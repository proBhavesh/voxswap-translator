# Plan 4: Wire Networking into Translation Pipeline

## Goal

Connect the networking layer (BoxConnection + AudioStreamer from Plan 3) into the existing translation pipeline so that TTS-generated PCM audio is streamed to the box over UDP, and connection status is shown in the UI. This covers section 4 from `docs/todo-mobile.md`.

**Prerequisite**: Plan 3 must be implemented first (BoxConnection, AudioStreamer, PcmResampler).

## Integration Points

- `WalkieTalkieService.onCreate()` — initialize networking objects
- `WalkieTalkieService.onStartCommand()` — has sourceLanguage, targetLanguage1, targetLanguage2 — trigger TCP connect
- `WalkieTalkieService.onPcmGenerated(PcmAudioData)` — TTS PCM available (stream_type 1 and 2)
- `WalkieTalkieService.onDestroy()` — tear down networking
- `WalkieTalkieFragment.setRecordingState()` — controls statusDot, statusLabel — can show connection state

### Key codebase facts (verified from source)
- **`GeneralService.notifyToClient(Bundle)`** (line 44): Uses `clientMessenger.send(message)`. `Messenger.send()` is thread-safe — it posts to the target Handler's message queue. Safe to call from any thread.
- **`CustomLocale.equals()`** (line 179): Returns `false` if either object's `getCountry()` is null, even for the same language. Violates reflexivity. **Must use `equalsLanguage()` for stream type detection** — compares only ISO3 language code, ignoring country.
- **`PcmAudioData`** is `protected static` in `VoiceTranslationService`. Code in a different package (networking) cannot access it. `WalkieTalkieService` CAN access it (subclass). Solution: WalkieTalkieService extracts fields and passes raw `(byte[] pcmBytes, int sampleRate, int streamType)` to AudioStreamer.
- **Translator callbacks run on main thread**: `mainHandler.post(() -> responseListener.onTranslatedText(...))` — so `speak()` is called on main thread. But `onDone()` (UtteranceProgressListener) runs on TTS background thread. So `onPcmGenerated()` runs on TTS background thread.

---

## Files to Modify

### 1. `VoiceTranslationService.java` — Add connection status callback constants

Small changes only — add callback constants for the connection status notification.

**Add constants** (after existing callback constants, ~line 66):
```java
public static final int ON_BOX_CONNECTED = 30;
public static final int ON_BOX_DISCONNECTED = 31;
```

**Add to `VoiceTranslationServiceCallback`** (after existing callback methods, ~line 895):
```java
public void onBoxConnected() {}
public void onBoxDisconnected() {}
```

**Add to `executeCallback()` in `VoiceTranslationServiceCommunicator`** (after ON_ERROR case, ~line 796):
```java
case ON_BOX_CONNECTED: {
    for (int i = 0; i < clientCallbacks.size(); i++) {
        clientCallbacks.get(i).onBoxConnected();
    }
    return true;
}
case ON_BOX_DISCONNECTED: {
    for (int i = 0; i < clientCallbacks.size(); i++) {
        clientCallbacks.get(i).onBoxDisconnected();
    }
    return true;
}
```

### 2. `WalkieTalkieService.java` — Wire networking into pipeline

**New fields:**
```java
private BoxConnection boxConnection;
private AudioStreamer audioStreamer;
```

**Changes to `onCreate()`** — initialize networking objects:
```java
audioStreamer = new AudioStreamer();
boxConnection = new BoxConnection(new BoxConnection.ConnectionListener() {
    @Override
    public void onConnected(int speakerId) {
        audioStreamer.start("10.0.0.1", speakerId);
        notifyConnectionStatus(true);
        Log.d("VoxSwap", "Connected to box, speaker_id=" + speakerId);
    }

    @Override
    public void onDisconnected() {
        audioStreamer.stop();
        notifyConnectionStatus(false);
        Log.d("VoxSwap", "Disconnected from box");
    }

    @Override
    public void onConnectionError(String reason) {
        Log.w("VoxSwap", "Connection error: " + reason);
    }
});
```

**Changes to `onStartCommand()`** — trigger TCP connection after languages are known:
```java
/* After reading sourceLanguage, targetLanguage1, targetLanguage2 from intent: */

/* Connect to box (runs on background thread, doesn't block) */
if (isFirstStart && sourceLanguage != null) {
    String speakerName = android.os.Build.MODEL;  /* e.g. "Pixel 7", "Galaxy S24" */
    boxConnection.connect("10.0.0.1", 7700, speakerName, sourceLanguage.getCode());
}
```

