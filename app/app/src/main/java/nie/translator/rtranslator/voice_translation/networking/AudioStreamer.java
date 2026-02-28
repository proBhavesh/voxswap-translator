package nie.translator.rtranslator.voice_translation.networking;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Streams translated TTS audio to the VoxSwap box over UDP.
 *
 * Takes raw 16-bit PCM at any sample rate, resamples to 16kHz via {@link PcmResampler},
 * packetizes into 650-byte UDP packets (10B header + 640B payload = 20ms per packet),
 * and sends in a burst. The box must use a jitter buffer to play at real-time rate.
 *
 * UDP packet format:
 *   speaker_id (1B) | stream_type (1B) | sequence (4B BE) | timestamp (4B BE) | PCM (640B LE)
 *
 * Thread safety: streamTtsAudio() can be called from any thread (typically the TTS background
 * thread). The actual resampling runs on the caller's thread, then packetization + sending
 * is dispatched to a single-threaded executor to avoid blocking the caller and to ensure
 * packets within the same stream are sent in order.
 */
public class AudioStreamer {

    private static final String TAG = "AudioStreamer";

    /* Protocol constants */
    private static final int BOX_UDP_PORT = 7701;
    private static final int SAMPLES_PER_PACKET = 320;
    private static final int BYTES_PER_PACKET = 640;   /* 320 samples x 2 bytes */
    private static final int HEADER_SIZE = 10;
    private static final int PACKET_SIZE = 650;        /* HEADER_SIZE + BYTES_PER_PACKET */
    private static final int NUM_STREAM_TYPES = 3;     /* 0=original, 1=target1, 2=target2 */

    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 3000;

    /* Networking — volatile because stop() and sendExecutor access these from different threads */
    private volatile DatagramSocket udpSocket;
    private InetAddress boxAddress;
    private int speakerId;

    /* Per-stream counters — only accessed from sendExecutor (single thread), so no sync needed */
    private final int[] sequenceCounters = new int[NUM_STREAM_TYPES];
    private final int[] sampleOffsets = new int[NUM_STREAM_TYPES];

    /* Threading */
    private ExecutorService sendExecutor;
    private volatile boolean isActive = false;

    /**
     * Start the streamer. Creates UDP socket, resolves box address, resets counters.
     * If already active, stops the previous session first to prevent resource leaks.
     *
     * @param host box IP address (e.g. "10.0.0.1")
     * @param speakerId assigned by box during REGISTER_ACK (0-2)
     */
    public void start(String host, int speakerId) {
        /* Clean up any existing session (e.g. rapid reconnect) */
        if (isActive) {
            stop();
        }

        this.speakerId = speakerId;
        resetCounters();

        try {
            boxAddress = InetAddress.getByName(host);
            udpSocket = new DatagramSocket();
            sendExecutor = Executors.newSingleThreadExecutor();
            isActive = true;
            Log.d(TAG, "Started: host=" + host + " speakerId=" + speakerId);
        } catch (UnknownHostException e) {
            Log.e(TAG, "Failed to resolve host: " + host, e);
        } catch (SocketException e) {
            Log.e(TAG, "Failed to create UDP socket", e);
        }
    }

    /**
     * Stop the streamer. Closes socket, shuts down executor.
     * Safe to call even if not started or already stopped.
     */
    public void stop() {
        isActive = false;

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        udpSocket = null;

        if (sendExecutor != null) {
            sendExecutor.shutdown();
            try {
                if (!sendExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    sendExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                sendExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            sendExecutor = null;
        }

        Log.d(TAG, "Stopped");
    }

    public boolean isActive() {
        return isActive;
    }

    /**
     * Reset sequence and timestamp counters for all streams. Called on reconnect.
     */
    public void resetCounters() {
        Arrays.fill(sequenceCounters, 0);
        Arrays.fill(sampleOffsets, 0);
    }

    /**
     * Resample TTS audio to 16kHz and send over UDP.
     * Called from WalkieTalkieService.onPcmGenerated() with raw fields extracted from PcmAudioData
     * (PcmAudioData is protected in VoiceTranslationService and not accessible from this package).
     *
     * @param pcmBytes raw 16-bit LE PCM bytes from TTS
     * @param sampleRate source sample rate (e.g. 22050, 24000)
     * @param streamType 0=original, 1=target_lang1, 2=target_lang2
     */
    public void streamTtsAudio(byte[] pcmBytes, int sampleRate, int streamType) {
        if (!isActive) return;
        byte[] pcm16kHz = PcmResampler.resampleTo16kHz(pcmBytes, sampleRate);
        streamAudio(pcm16kHz, streamType);
    }

    /**
     * Packetize 16kHz PCM into 20ms chunks and send as UDP packets.
     * Runs on the send executor to avoid blocking the caller (TTS thread).
     */
    private void streamAudio(byte[] pcm16kHz, int streamType) {
        if (!isActive || streamType < 0 || streamType >= NUM_STREAM_TYPES) return;

        sendExecutor.execute(() -> {
            int offset = 0;
            int packetCount = 0;
            while (offset < pcm16kHz.length && isActive) {
                int chunkSize = Math.min(BYTES_PER_PACKET, pcm16kHz.length - offset);
                byte[] chunk = new byte[BYTES_PER_PACKET];

                /* Copy PCM data; last chunk is zero-padded (silence) if < 640 bytes */
                System.arraycopy(pcm16kHz, offset, chunk, 0, chunkSize);

                byte[] packet = buildPacket(streamType, sequenceCounters[streamType], sampleOffsets[streamType], chunk);
                sendPacket(packet);

                sequenceCounters[streamType]++;
                sampleOffsets[streamType] += SAMPLES_PER_PACKET;
                offset += BYTES_PER_PACKET;
                packetCount++;

                /* Pace sends at ~2x real-time: each packet = 20ms of audio,
                 * so sleep 10ms per packet. Prevents WiFi from dropping packets
                 * when hundreds are sent as a burst after TTS completes. */
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        });
    }

    /**
     * Assemble a 650-byte UDP packet with big-endian header and LE PCM payload.
     */
    private byte[] buildPacket(int streamType, int seq, int timestamp, byte[] pcmChunk) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = (byte) speakerId;
        packet[1] = (byte) streamType;
        /* sequence: uint32 big-endian */
        packet[2] = (byte) (seq >> 24);
        packet[3] = (byte) (seq >> 16);
        packet[4] = (byte) (seq >> 8);
        packet[5] = (byte) seq;
        /* timestamp: uint32 big-endian */
        packet[6] = (byte) (timestamp >> 24);
        packet[7] = (byte) (timestamp >> 16);
        packet[8] = (byte) (timestamp >> 8);
        packet[9] = (byte) timestamp;
        /* PCM payload */
        System.arraycopy(pcmChunk, 0, packet, HEADER_SIZE, BYTES_PER_PACKET);
        return packet;
    }

    /**
     * Send a UDP packet. Captures socket reference locally to avoid NPE if stop()
     * is called concurrently (stop nulls udpSocket from another thread).
     */
    private void sendPacket(byte[] packet) {
        DatagramSocket socket = udpSocket;
        if (socket == null || socket.isClosed()) return;

        try {
            DatagramPacket datagramPacket = new DatagramPacket(
                    packet, packet.length, boxAddress, BOX_UDP_PORT);
            socket.send(datagramPacket);
        } catch (IOException e) {
            /* UDP send failure on local WiFi is rare; log but don't crash */
            Log.w(TAG, "UDP send failed: " + e.getMessage());
        }
    }
}
