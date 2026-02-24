# VoxSwap Translator

## What We're Building

A real-time speech-to-speech translation system. Speakers talk into their phones, the phone translates locally, and sends translated audio to a central device that broadcasts it to listeners via Auracast.

## Architecture

```
Speaker Phones (up to 3)              iMX8M Plus Box
┌──────────────────────┐              ┌──────────────────────┐
│  Phone App           │              │  Receiver            │
│  (Android / iOS)     │              │                      │
│                      │              │  Receive audio       │
│  Mic → VAD → STT     │              │  via WiFi            │
│  → Translate → TTS   │   WiFi       │                      │
│                      │ ──────────►  │  Broadcast via       │
│  Outputs:            │  (raw PCM    │  Auracast:           │
│  - Original audio    │   over UDP)  │  - Ch 1: Original    │
│  - Language 1 audio  │              │  - Ch 2: Language 1  │
│  - Language 2 audio  │              │  - Ch 3: Language 2  │
│                      │              │                      │
└──────────────────────┘              └──────────────────────┘
                                               │
                                          Auracast BLE
                                               │
                                      ┌────────┼────────┐
                                      ▼        ▼        ▼
                                     🎧       🎧       🎧
                                  Listeners (unlimited, no app needed)
```

## Components

### 1. Phone App (React Native - Android + iOS)
- Captures speaker's voice
- VAD (Silero) detects speech, segments audio on pauses
- Runs translation pipeline on-device (STT → Translate → TTS)
- Outputs translated audio for 2 target languages
- Sends 3 raw PCM streams (original + 2 translations) to box over UDP
- Displays live captions on screen (from STT output)
- Also serves as settings/control panel for the box

### 2. iMX8M Plus Box (Linux Yocto)
- Hosts a WiFi hotspot - phones connect to it
- Receives audio streams from phones via UDP
- Broadcasts 3 Auracast channels (original, lang 1, lang 2)
- PipeWire handles mixing multiple speakers per channel
- No screen, no keyboard - controlled from phone app
- Minimal processing - audio passthrough to broadcast

## Hardware

| Component | Spec |
|-----------|------|
| SoC | NXP i.MX8M Plus |
| WiFi/BT (current) | Intel AX210 (WiFi 6E, BT 5.2 — no Auracast) |
| WiFi/BT (target) | Ezurio Sona IF573 (Infineon CYW55573, BT 6.0, Auracast capable) |
| OS | Linux Yocto |

**Sona IF573 status:** PCIe link fails on CompuLab board — needs full Yocto rebuild with Ezurio's meta-summit-radio layer. See `docs/findings.md` for details. Using Intel AX210 for now to develop everything except Auracast. Auracast broadcast module will be integrated when Sona is working.

## Translation Model - TBD

Model stack is not finalized yet. Key finding:

**SeamlessM4T v2 cannot run on mobile.** The only ONNX conversion attempt (Fast-SeamlessM4T-ONNX) was
archived incomplete - missing decoder, vocoder, and beam search. No one has successfully exported
SeamlessM4T for mobile inference.

### Options Under Consideration

| Option | Type | Mobile Ready? | License |
|--------|------|---------------|---------|
| Whisper + NLLB + Piper TTS | Cascaded (3 models) | ✅ All have ONNX exports | MIT / CC-BY-NC / MIT |
| SeamlessM4T on box (PyTorch) | End-to-end | ✅ Box only (Linux) | CC-BY-NC 4.0 |
| Custom ONNX conversion | End-to-end | ⚠️ Months of work | CC-BY-NC 4.0 |
| Other models TBD | TBD | TBD | TBD |

### Cascaded Fallback (Proven)
- STT: Whisper / Distil-Whisper (ONNX, working on mobile)
- Translation: NLLB-200 Distilled (ONNX, working on mobile)
- TTS: Piper (ONNX, designed for edge devices)

This is the same stack rTranslator uses. Proven but higher latency (3-5s vs 2-3s).

**Decision pending - will finalize before development starts.**

## Tech Stack

### Phone App

