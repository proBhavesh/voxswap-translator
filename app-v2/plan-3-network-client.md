# Plan 3: Network Client — TCP Control + UDP Audio Streaming

## Goal

Create the networking layer for the phone app: TCP client for registration/heartbeat with the VoxSwap box, and UDP sender for streaming translated TTS audio. This covers section 3 from `docs/todo-mobile.md`.

**Deferred**: Original audio capture (stream_type=0, section 2 from todo-mobile.md) — will be added in a later step.

## Current State

### What exists
- **Translation pipeline**: Recorder → Whisper STT → NLLB-200 translate → Android TTS (synthesizeToFile)
- **PCM hook**: `VoiceTranslationService.onPcmGenerated(PcmAudioData)` fires after TTS synthesis with raw 16-bit PCM bytes + sample rate + language info
- **WalkieTalkieService.onPcmGenerated()**: Currently just logs PCM size/type, ready to wire to UDP
- **Permissions**: `INTERNET` and `ACCESS_NETWORK_STATE` already in manifest
- **Box side**: All stubs — `stream-receiver.c` and `settings-server.c` are empty TODO files

### Audio format details
| Source | Format | Sample Rate | Channels | Notes |
|--------|--------|-------------|----------|-------|
| TTS (synthesizeToFile) | 16-bit PCM LE bytes | 22,050 or 24,000 Hz | mono | Needs resampling to 16kHz |
| Box expects | 16-bit PCM LE bytes | 16,000 Hz | mono | 640 bytes per 20ms packet |

### Key codebase facts (verified from source)
- **`PcmAudioData`** is `protected static` in `VoiceTranslationService`. Code in a different package (networking) cannot access it. `WalkieTalkieService` CAN access it (subclass). Solution: WalkieTalkieService extracts fields and passes raw `(byte[] pcmBytes, int sampleRate, int streamType)` to AudioStreamer.
- **`onPcmGenerated()` runs on TTS background thread**: Translator callbacks run on main thread, but `onDone()` (UtteranceProgressListener) runs on TTS background thread.

---

## Protocol Definition

The box side is all stubs, so we define the wire format here. Both phone and box will be built to match.

### TCP Control Protocol (port 7700)

All TCP messages use a simple **length-prefixed** format:

```
| msg_type (1B) | payload_length (2B, big-endian) | payload (variable) |
```

#### Message Types

| Type | Value | Direction | Payload |
|------|-------|-----------|---------|
| REGISTER | 0x01 | Phone → Box | speaker_name (UTF-8, null-terminated) + source_language (UTF-8, null-terminated) |
| REGISTER_ACK | 0x02 | Box → Phone | speaker_id (1B) + target_lang1 (UTF-8, null-terminated) + target_lang2 (UTF-8, null-terminated) |
| HEARTBEAT | 0x03 | Phone → Box | (empty — payload_length = 0) |
| ERROR | 0xFF | Box → Phone | error_code (1B) + error_message (UTF-8, null-terminated) |

**Why length-prefixed**: Simpler than line-delimited for binary data. The 2-byte length (max 65535) is more than enough for registration payloads (~100 bytes).

**Why null-terminated strings inside**: Easy to parse in C (`strchr(buf, '\0')`) and in Java (`indexOf('\0')`). No need for a separate length field per string.

**No HEARTBEAT_ACK**: The phone sends heartbeats so the box knows the phone is alive (box disconnects phone after 3 missed heartbeats = 6 seconds). The phone detects disconnection via TCP `IOException` — when the socket write/read fails, the connection is dead. This is simpler than tracking ACK timeouts and equally reliable.

#### Registration Flow
```
Phone                                   Box (10.0.0.1:7700)
  |                                      |
  |--- TCP connect (5s timeout) -------->|
  |                                      |
  |--- REGISTER ----->                   |
  |    type=0x01                         |
  |    payload: "Pixel 7\0en\0"          |
  |                                      |
  |<--- REGISTER_ACK -                   |
  |     type=0x02                        |
  |     payload: speaker_id=0            |
  |              "fr\0"  "de\0"          |
  |                                      |
  |--- HEARTBEAT ----->  (every 2s)      |
  |    type=0x03, len=0                  |
  |                                      |
  |    (no ACK — phone detects           |
  |     disconnect via IOException)      |
```

