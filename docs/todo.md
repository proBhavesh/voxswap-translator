# Mobile App TODO

## Phase 1 — UI Screens

- [ ] Language Select screen — pick source language + display target languages
- [ ] Settings screen — connection status, box IP, WiFi info, speaker name

## Phase 2 — Components

- [ ] Language picker component — scrollable list with search, selection state
- [ ] Connection status component — WiFi/box connection indicator with auto-refresh
- [ ] Audio level component — mic input level visualizer (animated bar/ring)

## Phase 3 — Audio Capture

- [ ] Install react-native-live-audio-stream
- [ ] Implement audio-capture service — mic recording, PCM 16-bit 16kHz mono streaming
- [ ] Implement use-audio-recorder hook — wraps service, connects to translation store
- [ ] Mic permissions handling (Android + iOS)

## Phase 4 — Networking

- [ ] Install react-native TCP/UDP socket library
- [ ] Implement network-client service — TCP connection, registration, heartbeat
- [ ] Implement stream-sender service — UDP audio packet sending
- [ ] Implement use-connection hook — wraps network client, connects to connection store
- [ ] Test with mock TCP/UDP server on laptop

## Phase 5 — Translation Engine

- [ ] Finalize model stack decision (Whisper + NLLB + Piper vs alternatives)
- [ ] Install onnxruntime-react-native
- [ ] Implement Silero VAD — speech detection, audio segmentation
- [ ] Implement Whisper STT — speech-to-text
- [ ] Implement NLLB translation — text-to-text translation
- [ ] Implement Piper TTS — text-to-speech for target languages
- [ ] Wire full pipeline: VAD -> STT -> Translate -> TTS
- [ ] Implement use-translation hook — wraps engine, connects to stores
- [ ] Model download + caching on first launch

## Phase 6 — Integration & Polish

- [ ] Wire audio capture -> translation engine -> stream sender end-to-end
- [ ] Live captions from STT output on home screen
- [ ] Audio level ring animation on mic button
- [ ] Battery/thermal monitoring + user warnings
- [ ] Background mode (Android foreground service, iOS audio background)
- [ ] Error handling + auto-reconnect
- [ ] Multi-speaker testing (up to 3 phones)