| Layer | Technology | Language |
|-------|------------|----------|
| Framework | Expo (React Native) | TypeScript |
| Routing | expo-router | TypeScript |
| Styling | NativeWind (Tailwind CSS) | TypeScript |
| VAD | Silero VAD (ONNX, ~2MB) | TypeScript |
| ML Inference | onnxruntime-react-native | TypeScript |
| Audio Capture | expo-av / react-native-live-audio-stream | TypeScript |
| State Management | Zustand | TypeScript |
| Networking | UDP sockets (audio), TCP (settings) | TypeScript |
| Builds | EAS Build (cloud) | - |

### Box Software

| Layer | Technology | Language |
|-------|------------|----------|
| Application | Custom daemon | C |
| Bluetooth | BlueZ + Infineon CYW55573 SDK | C |
| Audio Routing | PipeWire | C |
| WiFi Hotspot | hostapd | Config (shell) |
| Build System | Yocto / CMake | Bitbake / CMake |

## Communication

| Link | Protocol | Purpose |
|------|----------|---------|
| Phone → Box | Raw PCM over UDP (WiFi hotspot) | Send translated audio streams |
| Phone → Box | TCP (WiFi hotspot) | Settings, configuration, control |
| Box → Earbuds | Auracast (BLE Audio, LC3 codec) | Broadcast to listeners |

### Audio Streaming Protocol

Raw PCM over UDP. No codec encoding/decoding between phone and box — avoids unnecessary latency.

```
Phone: TTS output (PCM) → UDP → Box: receive PCM → mix → LC3 encode → Auracast
```

- Format: 16-bit PCM, 16kHz, mono
- Transport: UDP with simple header (sequence number + timestamp + speaker ID)
- Bandwidth: ~256 kbps per stream, ~2.3 Mbps total for 9 streams — trivial for local WiFi 6E
- Packet loss: Negligible on local hotspot with 3 devices. Dropped packet = tiny audio gap (acceptable)

Why not Opus: Adding encode on phone + decode on box adds latency for no real benefit on a local network.
Why UDP over TCP: For real-time audio, a dropped packet is better than TCP's retransmission stall.

### Network Discovery

Hardcoded gateway IP. The box is the hotspot, so its IP is always known. Box runs at `10.0.0.1`,
phones send to that IP on fixed ports. No mDNS or broadcast discovery needed.

### WiFi Security

WPA2 with a default password (printed on box sticker). Changeable via phone app settings.
Protects against unauthorized connections and audio sniffing.

## Directory Structure

```
voxswap/translator/
│
├── PROJECT.md
│
├── app/                                  # Phone App (Expo)
│   ├── package.json
│   ├── tsconfig.json
│   ├── app.json                             # Expo config (permissions, build settings)
│   ├── eas.json                             # EAS Build profiles
│   │
│   ├── src/
│   │   ├── app/                             # Expo Router (file-based routing)
│   │   │   ├── _layout.tsx                      # Root layout
│   │   │   ├── index.tsx                        # Home - start/stop translation
│   │   │   ├── language-select.tsx              # Pick source + target languages
│   │   │   └── settings.tsx                     # Box config, connection status
│   │   │
│   │   ├── components/
│   │   │   ├── connection-status.tsx            # WiFi connection indicator
│   │   │   ├── audio-level.tsx                  # Mic input level visualizer
│   │   │   └── language-picker.tsx              # Language dropdown
│   │   │
│   │   ├── services/
│   │   │   ├── audio-capture.ts                # Mic recording, PCM streaming
│   │   │   ├── translation-engine.ts           # ONNX Runtime + SeamlessM4T
│   │   │   ├── network-client.ts               # WiFi connection to box
│   │   │   └── stream-sender.ts                # Send audio streams over TCP/UDP
│   │   │
│   │   ├── hooks/
│   │   │   ├── use-audio-recorder.ts           # Audio capture hook
│   │   │   ├── use-translation.ts              # Translation pipeline hook
│   │   │   └── use-connection.ts               # Box connection hook
│   │   │
│   │   ├── types/
│   │   │   └── index.ts                        # Shared types
│   │   │
│   │   └── constants/
│   │       └── index.ts                        # Languages, config defaults
│   │
│   └── assets/
│       └── models/                             # ONNX model files
│
├── box/                                  # Box Software (C - Linux Yocto)
│   ├── CMakeLists.txt
│   │
│   ├── src/
│   │   ├── main.c                            # Entry point, init all components
│   │   ├── wifi-hotspot.c                    # Create and manage WiFi AP
│   │   ├── wifi-hotspot.h
│   │   ├── stream-receiver.c                 # TCP/UDP server, receive audio
│   │   ├── stream-receiver.h
│   │   ├── audio-mixer.c                     # Mix multiple speaker streams
│   │   ├── audio-mixer.h
│   │   ├── auracast-broadcast.c              # LE Audio broadcast via BlueZ
│   │   ├── auracast-broadcast.h
│   │   ├── settings-server.c                 # Receive config from phone app
│   │   └── settings-server.h
│   │
│   ├── config/
│   │   ├── bluetooth.conf                    # BlueZ config
│   │   └── hostapd.conf                      # WiFi hotspot config
│   │
│   └── yocto/
│       ├── meta-voxswap/                     # Custom Yocto layer
│       │   ├── recipes-core/                 # Custom image recipe
│       │   ├── recipes-connectivity/         # BT + WiFi configs
│       │   └── recipes-app/                  # Box application recipe
│       └── build-image.sh                    # Build script
│
└── docs/
    └── chat-till-now.txt
```