#### Reconnection Logic
- If TCP connection drops (IOException on send/read): wait 1s, try reconnecting
- Exponential backoff: 1s → 2s → 4s → 8s → cap at 10s
- On reconnect: re-send REGISTER (box may have forgotten us)
- On reconnect: reset UDP sequence/timestamp counters to 0
- `AtomicBoolean isReconnecting` prevents concurrent reconnection attempts from multiple threads

### UDP Audio Protocol (port 7701)

```
Offset  Size  Field         Format              Description
──────  ────  ──────────    ──────────────────  ─────────────────────────────────
0       1     speaker_id    uint8               0-2 (assigned by box during REGISTER)
1       1     stream_type   uint8               0=original, 1=target_lang1, 2=target_lang2
2       4     sequence      uint32 big-endian   Per-stream packet counter (wraps at 2^32)
6       4     timestamp     uint32 big-endian   Sample offset from stream start (wraps at 2^32)
10      640   pcm_payload   int16 LE samples    320 samples × 2 bytes = 20ms at 16kHz mono
──────────────────────────────────────────────────────────────────────────────────
Total: 650 bytes per packet
```

**Byte order choices**:
- Header fields (sequence, timestamp): **big-endian** — network byte order, standard for protocol headers
- PCM payload: **little-endian** — native format for ARM and x86, matches what AudioRecord/TTS produces

**Sequence number**: Per-stream counter. Each stream has its own sequence starting at 0 (reset on reconnect). Lets the box detect gaps per stream independently.

**Timestamp**: Sample offset from stream start. Increments by 320 each packet (320 samples = 20ms at 16kHz). At 16kHz, wraps after ~74.5 hours — far beyond any session. Reset to 0 on reconnect.

**Sending rate**: Currently only stream_type 1 and 2 (TTS translations). 50 packets/second per stream = 100 packets/second total per phone. At 650 bytes per packet, that's ~63 KB/s per phone — trivial for WiFi.

**Burst sending**: TTS output arrives as a complete utterance (1-5 seconds of audio) all at once after synthesis. We send all packets in a burst (not paced at 20ms intervals). Pacing would add `numPackets × 20ms` of additional latency on top of the STT+translate+TTS delay. **Box-side requirement**: The box MUST buffer received packets and play them at real-time rate based on the timestamp field (jitter buffer). This is standard for real-time audio over UDP.

**Packet loss**: Negligible on a local WiFi hotspot with 3 devices. A dropped packet = 20ms gap = imperceptible. No retransmission, no FEC.

---

## Files to Create

### 1. `PcmResampler.java` (static utility)
**Package**: `nie.translator.rtranslator.voice_translation.networking`
**Purpose**: Resample 16-bit PCM from any sample rate to 16kHz

Android TTS outputs at 22050 or 24000 Hz. The box expects 16kHz. We need a resampler.

**Algorithm**: Linear interpolation.

