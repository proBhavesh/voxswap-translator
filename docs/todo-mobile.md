# Mobile App (app-v2) — TODO

## What's Done
- UI conversion complete (indigo theme, home screen, language select, settings, download screen)
- Dead code cleanup (Bluetooth, conversation mode, text translation all removed)
- Local translation pipeline: Recorder (amplitude VAD) → Whisper STT → NLLB-200 translate → Android system TTS
- 3-language model (source, target1, target2) wired into WalkieTalkieService
- TTS PCM capture via synthesizeToFile → WAV read → AudioTrack playback queue
- Network client: BoxConnection (TCP registration, heartbeat, reconnect with exponential backoff), AudioStreamer (UDP packetization, resampling via PcmResampler)
- Networking wired into pipeline: onPcmGenerated() streams translated TTS audio to box, connection lifecycle in WalkieTalkieService
- Connection status in home screen UI: status dot (gray=idle, indigo=connected, green=recording) + label
- Build compiles and runs

## What's Remaining

### 1. ~~Replace Android TTS with Kokoro/Piper TTS~~ — Revised approach
~~Originally planned to replace Android TTS with sherpa-onnx Kokoro TTS.~~
Kokoro only supports 8 languages, not enough for our use case. Instead, we kept Android system TTS and added `synthesizeToFile()` to capture PCM output. This is done.

- [x] 1.5 Capture TTS PCM output via synthesizeToFile() → WAV → PcmAudioData
- [x] 1.6 Play PCM through phone speaker via AudioTrack (sequential playback queue)
- [x] 1.7 Hold PCM buffers for streaming to box (onPcmGenerated hook)

### 2. Capture Original Audio as PCM — Deferred
Original audio streaming (stream_type=0) is deferred. The protocol and AudioStreamer already support it — just needs wiring of Recorder.onVoice() PCM when we're ready.

- [ ] 2.1 In `WalkieTalkieService`, buffer the original PCM audio from Recorder's `onVoice()` callback
- [ ] 2.2 Align original audio timing with translation output (both need to be sent per-utterance)

### 3. Network Client — Done
- [x] 3.1 Create `BoxConnection` class — TCP connection to `10.0.0.1:7700`
- [x] 3.2 TCP registration: REGISTER (speaker name, source language) → REGISTER_ACK (speaker_id, target languages)
- [x] 3.3 TCP heartbeat (every 2s)
- [x] 3.4 TCP disconnect/reconnect with exponential backoff (1s → 2s → 4s → 8s → 10s cap)
- [x] 3.5 Create `AudioStreamer` class — UDP to `10.0.0.1:7701`
- [x] 3.6 UDP packet format: `speaker_id (1B) | stream_type (1B) | sequence (4B BE) | timestamp (4B BE) | PCM (640B LE)`
- [x] 3.7 Create `PcmResampler` — resamples TTS output (22050/24000 Hz) to 16kHz via linear interpolation
- [x] 3.8 Packetize into 20ms chunks (320 samples × 2 bytes = 640 bytes per packet at 16kHz mono)

### 4. Wire Networking into Translation Pipeline — Mostly done
- [ ] 4.1 Stream original audio PCM (type=0) — deferred, see #2
- [x] 4.2 After TTS for target1 → stream translated PCM (type=1)
- [x] 4.3 After TTS for target2 → stream translated PCM (type=2)
- [x] 4.4 Graceful degradation: skip streaming when box not connected, local playback unaffected

### 5. Connection Status in UI — Partially done
- [x] 5.1 Home screen status dot: gray=idle, indigo=connected to box, green=recording
- [x] 5.2 Callback plumbing: ON_BOX_CONNECTED / ON_BOX_DISCONNECTED through service → fragment
- [ ] 5.3 Settings screen: show live connection status (layout has connectionLabel/connectionDot/connectionDetail views, currently hardcoded)

### 6. WiFi Hotspot Connection — Deferred (manual connect)
User manually connects to "VoxSwap-XXXX" WiFi. BoxConnection's reconnect loop handles the rest — keeps retrying until the box is reachable. No programmatic WiFi connection needed.

### 7. Foreground Service & Background Operation
- [x] 7.1 Foreground notification keeps service alive (already implemented in VoiceTranslationService)
- [x] 7.2 WakeLock for continuous audio capture (already in VoiceTranslationService, 10-min auto-renewing)
- [ ] 7.3 WiFi lock to prevent WiFi from sleeping

### 8. Testing & Polish
- [ ] 8.1 Create Python test receiver (simulate box: TCP REGISTER_ACK + UDP packet logger)
- [ ] 8.2 Test full pipeline: mic → STT → translate → TTS → UDP stream → box receives
- [ ] 8.3 Measure end-to-end latency (target: 2-3s from end of utterance)
- [ ] 8.4 Battery/thermal testing under continuous use
- [ ] 8.5 Test with multiple phones connected to same box

## Remaining Priority Order
1. **Settings screen connection status** (#5.3) — show live status in settings
2. **WiFi lock** (#7.3) — prevent WiFi sleep during use
3. **Test receiver + end-to-end testing** (#8) — verify everything works together
4. **Original audio capture** (#2) — stream_type=0, when needed
