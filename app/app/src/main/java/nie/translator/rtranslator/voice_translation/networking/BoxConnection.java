package nie.translator.rtranslator.voice_translation.networking;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP client for registration and heartbeat with the VoxSwap box.
 *
 * Protocol: length-prefixed messages (type 1B | payload_length 2B BE | payload).
 * Phone sends REGISTER on connect, HEARTBEAT every 2s.
 * Box responds with REGISTER_ACK (speaker_id + target languages).
 * Phone detects disconnect via IOException (no HEARTBEAT_ACK needed).
 *
 * Threading: connect() runs on a background executor (never blocks caller).
 * A reader thread handles incoming messages. A scheduled executor sends heartbeats.
 * Reconnection uses exponential backoff (1s -> 2s -> 4s -> 8s -> 10s cap).
 */
public class BoxConnection {

    private static final String TAG = "BoxConnection";

    /* Protocol message types */
    private static final int MSG_REGISTER = 0x01;
    private static final int MSG_REGISTER_ACK = 0x02;
    private static final int MSG_HEARTBEAT = 0x03;
    private static final int MSG_ERROR = 0xFF;

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    /* Timing */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int HEARTBEAT_INTERVAL_MS = 2000;
    private static final int MAX_RECONNECT_DELAY_MS = 10000;
    private static final int INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 3000;

    /* Connection state */
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();

    /* Threading */
    private ExecutorService connectionExecutor;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;
    private Thread readerThread;

    /* State */
    private volatile int speakerId = -1;
    private volatile boolean isConnected = false;
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private final AtomicBoolean shouldRun = new AtomicBoolean(false);

    /* Saved for reconnection */
    private String host;
    private int port;
    private String speakerName;
    private String sourceLanguage;

    /* Callback */
    private final ConnectionListener listener;

    public BoxConnection(ConnectionListener listener) {
        this.listener = listener;
    }

    /**
     * Connect to the box. Runs on a background thread — never blocks the caller.
     * Sends REGISTER after TCP connect, then starts heartbeat + reader thread.
     */
    public void connect(String host, int port, String speakerName, String sourceLanguage) {
        this.host = host;
        this.port = port;
        this.speakerName = speakerName;
        this.sourceLanguage = sourceLanguage;
        shouldRun.set(true);

        /* Clean up any existing executor from a prior connect() call */
        if (connectionExecutor != null) {
            shutdownExecutor(connectionExecutor, "old-connection");
        }
        connectionExecutor = Executors.newSingleThreadExecutor();
        connectionExecutor.execute(this::connectInternal);
    }

    /**
     * Perform the actual TCP connect + REGISTER on a background thread.
     * On failure, enters reconnection loop if shouldRun is still true.
     */
    private void connectInternal() {
        try {
            openSocketAndRegister();

            readerThread = new Thread(this::readerLoop, "BoxConnection-reader");
            readerThread.start();

            startHeartbeat();

        } catch (IOException e) {
            Log.w(TAG, "Connect failed: " + e.getMessage());
            listener.onConnectionError("Connect failed: " + e.getMessage());
            if (shouldRun.get()) {
                reconnect();
            }
        }
    }

    /**
     * Cleanly disconnect. Sets shouldRun=false first, then tears down all resources.
     * Safe to call from any thread. After this returns, no callbacks will fire.
     */
    public void disconnect() {
        shouldRun.set(false);
        isConnected = false;
        speakerId = -1;

        stopHeartbeat();
        closeSocket();

        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }

        shutdownExecutor(heartbeatExecutor, "heartbeat");
        heartbeatExecutor = null;

        shutdownExecutor(connectionExecutor, "connection");
        connectionExecutor = null;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public int getSpeakerId() {
        return speakerId;
    }

    /* --- Socket + REGISTER --- */

