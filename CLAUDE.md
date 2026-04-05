# VoxSwap Translator

Real-time speech-to-speech translation system. Phone app translates speech locally using a cascaded pipeline (Whisper STT + NLLB-200 translation + Android TTS), sends translated audio over WiFi to a Raspberry Pi 4 that will broadcast via Auracast transmitters to unlimited listeners.

See @PROJECT.md for full architecture and build phases.

## Project Structure

Two codebases in this monorepo:

- `app/` - Phone app (native Android, Java) - captures voice, translates on-device, sends audio to box
- `box/` - Box software (C, Linux) - receives audio via WiFi, outputs to audio jack / Auracast transmitters

## Phone App (app/)

### Tech Stack
- Native Android (Java, minSdk 26, targetSdk 32)
- ONNX Runtime (Whisper STT + NLLB-200 translation inference)
- Android TextToSpeech (TTS — uses system Google TTS engine)
- AudioRecord (raw PCM 16kHz mono capture)
- Amplitude-based VAD (voice activity detection)
- TCP sockets (control) + UDP sockets (audio streaming)
- Gradle build system

### Commands
- Build: `cd app && ./gradlew assembleDebug`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Logs: `adb logcat -s performance` (pipeline timing)
- Force stop: `adb shell am force-stop nie.translator.vtranslator`

### Code Style (Java)
- Existing codebase forked from vTranslator — follow its patterns
- Package: `nie.translator.vtranslator`
- Debug config: `DebugConfig.java` (centralized flags like ONNX profiling)

### App Architecture
- `voice_translation/neural_networks/voice/Recognizer.java` - Whisper STT (6 ONNX sessions)
- `voice_translation/neural_networks/voice/Recorder.java` - Mic capture + amplitude VAD
- `voice_translation/neural_networks/translation/Translator.java` - NLLB-200 translation (4 ONNX sessions)
- `voice_translation/VoiceTranslationService.java` - Base service: TTS, playback, model init
- `voice_translation/_walkie_talkie_mode/_walkie_talkie/WalkieTalkieService.java` - Translation pipeline orchestration + networking
- `voice_translation/_walkie_talkie_mode/_walkie_talkie/WalkieTalkieFragment.java` - Home screen UI
- `voice_translation/networking/BoxConnection.java` - TCP control connection to box
- `voice_translation/networking/AudioStreamer.java` - UDP audio streaming to box
- `voice_translation/networking/WifiNetworkBinder.java` - Binds sockets to WiFi network
- `Global.java` - App-wide state, model initialization, language settings

### Key Patterns
- Audio is PCM 16-bit 16kHz mono
- VAD segments audio on pauses (>1.3s silence) or max duration (29s)
- Translation pipeline: Mic → VAD → Whisper STT → NLLB Translate → Android TTS → Send to box
- Pipeline is sequential per chunk: STT → translate target1 → translate target2 → TTS
- Audio sent as raw PCM over UDP (port 7701), control over TCP (port 7700)
- Models (~850MB total) downloaded on first launch from HuggingFace, not bundled with app
- **Model hosting: HuggingFace only** — all models hosted at `https://huggingface.co/bhavsh/voxswap-models`. Never use GitHub releases for model hosting (too slow). Upload new models via `hf upload` CLI.
- Everything runs offline during operation
- Debug/profiling flags in `DebugConfig.java`

### AI Models (loaded at startup, ~1.1GB RAM total)
- **Whisper Small** (~244MB): 6 ONNX sessions (initializer, encoder, cache init, cache init batch, decoder, detokenizer)
- **NLLB-200 Distilled 600M** (~600MB): 4 ONNX sessions (encoder, decoder, cache init, embed+lm_head) + SentencePiece tokenizer
- **Android TTS**: System Google TTS engine (~50MB, system-managed)
- All inference runs on CPU via ONNX Runtime, no GPU/NPU acceleration

## Box Software (box/)

### Tech Stack
- C (C11 standard)
- ALSA (audio output to 3.5mm jack)
- CMake (build system)
- Runs on Raspberry Pi OS (Debian)