## Engineering Decisions

### onnxruntime-react-native + New Architecture
`onnxruntime-react-native` uses the old `NativeModules` bridge (`ReactContextBaseJavaModule`) and has no `react-native.config.js` for autolinking. With New Architecture enabled (required by `react-native-reanimated` v4+), `NativeModules.Onnxruntime` is null at runtime. Fix: manually register `OnnxruntimePackage()` in `MainApplication.kt`'s `getPackages()`. Also lazy-import the module in JS (`require()` inside functions, not top-level `import`) to prevent startup crashes.

### Audio Chunking & VAD
Silero VAD (ONNX, ~2MB) detects speech vs silence. Models only run when someone is speaking —
critical for battery life and CPU usage.

Hybrid segmentation: buffer audio while speaking, segment when:
- Speaker pauses (>700ms silence) — natural sentence boundary, best translation quality
- Max duration hit (15 seconds) — safety cap for non-stop speakers

Latency breakdown (from end of utterance):
- Whisper STT: ~500ms-1s
- NLLB translate: ~200-500ms
- Piper TTS: ~200-500ms
- Network: ~5-10ms
- **Total after pause: ~1.5-2.5s** (within 2-3s target)

Listeners hear sentence-by-sentence translation with natural gaps, not continuous real-time audio.

### Stream Protocol
TCP port 7700 for control. UDP port 7701 for audio.

**Registration (TCP):**
Phone connects to WiFi → opens TCP to 10.0.0.1:7700 → sends REGISTER (speaker name, source language)
→ box responds with speaker_id and target languages. First phone becomes admin and sets target languages.

**Audio packets (UDP):** 10-byte header + PCM payload.
```
| speaker_id (1B) | stream_type (1B) | sequence (4B) | timestamp (4B) | PCM payload (640B) |
```
stream_type: 0 = original, 1 = lang1, 2 = lang2. Packet = 20ms of audio (650 bytes total).
Rate: 50 packets/sec per stream, 450 packets/sec total for 3 phones x 3 streams.

**Heartbeat:** TCP heartbeat every 2 seconds. 3 missed (6 seconds) = speaker disconnected.

### Captions
No Auracast caption channel — Auracast broadcasts audio, not text. 3 channels only (original, lang 1, lang 2).

Captions delivered via screen instead:
- Speaker's phone displays live captions from Whisper STT output
- Optional: companion tablet/laptop on box WiFi shows captions via web interface served by box

### Multi-Speaker Mixing
PipeWire handles mixing natively. Daemon creates one PipeWire source node per speaker per channel.
When multiple speakers talk simultaneously, PipeWire mixes their streams together per channel
(same as hearing two people talk at once in a room). Per-speaker volume normalization before mixing.