    /**
     * Opens a TCP socket, sends the REGISTER message.
     * Extracted to avoid duplication between connectInternal() and reconnect().
     */
    private void openSocketAndRegister() throws IOException {
        Socket newSocket = new Socket();
        newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        newSocket.setTcpNoDelay(true);

        synchronized (writeLock) {
            socket = newSocket;
            out = socket.getOutputStream();
            in = socket.getInputStream();
        }

        sendMessage(MSG_REGISTER, buildRegisterPayload());
        Log.d(TAG, "REGISTER sent: name=" + speakerName + " lang=" + sourceLanguage);
    }

    /**
     * Build REGISTER payload: speakerName (UTF-8, null-terminated) + sourceLanguage (UTF-8, null-terminated).
     */
    private byte[] buildRegisterPayload() {
        byte[] nameBytes = speakerName.getBytes(StandardCharsets.UTF_8);
        byte[] langBytes = sourceLanguage.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[nameBytes.length + 1 + langBytes.length + 1];
        System.arraycopy(nameBytes, 0, payload, 0, nameBytes.length);
        payload[nameBytes.length] = 0;
        System.arraycopy(langBytes, 0, payload, nameBytes.length + 1, langBytes.length);
        payload[payload.length - 1] = 0;
        return payload;
    }

    /* --- Heartbeat --- */

    /**
     * Start sending heartbeats every 2s. Shuts down any existing heartbeat executor
     * first to prevent thread leaks during reconnection.
     */
    private void startHeartbeat() {
        /* Shut down old executor to prevent thread leak on reconnect.
         * On reconnect, the old heartbeat executor's thread may still be alive
         * (it's the thread running reconnect()). shutdown() marks it for termination
         * after the current task finishes — no thread leak. */
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                sendMessage(MSG_HEARTBEAT, EMPTY_PAYLOAD);
            } catch (IOException e) {
                Log.w(TAG, "Heartbeat send failed: " + e.getMessage());
                handleDisconnect();
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    /* --- Message I/O --- */

    /**
     * Write a length-prefixed message: type (1B) | payload_length (2B BE) | payload.
     * Synchronized on writeLock to prevent interleaved writes from heartbeat + other threads.
     */
    private void sendMessage(int type, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (out == null) throw new IOException("Output stream is null");

            byte[] header = new byte[3];
            header[0] = (byte) type;
            header[1] = (byte) ((payload.length >> 8) & 0xFF);
            header[2] = (byte) (payload.length & 0xFF);

            out.write(header);
            if (payload.length > 0) {
                out.write(payload);
            }
            out.flush();
        }
    }

    /**
     * Blocking read of one length-prefixed message.
     * @throws IOException if the stream is closed or a read error occurs
     */
    private ReceivedMessage readMessage() throws IOException {
        if (in == null) throw new IOException("Input stream is null");

        int type = in.read();
        if (type == -1) throw new IOException("Stream closed");

        int lenHigh = in.read();
        int lenLow = in.read();
        if (lenHigh == -1 || lenLow == -1) throw new IOException("Stream closed reading length");

        int payloadLength = (lenHigh << 8) | lenLow;
        byte[] payload = new byte[payloadLength];
        int bytesRead = 0;
        while (bytesRead < payloadLength) {
            int read = in.read(payload, bytesRead, payloadLength - bytesRead);
            if (read == -1) throw new IOException("Stream closed reading payload");
            bytesRead += read;
        }

        return new ReceivedMessage(type, payload);
    }

    /* --- Reader thread --- */

    private void readerLoop() {
        try {
            while (shouldRun.get()) {
                ReceivedMessage msg = readMessage();
                switch (msg.type) {
                    case MSG_REGISTER_ACK:
                        handleRegisterAck(msg.payload);
                        break;
                    case MSG_ERROR:
                        handleError(msg.payload);
                        break;
                    default:
                        Log.w(TAG, "Unknown message type: 0x" + Integer.toHexString(msg.type));
                        break;
                }
            }
        } catch (IOException e) {
            if (shouldRun.get()) {
                Log.w(TAG, "Reader loop IOException: " + e.getMessage());
                handleDisconnect();
            }
        }
    }

