# VoxSwap Translator

Real-time speech-to-speech translation system. Phone app translates speech locally via SeamlessM4T, sends translated audio over WiFi to an iMX8M Plus box that broadcasts via Auracast to unlimited listeners.

See @PROJECT.md for full architecture, hardware specs, and build phases.

## Project Structure

Two codebases in this monorepo:

- `app/` - Phone app (React Native, TypeScript) - captures voice, translates on-device, sends audio to box
- `box/` - Box software (C, Linux Yocto) - receives audio via WiFi, broadcasts via Auracast BLE

## Phone App (app/)

### Tech Stack
- Expo (React Native, TypeScript)
- expo-router (file-based routing)
- NativeWind (Tailwind CSS for styling)
- Zustand (state management)
- Silero VAD (ONNX, ~2MB) for voice activity detection
- onnxruntime-react-native (ML inference)
- expo-av / react-native-live-audio-stream (audio capture)
- UDP sockets (audio streaming), TCP (control/settings)
- EAS Build (cloud builds - no local android/ios folders)

### Commands
- Install: `cd app && npx expo install`
- Dev: `cd app && npx expo start`
- Build Android: `cd app && eas build --platform android`
- Build iOS: `cd app && eas build --platform ios`
- Lint: `cd app && npx eslint src/`
- Typecheck: `cd app && npx tsc --noEmit`

### Code Style (TypeScript)
- Files: `kebab-case.ts` / `kebab-case.tsx`
- Variables/functions: `camelCase`
- Components/Types/Interfaces: `PascalCase`
- Constants: `UPPER_SNAKE_CASE`
- Booleans: prefix with `is`, `has`, `should`, `can`
- Never use `any` - use `unknown` if type is truly unknown
- Use `import type` for type-only imports
- Prefer `interface` for objects, `type` for unions/primitives
- Prefer named exports over default exports
- One component per file
- Import order: external packages > absolute imports (`@/`) > relative imports

### App Architecture
- `src/app/` - Expo Router file-based routes
- `src/components/` - Reusable UI components
- `src/services/` - Business logic (audio capture, translation engine, networking)
- `src/hooks/` - Custom React hooks wrapping services
- `src/types/` - Shared TypeScript types
- `src/constants/` - Language lists, config defaults

### Key Patterns
- Services handle business logic, hooks expose them to components
- Components are presentational, hooks/services handle logic
- Audio is PCM 16-bit 16kHz mono
- VAD segments audio on pauses (>700ms) or max duration (15s)
- Translation pipeline: Mic → VAD → STT → Translate → TTS → Send to box
- Audio sent as raw PCM over UDP (port 7701), control over TCP (port 7700)
- Box always at 10.0.0.1 (hardcoded gateway IP)
- Models downloaded on first launch, not bundled with app
- Everything runs offline during operation

## Box Software (box/)

### Tech Stack
- C (C11 standard)
- BlueZ + Infineon CYW55573 SDK (Auracast broadcast)
- PipeWire (audio routing)
- hostapd (WiFi hotspot)
- CMake (build system)
- Yocto (Linux image)

### Commands
- Build: `cd box && mkdir -p build && cd build && cmake .. && make`
- Clean: `cd box/build && make clean`
- Yocto image: `cd box/yocto && ./build-image.sh`

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
- `src/wifi-hotspot.c` - WiFi AP management via hostapd
- `src/stream-receiver.c` - TCP/UDP server, receives audio from phones
- `src/audio-mixer.c` - Mixes multiple speaker streams per channel
- `src/auracast-broadcast.c` - LE Audio broadcast via BlueZ
- `src/settings-server.c` - Receives configuration from phone app
- `config/` - BlueZ and hostapd configuration files
- `yocto/meta-voxswap/` - Custom Yocto layer for image build

### Key Patterns
- Box hosts WiFi hotspot ("VoxSwap-XXXX", WPA2), phones connect to it
- Receives raw PCM audio from up to 3 phones via UDP (port 7701)
- TCP control server (port 7700) handles registration, heartbeat, settings
- Broadcasts 3 Auracast channels: original, lang 1, lang 2 (no caption channel)
- PipeWire handles mixing multiple speakers per channel natively
- Daemon: epoll event loop (network I/O) + PipeWire thread (audio routing)
- BlueZ experimental features must be enabled for LE Audio
- Minimal processing - audio passthrough to broadcast

## Hardware Context
- SoC: NXP i.MX8M Plus (Cortex-A53, 2.8 TOPS NPU)
- BT: Ezurio Sona IF573 (Infineon CYW55573, BT 6.0, Auracast capable)
- WiFi: Sona IF573 (Wi-Fi 6E)
- OS: Linux Yocto (kernel 6.4+, BlueZ 5.66+, PipeWire)

## Constraints
- Fully offline during operation (internet needed for initial model download)
- Max 3 simultaneous speakers
- 2 target translation languages per session (configured once, global)
- Latency target: 2-3 seconds (from end of utterance)
- Translation model: TBD (cascaded Whisper + NLLB + Piper is proven fallback)
- Phone does all heavy processing (VAD + STT + translate + TTS)
- Box is lightweight (receive + mix via PipeWire + broadcast)