```java
public final class PcmResampler {
    private PcmResampler() {}

    /**
     * Resample 16-bit LE PCM from sourceSampleRate to 16000 Hz using linear interpolation.
     * @param input byte array of 16-bit little-endian samples
     * @param sourceSampleRate the source sample rate (e.g. 22050, 24000)
     * @return resampled byte array at 16000 Hz. Returns input unchanged if already 16000 Hz.
     */
    public static byte[] resampleTo16kHz(byte[] input, int sourceSampleRate) {
        if (sourceSampleRate == 16000) return input;

        int inputSamples = input.length / 2;
        int outputSamples = (int) ((long) inputSamples * 16000 / sourceSampleRate);
        byte[] output = new byte[outputSamples * 2];

        double ratio = (double) sourceSampleRate / 16000;
        for (int i = 0; i < outputSamples; i++) {
            double srcPos = i * ratio;
            int srcIndex = (int) srcPos;
            double frac = srcPos - srcIndex;

            short s0 = readSample(input, srcIndex);
            short s1 = (srcIndex + 1 < inputSamples) ? readSample(input, srcIndex + 1) : s0;
            short interpolated = (short) (s0 + frac * (s1 - s0));

            writeSample(output, i, interpolated);
        }
        return output;
    }

    private static short readSample(byte[] buf, int sampleIndex) {
        int byteIndex = sampleIndex * 2;
        return (short) ((buf[byteIndex] & 0xFF) | (buf[byteIndex + 1] << 8));
    }

    private static void writeSample(byte[] buf, int sampleIndex, short value) {
        int byteIndex = sampleIndex * 2;
        buf[byteIndex] = (byte) (value & 0xFF);
        buf[byteIndex + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
```

**Why linear interpolation is adequate**: TTS speech energy is concentrated below 4kHz. When downsampling from 22050→16000 (Nyquist drops from 11025 to 8000), frequencies between 8-11kHz could alias — but TTS speech has negligible energy there. The result is indistinguishable from a proper sinc resample for this use case. If quality becomes an issue, we can upgrade later.

**Why a separate utility class**: Pure math, no Android dependencies. Keeps AudioStreamer focused on networking. Easy to unit test.

### 2. `BoxConnection.java`
**Package**: `nie.translator.rtranslator.voice_translation.networking`
**Purpose**: TCP client for registration and heartbeat

```
BoxConnection
├── Constants
│   ├── MSG_REGISTER = 0x01
│   ├── MSG_REGISTER_ACK = 0x02
│   ├── MSG_HEARTBEAT = 0x03
│   ├── MSG_ERROR = 0xFF
│   ├── CONNECT_TIMEOUT_MS = 5000
│   ├── HEARTBEAT_INTERVAL_MS = 2000
│   └── MAX_RECONNECT_DELAY_MS = 10000
│
├── Fields
│   ├── Socket socket
│   ├── InputStream in / OutputStream out
│   ├── ScheduledExecutorService heartbeatExecutor
│   ├── ExecutorService connectionExecutor (single thread for connect/reconnect)
│   ├── Thread readerThread (reads responses from box)
│   ├── volatile int speakerId = -1 (assigned after REGISTER_ACK)
│   ├── volatile boolean isConnected = false
│   ├── AtomicBoolean isReconnecting = false (prevents concurrent reconnections)
│   ├── AtomicBoolean shouldRun = true (controls lifecycle)
│   ├── ConnectionListener listener
│   ├── String host / int port (saved for reconnection)
│   └── String speakerName / String sourceLanguage (saved for re-registration)
│
├── Methods
│   ├── connect(String host, int port, String speakerName, String sourceLanguage)
│   │     — runs on connectionExecutor (never blocks caller)
│   │     — Socket.connect() with 5s timeout
│   │     — sends REGISTER, starts reader thread + heartbeat timer
│   ├── disconnect()
│   │     — sets shouldRun=false, cancels heartbeat, closes socket, awaits threads
│   ├── isConnected()
│   ├── getSpeakerId()
│   ├── sendHeartbeat()       — called every 2s by heartbeatExecutor
│   ├── sendMessage(int type, byte[] payload) — writes length-prefixed message to TCP
│   ├── readMessage()         — blocking read from socket, parses type + payload
│   └── reconnect()           — exponential backoff (1s→2s→4s→8s→10s cap)
│                               — guarded by isReconnecting AtomicBoolean
│
└── Interface: ConnectionListener
    ├── onConnected(int speakerId)
    ├── onDisconnected()
    └── onConnectionError(String reason)
```