**Replace `onPcmGenerated()`** — stream TTS audio over UDP:
```java
@Override
protected void onPcmGenerated(PcmAudioData pcmData) {
    if (audioStreamer == null || !audioStreamer.isActive()) return;

    int streamType;
    if (pcmData.language != null && pcmData.language.equalsLanguage(targetLanguage1)) {
        streamType = 1;
    } else if (pcmData.language != null && pcmData.language.equalsLanguage(targetLanguage2)) {
        streamType = 2;
    } else {
        Log.w("VoxSwap", "PCM generated for unknown language, skipping stream");
        return;
    }

    /* Extract fields — PcmAudioData is protected and can't be passed to AudioStreamer directly */
    audioStreamer.streamTtsAudio(pcmData.pcmBytes, pcmData.sampleRate, streamType);
}
```

**Key: uses `equalsLanguage()` not `equals()`** — `CustomLocale.equals()` can return false if `getCountry()` is null, even for the same language. `equalsLanguage()` compares only the ISO3 language code, which is what we want for stream type detection.

**Add `notifyConnectionStatus()` method:**
```java
private void notifyConnectionStatus(boolean connected) {
    Bundle bundle = new Bundle();
    bundle.putInt("callback", connected ? ON_BOX_CONNECTED : ON_BOX_DISCONNECTED);
    WalkieTalkieService.super.notifyToClient(bundle);
}
```

**Changes to `onDestroy()`:**
```java
@Override
public void onDestroy() {
    if (boxConnection != null) boxConnection.disconnect();
    if (audioStreamer != null) audioStreamer.stop();
    speechRecognizer.removeCallback(speechRecognizerCallback);
    super.onDestroy();
}
```

### 3. `WalkieTalkieFragment.java` — Connection status in UI

**In `HomeServiceCallback`, add:**
```java
@Override
public void onBoxConnected() {
    if (statusDot != null && statusLabel != null) {
        setStatusDotColor(ContextCompat.getColor(requireContext(), R.color.brand_primary));
        statusLabel.setText("Connected to Box");
    }
}

@Override
public void onBoxDisconnected() {
    if (statusDot != null && statusLabel != null && !isRecording) {
        setStatusDotColor(ContextCompat.getColor(requireContext(), R.color.gray_400));
        statusLabel.setText(R.string.status_ready);
    }
}
```

Uses existing `brand_primary` color (indigo) for connected state — distinct from the green recording dot. No new color resource needed.

---

## Connection Lifecycle

```
App Launch
    │
    ▼
WalkieTalkieService.onCreate()
    │ create BoxConnection + AudioStreamer (not connected yet)
    │
    ▼
WalkieTalkieService.onStartCommand(intent with languages)
    │ sourceLanguage, targetLanguage1, targetLanguage2 now known
    │
    ├──► BoxConnection.connect("10.0.0.1", 7700, "Pixel 7", "en")
    │       │ (runs on connectionExecutor — non-blocking)
    │       │
    │       ├── Socket.connect() with 5s timeout
    │       ├── sendMessage(REGISTER, "Pixel 7\0en\0")
    │       ├── readerThread starts, reads REGISTER_ACK: speaker_id=0
    │       ├── listener.onConnected(0)
    │       │     └── AudioStreamer.start("10.0.0.1", speakerId=0)
    │       │     └── notifyConnectionStatus(true) → UI turns dot indigo
    │       └── Heartbeat timer starts (every 2s)
    │
    ▼
[Normal operation — translation pipeline running]
    │
    │ onPcmGenerated(target1) → streamTtsAudio(pcmBytes, sampleRate, 1) → resample → UDP burst
    │ onPcmGenerated(target2) → streamTtsAudio(pcmBytes, sampleRate, 2) → resample → UDP burst
    │
    ▼
WalkieTalkieService.onDestroy()
    │ BoxConnection.disconnect() → closes TCP, stops heartbeat, awaits threads
    │ AudioStreamer.stop() → closes UDP socket, shuts down executor
    ▼
[Done]
```