### Commands
- Build locally: `cd box && mkdir -p build && cd build && cmake .. && make`
- Deploy to Pi: `scp box/src/*.c box/src/*.h pi@voxswap.local:~/voxswap-box/src/`
- Build on Pi: `ssh pi@voxswap.local "cd ~/voxswap-box/build && cmake .. && make"`
- Run on Pi: `ssh pi@voxswap.local "cd ~/voxswap-box/build && ./voxswap-box --no-hotspot"`
- Clean: `cd box/build && make clean`

### Code Style (C)
- Files: `kebab-case.c` / `kebab-case.h`
- Functions: `snake_case`
- Types/Structs: `PascalCase`
- Constants/Macros: `UPPER_SNAKE_CASE`
- Local variables: `snake_case`
- Include guards: `#ifndef VOXSWAP_FILENAME_H`
- Block comments only (`/* */`)
- Early returns for error handling
- Every function that can fail returns an error code
- All resources freed in cleanup path (goto cleanup pattern)

### Box Architecture
- `src/main.c` - Entry point, initializes all components
- `src/wifi-hotspot.c` - WiFi AP management (for production hotspot mode)
- `src/stream-receiver.c` - TCP/UDP server, receives audio from phones
- `src/audio-mixer.c` - Mixes multiple speaker streams per channel
- `src/auracast-broadcast.c` - LE Audio broadcast (stub — needs Auracast transmitters)
- `src/settings-server.c` - Settings storage, admin management, language config

### Key Patterns
- In dev: Pi connects to home WiFi, app connects to Pi via mDNS (`voxswap.local`)
- In production: Pi hosts WiFi hotspot ("VoxSwap-XXXX", WPA2), phones connect to it
- Receives raw PCM audio from up to 3 phones via UDP (port 7701)
- TCP control server (port 7700) handles registration, heartbeat, settings
- First phone to connect is admin and sets target languages via REGISTER protocol
- Target languages are optional at startup — phone provides them
- Daemon: epoll event loop for network I/O
- Minimal processing — audio passthrough to output

## Board Access (Raspberry Pi 4)
- SSH: `ssh pi@voxswap.local` (key auth, password: bhavesh9950)
- Hostname: `voxswap`
- OS: Raspberry Pi OS (Debian Trixie), Linux 6.12, aarch64
- Has: gcc, cmake, avahi-daemon (mDNS)
- WiFi: connected to home network (IP may change, use mDNS hostname)
- Box binary: `~/voxswap-box/build/voxswap-box`
- Start box: `cd ~/voxswap-box/build && ./voxswap-box --no-hotspot`
- Deploy updated source: `scp box/src/*.c box/src/*.h pi@voxswap.local:~/voxswap-box/src/`

## Hardware
- **Dev board**: Raspberry Pi 4 Model B (4GB RAM, BCM2711, quad Cortex-A72)
- **Production board (planned)**: CompuLab i.MX8M Plus with Sona IF573 (Auracast capable)
- **Auracast**: Will use USB Auracast transmitter dongles (3x ~$59 each), one per channel
- **Phone**: Any Android phone with 6GB+ RAM for running Whisper + NLLB models

## User Context
- First time with hardware boards — explain embedded/hardware concepts in plain language as they come up
- Still write production-quality code — only simplify explanations, not the code itself

## Constraints
- Fully offline during operation (internet needed for initial model download)
- Max 3 simultaneous speakers
- 2 target translation languages per session (first phone sets them)
- Latency target: 2-3 seconds (from end of utterance)
- Translation pipeline: Whisper Small (STT) → NLLB-200 Distilled 600M (translate) → Android TTS
- Phone does all heavy processing (VAD + STT + translate + TTS)
- Box is lightweight (receive + mix + output/broadcast)


## Subagents
Do not spawn subagents (Agent tool) unless explicitly asked by the user or invoked by a skill/plugin.
For code exploration, file searches, and codebase questions, use Glob, Grep, and Read directly —
these are faster and maintain conversation context. Subagents lack conversation context and
often provide less accurate results than working directly.