**Threading model**:
- `connect()` submits to `connectionExecutor` — never blocks the caller (main thread)
- `readerThread` loops on `readMessage()`, dispatches REGISTER_ACK/ERROR to listener
- `heartbeatExecutor` fires `sendHeartbeat()` every 2 seconds
- If any `sendMessage()` or `readMessage()` throws `IOException` → socket is dead → trigger `reconnect()`
- `reconnect()` uses `isReconnecting.compareAndSet(false, true)` to prevent concurrent attempts from reader thread + heartbeat thread racing
- All listener callbacks fire on background threads. `notifyToClient()` is thread-safe (Messenger pattern).
- `disconnect()` sets `shouldRun=false` first, then closes socket (causes reader thread to throw IOException and exit), then shuts down executors with timeout

**Error handling**:
- `Socket.connect()` with 5-second timeout — fails fast if box is unreachable
- All socket I/O wrapped in try/catch IOException
- On any IOException: close socket, fire `onDisconnected()`, start `reconnect()` if `shouldRun` is true
- `reconnect()` re-calls the full connect+register flow

### 3. `AudioStreamer.java`
**Package**: `nie.translator.rtranslator.voice_translation.networking`
**Purpose**: Resamples PCM to 16kHz, packetizes into 20ms chunks, sends over UDP

```
AudioStreamer
├── Constants
│   ├── BOX_UDP_PORT = 7701
│   ├── SAMPLES_PER_PACKET = 320
│   ├── BYTES_PER_PACKET = 640
│   ├── HEADER_SIZE = 10
│   └── PACKET_SIZE = 650
│
├── Fields
│   ├── DatagramSocket udpSocket
│   ├── InetAddress boxAddress
│   ├── int speakerId
│   ├── int[] sequenceCounters = new int[3]  (per stream_type)
│   ├── int[] sampleOffsets = new int[3]     (per stream_type timestamp)
│   ├── ExecutorService sendExecutor         (single thread for sending)
│   └── volatile boolean isActive = false
│
├── Methods
│   ├── start(String host, int speakerId)
│   │     — creates DatagramSocket, resolves boxAddress, sets speakerId
│   │     — resets sequenceCounters and sampleOffsets to 0
│   ├── stop()
│   │     — closes socket, shuts down executor, sets isActive=false
│   ├── isActive()
│   ├── resetCounters()           — called on reconnect (resets sequence + timestamp to 0)
│   ├── streamTtsAudio(byte[] pcmBytes, int sampleRate, int streamType)
│   │     — resamples to 16kHz via PcmResampler, then calls streamAudio()
│   ├── streamAudio(byte[] pcm16kHz, int streamType)
│   │     — packetize into 640-byte chunks, send on sendExecutor
│   └── buildPacket(int streamType, int seq, int timestamp, byte[] chunk)
│         — assembles 650-byte UDP packet with big-endian header
│
└── (No listener — fire-and-forget UDP)
```

**Note on `streamTtsAudio` signature**: Takes `(byte[] pcmBytes, int sampleRate, int streamType)` — raw fields, NOT `PcmAudioData`. This is because `PcmAudioData` is `protected static` in `VoiceTranslationService` and not accessible from this package. `WalkieTalkieService` (which IS a subclass and CAN access `PcmAudioData`) extracts the fields before calling this method.

**Single send thread**: UDP `DatagramSocket.send()` is fast (~microseconds per packet) but we don't want to block the TTS callback thread. The single-threaded executor ensures packets within the same stream are sent in order. Cross-stream interleaving is fine — the box separates by stream_type.

---

## Design Details

### PCM Resampling

#### When it's needed
- **TTS audio (stream_type 1,2)**: Android TTS outputs 22050 or 24000 Hz → **resampling needed**
- If TTS outputs 16kHz (some Samsung engines do): resampler returns input unchanged (no-op)

#### Algorithm: Linear interpolation
For each output sample at position `i`:
1. Map to input position: `srcPos = i * (srcRate / 16000)`
2. Find the two nearest input samples
3. Interpolate: `output = s0 + frac * (s1 - s0)`

For 22050→16000 (ratio 1.378), every output sample maps between two input samples. The linear interpolation acts as a crude low-pass filter.