### Graceful degradation
If the box is unreachable (phone not on VoxSwap WiFi, box not powered on):
- `BoxConnection.connect()` times out after 5s → `onConnectionError()` logged → reconnection loop begins
- `audioStreamer.isActive()` returns false
- `onPcmGenerated()` skips streaming (early return)
- **Translation pipeline works normally** — local TTS playback through AudioTrack is unaffected
- User hears translations on phone speaker, box just doesn't get the audio
- When phone later connects to VoxSwap WiFi, reconnection loop succeeds

### Reconnection scenario
```
[Connected, streaming normally]
    │
    ▼
Box crashes / WiFi drops
    │
    ├── heartbeatExecutor tries sendHeartbeat() → IOException
    │   OR readerThread readMessage() → IOException
    │
    ├── First thread to detect: closes socket, fires onDisconnected()
    │   (audioStreamer.stop(), UI dot → gray)
    │
    ├── reconnect() triggered (guarded by isReconnecting AtomicBoolean)
    │   wait 1s → try connect → fail → wait 2s → try → fail → wait 4s → ...
    │
    ├── Box comes back online
    │   connect succeeds → REGISTER → REGISTER_ACK → onConnected()
    │   audioStreamer.start() with fresh counters (sequence=0, timestamp=0)
    │   UI dot → indigo
    │
    ▼
[Streaming resumes]
```

---

## Thread Safety Notes

1. **onPcmGenerated() thread safety**: Called from TTS background thread. `audioStreamer.streamTtsAudio()` submits to `sendExecutor` (non-blocking). Does not block the TTS thread. Safe.

2. **notifyToClient() from background thread**: `BoxConnection.ConnectionListener` fires on background thread. `notifyToClient()` uses `Messenger.send()` which is thread-safe — posts to target Handler's message queue (main thread). Verified from `GeneralService.java:44-53`.

---

## Test Strategy

### Python test receiver
Create `tools/test-receiver.py` — a simple script that:
1. Listens on TCP port 7700
2. On REGISTER message: responds with REGISTER_ACK (speaker_id=0, targets="fr\0de\0")
3. Listens on UDP port 7701 and prints packet headers (speaker_id, stream_type, sequence, timestamp, payload_size)
4. Optionally writes received PCM to a WAV file for playback verification

Run on a laptop connected to the same WiFi as the phone. For testing, change the box IP from `10.0.0.1` to the laptop's LAN IP (or use `10.0.2.2` on Android emulator which routes to host).

### Test scenarios
1. **Happy path**: Phone starts → connects → registers → speaks → verify UDP packets arrive with correct headers and incrementing sequence numbers
2. **Box not reachable**: Phone starts without VoxSwap WiFi → verify translation pipeline works locally, no crashes, reconnection log messages appear
3. **Connection drop**: Kill test receiver mid-stream → verify `onDisconnected` fires, reconnection loop starts, no crashes
4. **Audio playback**: Save received PCM packets to WAV → play back → verify it sounds correct (translated speech)
5. **Resampling**: Verify received audio at 16kHz sounds correct (no pitch shift, no artifacts)

---

## Implementation Order (this plan only)

| Step | File | What | Depends on |
|------|------|------|------------|
| 1 | `VoiceTranslationService.java` | Add connection status callback constants + methods | Nothing |
| 2 | `WalkieTalkieService.java` | Wire networking into pipeline (onPcmGenerated, lifecycle) | Plan 3 + Step 1 |
| 3 | `WalkieTalkieFragment.java` | Connection status in UI (status dot + label) | Step 1 |
| 4 | `tools/test-receiver.py` | Python test script | Plan 3 |
| 5 | Build and test | Verify compilation + end-to-end with test receiver | All |

Steps 1 and 3 can be done in parallel. Step 2 requires Plan 3 to be complete.

---

## What This Does NOT Do (Deferred)

- **Original audio capture** (stream_type=0, section 2 from todo-mobile.md) — will be added later by buffering Recorder.onVoice() PCM. The protocol and AudioStreamer already support stream_type=0; we just don't wire it yet.
- **WiFi hotspot auto-connect** (#6) — user manually connects to VoxSwap WiFi for now
- **WiFi lock** (#7.3) — can add later if WiFi sleeps during use
- **Box-side receiver** — just stubs, will be implemented when we work on box software
- **Multi-phone coordination** — protocol supports it (speaker_id 0-2), but we test with 1 phone first
- **Opus/codec encoding** — raw PCM is fine for local WiFi
- **Admin target language setting** — first phone sets targets via REGISTER. Currently targets come from phone's local settings. Box will enforce global targets when implemented.