### User Flow
1. Box powers on → Linux boots → daemon starts WiFi hotspot ("VoxSwap-XXXX") → LED indicates ready
2. Admin opens app → app connects to "VoxSwap-XXXX" WiFi (programmatic via WiFi Suggestion API / NEHotspotConfiguration, fallback: QR code sticker on box)
3. App auto-connects to box at 10.0.0.1 → admin sets source language + 2 target languages
4. Admin presses Start → mic activates, translation running
5. Additional speakers: connect to same WiFi → app gets target languages from box → pick source language → Start
6. Listeners: Auracast earbuds scan → see "VoxSwap" → pick channel (Original / French / German) → audio plays

### Phone Battery & Thermal
Running 3 ML models continuously is heavy. Estimated 2-3 hours on battery.

Mitigations:
- VAD-gated processing — models idle during silence (biggest power saver)
- Quantized models (INT8) — 2-4x faster, less power
- Screen off support — foreground service (Android), audio background mode (iOS)
- Battery/thermal monitoring in app — warn user, show estimated remaining time
- Recommend plugging in for conference use (speakers are typically at a table)

### Language Selection
Configured once per session. First phone to connect sets the 2 target languages for all speakers.
Each speaker can set their own source language (or auto-detect). Target languages are global —
Channel 2 is always Language 1, Channel 3 is always Language 2, regardless of who's speaking.

### Model Delivery
Download on first launch. App ships small (<100MB). Core models (Whisper ~75MB, NLLB ~600MB)
download on first run. Piper voice packs (~60MB each) download per target language on demand.
"Fully offline" applies to operation, not initial setup.

### Error Handling
Graceful degradation with auto-reconnect. Each speaker is independent — one failure doesn't affect others.

| Scenario | Behavior |
|----------|----------|
| WiFi drops | Phone auto-reconnects. Box plays silence for that speaker during gap. |
| Speaker disconnects | Box detects timeout (3s no data). Removes from mix. Others continue. |
| Phone backgrounded | Android: foreground service. iOS: audio background mode. |
| Translation fails | Skip failed chunk, continue with next segment. |
| Phone call interrupts | Pause translation, resume when call ends. |

### State Management (Phone App)
Zustand — lightweight (~1KB), no providers/boilerplate, split into stores:
`connectionStore`, `translationStore`, `settingsStore`. Persist middleware for saved settings.

### Testing Strategy (Box - before hardware arrives)
1. **Unit tests on dev machine** — audio mixer, protocol parsing, settings. Mock hardware interfaces.
2. **Integration with mock audio** — PipeWire on regular Linux, replace Auracast with local audio sink.
3. **Network protocol tests** — run stream receiver on any Linux box, send audio from phone/test script.
4. **Hardware-specific (deferred)** — Auracast broadcast only testable on actual device. Code abstracted behind clean interfaces.

## Key Constraints

- Fully offline during operation (internet needed for initial model download)
- Max 3 simultaneous speakers
- 2 target translation languages per session (configured once, global)
- Latency target: 2-3 seconds
- Listeners only need Auracast-compatible earbuds
- Works anywhere - no external router needed

## Project Split

| Part | Platform | Developer |
|------|----------|-----------|
| Phone app | React Native (Android + iOS) | Start now, hand off later |
| Box receiver + Auracast | Linux Yocto (C) | Primary focus after device arrives |

## Build Order

### Phase 1 - Phone App (before device arrives)
1. Audio capture module
2. SeamlessM4T ONNX integration
3. Network client (test with local server)
4. Settings UI
5. Test on Android phone

### Phase 2 - Box Software (when device arrives)
6. Yocto image setup for iMX8M Plus
7. WiFi hotspot
8. Stream receiver
9. Audio mixer
10. Auracast broadcast
11. End-to-end testing

### Phase 3 - Polish
12. Latency optimization
13. iOS testing
14. Multi-speaker testing

## Timeline

- 2 weeks development
- 1 week testing and performance tuning