#### Why not a better resampler
- Android's `AudioTrack` has no built-in resampler for byte arrays
- `SoundPool` and `MediaCodec` are for file-based audio, not byte arrays
- Third-party libs (Oboe, Sonic) add native dependencies we don't need
- Linear interpolation is ~20 lines of code, zero dependencies, sounds fine for speech
- If quality becomes an issue later, we can upgrade to a windowed-sinc resampler

#### Sample rate is read from WAV header
`PcmAudioData.sampleRate` tells us the exact TTS output rate. No guessing.

### UDP Packetization

#### How `streamAudio(byte[] pcm16kHz, int streamType)` works

TTS output arrives as a complete utterance (all at once after synthesis). AudioStreamer chops it into 640-byte (320-sample) chunks and sends each as a UDP packet:

```java
public void streamAudio(byte[] pcm16kHz, int streamType) {
    if (!isActive) return;
    sendExecutor.execute(() -> {
        int offset = 0;
        while (offset < pcm16kHz.length) {
            int chunkSize = Math.min(BYTES_PER_PACKET, pcm16kHz.length - offset);
            byte[] chunk;

            if (chunkSize < BYTES_PER_PACKET) {
                /* Last chunk: pad with silence (zeros) to fill 640 bytes */
                chunk = new byte[BYTES_PER_PACKET];
                System.arraycopy(pcm16kHz, offset, chunk, 0, chunkSize);
            } else {
                chunk = new byte[BYTES_PER_PACKET];
                System.arraycopy(pcm16kHz, offset, chunk, 0, BYTES_PER_PACKET);
            }

            byte[] packet = buildPacket(streamType, sequenceCounters[streamType], sampleOffsets[streamType], chunk);
            sendPacket(packet);

            sequenceCounters[streamType]++;
            sampleOffsets[streamType] += SAMPLES_PER_PACKET;
            offset += BYTES_PER_PACKET;
        }
    });
}
```

#### `buildPacket()` layout

```java
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
```

---

## New Package Structure

```
nie.translator.rtranslator.voice_translation.networking/
├── BoxConnection.java       — TCP client (registration, heartbeat, reconnect)
├── AudioStreamer.java        — UDP sender (resample, packetize, send)
└── PcmResampler.java        — Static utility (linear interpolation resample to 16kHz)
```

---

## `build.gradle` / `AndroidManifest.xml` — No changes needed

Standard Java `java.net.Socket`, `java.net.DatagramSocket`, and `java.util.concurrent` are all we need. No external dependencies. Existing permissions (`INTERNET`, `ACCESS_NETWORK_STATE`) are sufficient.

---

## Risk Notes

1. **UDP send from executor thread**: `DatagramSocket.send()` is non-blocking for small packets. A burst of 150 packets (~97KB) completes in <10ms on WiFi. No blocking risk.

2. **Reconnection storm prevention**: `AtomicBoolean isReconnecting` prevents multiple threads from triggering concurrent reconnections. Only one reconnection loop runs at a time.

3. **Executor shutdown ordering in disconnect()**: `shouldRun` set to false first → socket closed (causes reader IOException) → heartbeat executor shutdown → connection executor shutdown. Clean teardown without races.

4. **Sequence counter overflow**: `int` wraps at 2^31 (Java signed). At 50 packets/sec, wraps after ~497 days. The box should treat sequence as unsigned for gap detection, but overflow is not a practical concern.

5. **Box IP hardcoded**: `10.0.0.1` is always the gateway when connected to the box's hotspot. For testing, the IP needs to be changed. Currently hardcoded in `WalkieTalkieService.onStartCommand()`. Could make it configurable via Settings screen later.

---

## Implementation Order (this plan only)

| Step | File | What | Depends on |
|------|------|------|------------|
| 1 | `PcmResampler.java` | Create static resampling utility | Nothing |
| 2 | `BoxConnection.java` | Create TCP client with registration + heartbeat + reconnect | Nothing |
| 3 | `AudioStreamer.java` | Create UDP sender with packetization + resampling | Step 1 |

Steps 1 and 2 can be done in parallel.