    /**
     * Parse REGISTER_ACK: speaker_id (1B) + target_lang1 (null-term) + target_lang2 (null-term).
     */
    private void handleRegisterAck(byte[] payload) {
        if (payload.length < 1) {
            Log.e(TAG, "REGISTER_ACK payload too short");
            return;
        }

        speakerId = payload[0] & 0xFF;
        isConnected = true;
        isReconnecting.set(false);

        /* Parse target languages from remaining payload */
        String targetsStr = new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
        int nullIndex = targetsStr.indexOf('\0');
        String targetLang1 = (nullIndex >= 0) ? targetsStr.substring(0, nullIndex) : targetsStr;
        String targetLang2 = "";
        if (nullIndex >= 0 && nullIndex + 1 < targetsStr.length()) {
            String remainder = targetsStr.substring(nullIndex + 1);
            int secondNull = remainder.indexOf('\0');
            targetLang2 = (secondNull >= 0) ? remainder.substring(0, secondNull) : remainder;
        }

        Log.d(TAG, "REGISTER_ACK: speaker_id=" + speakerId
                + " target1=" + targetLang1 + " target2=" + targetLang2);

        listener.onConnected(speakerId);
    }

    private void handleError(byte[] payload) {
        if (payload.length < 1) return;
        int errorCode = payload[0] & 0xFF;
        String errorMsg = "";
        if (payload.length > 1) {
            errorMsg = new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
            int nullIdx = errorMsg.indexOf('\0');
            if (nullIdx >= 0) errorMsg = errorMsg.substring(0, nullIdx);
        }
        Log.e(TAG, "Box error: code=" + errorCode + " msg=" + errorMsg);
        listener.onConnectionError("Box error " + errorCode + ": " + errorMsg);
    }

    /* --- Disconnect + reconnect --- */

    /**
     * Called when an IOException is detected by the reader thread or heartbeat.
     * Uses compareAndSet to ensure only one thread handles the disconnect — prevents
     * the reader thread and heartbeat from racing into concurrent reconnections.
     */
    private void handleDisconnect() {
        if (!isReconnecting.compareAndSet(false, true)) {
            return;
        }

        boolean wasConnected = isConnected;
        isConnected = false;
        speakerId = -1;

        stopHeartbeat();
        closeSocket();

        /* Only fire onDisconnected if we were previously connected AND this
         * isn't an intentional disconnect (shouldRun would be false). */
        if (wasConnected && shouldRun.get()) {
            listener.onDisconnected();
        }

        if (shouldRun.get()) {
            reconnect();
        } else {
            isReconnecting.set(false);
        }
    }

    /**
     * Reconnection loop with exponential backoff (1s -> 2s -> 4s -> 8s -> 10s cap).
     * Runs on whichever thread detected the disconnect (reader or heartbeat).
     * Exits when either a reconnect succeeds or shouldRun becomes false.
     */
    private void reconnect() {
        int delay = INITIAL_RECONNECT_DELAY_MS;

        while (shouldRun.get()) {
            Log.d(TAG, "Reconnecting in " + delay + "ms...");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (!shouldRun.get()) break;

            try {
                openSocketAndRegister();

                readerThread = new Thread(this::readerLoop, "BoxConnection-reader");
                readerThread.start();

                startHeartbeat();

                /* isReconnecting resets to false when REGISTER_ACK arrives */
                return;

            } catch (IOException e) {
                Log.w(TAG, "Reconnect attempt failed: " + e.getMessage());
                delay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
            }
        }

        isReconnecting.set(false);
    }

    private void closeSocket() {
        synchronized (writeLock) {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "Error closing socket: " + e.getMessage());
            }
            socket = null;
            in = null;
            out = null;
        }
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                Log.w(TAG, name + " executor did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /* --- Internal types --- */

    private static class ReceivedMessage {
        final int type;
        final byte[] payload;

        ReceivedMessage(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /* --- Listener interface --- */

    public interface ConnectionListener {
        void onConnected(int speakerId);
        void onDisconnected();
        void onConnectionError(String reason);
    }
}
