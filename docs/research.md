# VoxSwap Translator — Codebase Research Report

## What VoxSwap Is

A real-time speech-to-speech translation system for live events. Up to 3 speakers talk into their phones, the phones translate speech on-device, and send translated audio over WiFi to a central box (iMX8M Plus) that broadcasts it via Auracast BLE to unlimited listeners wearing standard Auracast earbuds. No internet needed during operation. Listeners don't need any app — just Auracast-compatible earbuds.

The system broadcasts 3 audio channels: original language, translation language 1, and translation language 2. Target languages are set once per session by the first phone (admin) to connect.

## Monorepo Structure

Two codebases, one repo:

```
voxswap-translator/
├── app/          Phone app (React Native / TypeScript / Expo)
├── box/          Box software (C / Linux Yocto)
├── docs/         Hardware findings, integration plans, images, datasheets, client comms
├── CLAUDE.md     AI assistant instructions
└── PROJECT.md    Full architecture document
```

---

## Phone App (`app/`)

### Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Framework | Expo | 54.0.33 |
| Language | TypeScript | 5.9.2 |
| Routing | expo-router | 6.0.23 |
| Styling | NativeWind (Tailwind via React Native) | 4.2.2 |
| CSS Engine | Tailwind CSS | 3.4.19 (v3, not v4) |
| State | Zustand | 5.0.11 |
| React | React | 19.1.0 |
| React Native | React Native | 0.81.5 |
| Animations | react-native-reanimated | 4.1.1 |
| Icons | @expo/vector-icons (Ionicons) | 15.0.3 |

New Arch is enabled (`newArchEnabled: true` in app.json). Builds use EAS (cloud) — no local android/ios folders.

### NativeWind Setup (Critical — non-obvious)

NativeWind bridges Tailwind CSS to React Native styles. The setup has 3 parts that must all be present:

1. **metro.config.js** — wraps default config with `withNativeWind`, pointing to `./src/global.css`
2. **babel.config.js** — uses `babel-preset-expo` with `jsxImportSource: 'nativewind'` and `nativewind/babel` preset
3. **tailwind.config.js** — standard Tailwind v3 config with `nativewind/preset` and custom theme colors

Additionally requires `react-native-css-interop` (0.2.2) and a type reference file `nativewind-env.d.ts`.

The `global.css` is minimal: just `@tailwind base/components/utilities` (Tailwind v3 syntax, NOT v4's `@import 'tailwindcss'`).

### File Architecture

```
src/
├── app/                    Expo Router file-based routes
│   ├── _layout.tsx            Root layout (Stack navigator, StatusBar)
│   ├── index.tsx              Home screen (fully built)
│   ├── language-select.tsx    Placeholder stub
│   └── settings.tsx           Placeholder stub
│
├── components/
│   ├── ui/                    Design system components (built)
│   │   ├── button.tsx            Variant/size button with loading state
│   │   ├── icon.tsx              Type-safe Ionicons wrapper
│   │   ├── safe-screen.tsx       SafeAreaView + padding wrapper
│   │   └── index.ts             Barrel export
│   │
│   ├── audio-level.tsx        TODO stub
│   ├── connection-status.tsx  TODO stub
│   └── language-picker.tsx    TODO stub
│
├── constants/
│   ├── colors.ts              Light theme palette (brand, text, bg, status, gray, border)
│   ├── spacing.ts             Spacing scale, radius, shadows, hit slop
│   ├── typography.ts          12 text style presets (system fonts)
│   ├── icons.ts               Icon sizes, colors (referencing COLORS), named icon groups
│   └── index.ts               Barrel export + app constants (network, audio, languages)
│
├── services/                  Business logic (all interface-only, no implementation)
│   ├── audio-capture.ts          Mic recording + PCM streaming
│   ├── translation-engine.ts     VAD -> STT -> Translate -> TTS pipeline
│   ├── network-client.ts         TCP control connection to box
│   └── stream-sender.ts          UDP audio packet sending
│
├── hooks/                     React hooks wrapping services (all single-line TODOs)
│   ├── use-audio-recorder.ts
│   ├── use-connection.ts
│   └── use-translation.ts
│
├── stores/                    Zustand state management (fully built)
│   ├── connection-store.ts       status, speaker, session + actions
│   └── translation-store.ts     status, sourceLanguage, isRecording, audioLevel, lastCaption + actions
│
├── types/
│   └── index.ts               Language, ConnectionStatus, TranslationStatus, SpeakerRegistration,
│                              SessionConfig, StreamType, AudioPacketHeader
│
└── global.css                 Tailwind v3 directives
```

### What's Built vs What's Stub

**Fully built:**
- Design system (colors, spacing, typography, icons) — all constants files complete
- UI components (Button, Icon, SafeScreen) — production quality
- Home screen — connection indicator, language selector, mic button with audio level ring, caption area, navigation
- Zustand stores — connection + translation state with all actions
- Types — all shared types defined
- Service interfaces — typed, with configs and factory functions, but empty bodies
- Tailwind + NativeWind setup — fully working

**Stub / TODO:**
- All 4 services (audio-capture, translation-engine, network-client, stream-sender) — have types and structure but `/* TODO */` implementations
- All 3 hooks (use-audio-recorder, use-connection, use-translation) — single-line TODOs
- 3 components (audio-level, connection-status, language-picker) — single-line TODOs
- language-select screen — placeholder text
- settings screen — placeholder text

### Design System Details

**Colors (light theme):** White backgrounds (#FFFFFF, #F9FAFB), dark text (#111827), indigo brand (#4F46E5), standard status colors. Gray scale from 50-900 matching Tailwind defaults.

**Tailwind config** extends with custom colors mapped as `brand.*`, `text.*`, `bg.*`, `status.*`, `border.*`. These map to NativeWind utility classes (`bg-brand-primary`, `text-text-secondary`, etc.).

**Button component:** 3 variants (primary/secondary/outline), 3 sizes (sm/md/lg), loading spinner, disabled state, `active:opacity-85` for press feedback. Uses NativeWind classes exclusively — no `style` function prop (this was a bug fix — function-style `style` props don't work reliably with NativeWind).

**Icon component:** Wraps Ionicons with design system size/color keys. Accepts both named keys (`size="lg"`, `color="brand"`) and raw values (`size={32}`, `color="#FF0000"`). Resolves named keys from `ICON_SIZES` and `ICON_COLORS` constants.

### Home Screen (`index.tsx`)

The main screen has 5 sections:
1. **Header** — connection status dot (color-coded) + label + settings gear icon
2. **Language selector** — pressable card showing source language → target languages, navigates to `/language-select`
3. **Mic button** — large 120px circular button centered on screen, indigo (idle) / red (recording), surrounded by 160px outer ring with audio-level-responsive opacity
4. **Caption area** — text display for STT output, placeholder text when empty
5. **Bottom** — "Change Languages" outline button

State is read from Zustand stores: `connectionStore` for status/session, `translationStore` for sourceLanguage/isRecording/lastCaption/audioLevel.

### Routing

expo-router with file-based routing. Root at `src/app/`. Three routes:
- `/` — home (index.tsx)
- `/language-select` — language selection
- `/settings` — box settings

Root layout wraps in Stack navigator with hidden headers and white background. StatusBar set to "dark" (dark icons on light bg).

### App Config (app.json)

- Portrait only orientation
- iOS: microphone permission, audio background mode
- Android: RECORD_AUDIO, WiFi state permissions, FOREGROUND_SERVICE + FOREGROUND_SERVICE_MICROPHONE
- Typed routes enabled (`experiments.typedRoutes`)
- Splash screen with dark bg (#1a1a2e) — note: this still uses the old dark theme color, should be updated to match light theme

### Constants

Network constants hardcoded:
- Box IP: 10.0.0.1 (the box IS the WiFi hotspot, so its IP is always known)
- TCP control port: 7700
- UDP audio port: 7701
- Audio: 16-bit PCM, 16kHz, mono, 20ms chunks (640 bytes per chunk)
- VAD: 700ms silence threshold, 15s max chunk duration
- Heartbeat: 2s interval, 6s timeout
- WiFi SSID prefix: "VoxSwap-"

12 supported languages defined (English, Spanish, French, German, Portuguese, Italian, Chinese, Japanese, Korean, Arabic, Hindi, Russian).

### Service Architecture (Interfaces Only)

All services use a factory function pattern (`createXxx(config)`) returning an object with methods. None are implemented.

**audio-capture:** `start()`, `stop()`, `isRecording()`. Config includes sampleRate/bitDepth/channels and an `onAudioData` callback.

**translation-engine:** `loadModels()`, `processAudio(pcmData)`, `unloadModels()`, `isReady()`. Config includes source/target languages, `onTranslation` and `onCaption` callbacks. `TranslationResult` contains originalAudio + two translatedAudio buffers + captionText.

**network-client:** `connect(speakerName, sourceLanguage)`, `disconnect()`, `setTargetLanguages(lang1, lang2)`, `isConnected()`. Config includes box IP/port and callbacks for connected/disconnected/sessionUpdate events. Manages heartbeat interval internally.

**stream-sender:** `send(streamType, pcmData)`, `resetSequence()`. Builds 10-byte UDP headers (speakerId 1B + streamType 1B + sequence 4B + timestamp 4B) + PCM payload.

### Missing Dependencies (Not Yet Installed)

These packages are needed but not in package.json:
- `react-native-live-audio-stream` or `expo-av` — mic recording
- `onnxruntime-react-native` — ML model inference
- TCP/UDP socket library (e.g., `react-native-tcp-socket`, `react-native-udp`)
- Silero VAD ONNX model file (~2MB)
- Whisper/NLLB/Piper ONNX model files (need to be downloaded at runtime)

---

## Box Software (`box/`)

### Overview

C11 daemon for Linux Yocto on iMX8M Plus. Receives translated audio from phones over WiFi and broadcasts via Auracast BLE. The box IS the WiFi hotspot — phones connect to it.

### Architecture

All code is stub/scaffolding. Each module has:
- Header (`.h`) with `init()` and `shutdown()` function declarations
- Source (`.c`) with empty implementations returning 0

**Modules:**
- `main.c` — signal handling (SIGINT/SIGTERM), commented-out init calls, empty epoll loop
- `wifi-hotspot.c` — will manage hostapd for WiFi AP
- `stream-receiver.c` — will run UDP server on port 7701 for audio reception
- `audio-mixer.c` — will use PipeWire to mix multiple speakers per channel
- `auracast-broadcast.c` — will use BlueZ LE Audio for Auracast broadcast
- `settings-server.c` — will run TCP server on port 7700 for registration/heartbeat/settings

### Build System

CMake 3.20+. Strict warnings (`-Wall -Wextra -Wpedantic -Werror`). Optionally links PipeWire and BlueZ if found via pkg-config, with compile-time `HAS_PIPEWIRE` / `HAS_BLUEZ` defines. Always links pthread.

Installs binary to `/bin` and config files to `/etc/voxswap`.

### Config Files

**hostapd.conf:** WiFi hotspot on wlan0, 5GHz channel 36, WPA2-PSK, SSID "VoxSwap-0001", password "voxswap123".

**bluetooth.conf:** BlueZ with `Experimental = true` (required for LE Audio/Auracast) and `ControllerMode = le`.

### Yocto Layer

`yocto/meta-voxswap/` has empty directory structure with `.gitkeep` files:
- `recipes-core/` — for custom image recipe
- `recipes-connectivity/` — for BT + WiFi configs
- `recipes-app/` — for box application recipe

`build-image.sh` is a placeholder that exits with error.

---

## Hardware

### Board: CompuLab UCM-iMX8M-Plus Evaluation Kit

| Property | Value |
|----------|-------|
| SoC | NXP i.MX8M Plus (Cortex-A53 quad-core @ 1.8GHz) |
| RAM | 4 GiB Samsung @ 3200 MHz |
| Storage | 29.1 GiB eMMC (HS400) |
| NPU | 2.8 TOPS (for future ML use) |
| Carrier Board | SBEV-UCMIMX8PLUS Rev 1.0 |
| Kernel | 6.6.23-compulab-4.0 |
| OS | NXP i.MX Release Distro 6.6-scarthgap (Yocto Linux 4.0) |
| Serial Console | USB serial on P3 at 115200/8N1 |

Two evaluation kits were received: iMX8M Plus (primary target) and iMX8M Mini (spare, no NPU).

### Wireless Modules

**Intel AX210NGW (currently installed):**
- WiFi 6E + BT 5.2
- PCIe for WiFi, USB for BT
- Works out of the box on the Yocto image
- Does NOT support Auracast
- BT not working — E4 jumper routes USB to J3 connector instead of M.2 slot

**Ezurio Sona IF573 (target module, P/N 453-00120):**
- Infineon CYW55573
- WiFi 6E (PCIe) + BT 6.0 (UART) with Auracast support
- Does NOT work on the board — PCIe link fails ("Phy link never came up")
- Needs full Yocto rebuild with Ezurio's `meta-summit-radio` layer

### Sona IF573 Integration Failure — Root Cause

The PCIe PHY link failure happens at the electrical level, before any driver/firmware loads. The Intel AX210 works in the same M.2 slot, proving the slot hardware is fine. Something specific to the CYW55573 prevents PCIe link training on this board.

**Attempted fixes (all failed):**
1. Manually copying firmware to `/lib/firmware/cypress/` — stock brcmfmac driver doesn't recognize CYW55573 PCIe device ID
2. Reducing PCIe link speed to Gen 1 in device tree — no effect
3. Enabling PCIe reference clock output in device tree — no effect

**Suspected remaining causes:**
1. W_DISABLE1# signal may be holding WiFi radio disabled
2. PCIe reset timing may need to be longer for CYW55573
3. Ezurio's backports driver may include PCIe subsystem patches for CYW55573 quirks

**Resolution:** Intel AX210 reinstalled. Development proceeds without Auracast. Sona integration deferred to when full Yocto rebuild is done with Ezurio's meta-layer.

### What's Needed for Sona (Significant Effort)

1. Add `meta-summit-radio` Yocto layer
2. Download firmware from Ezurio (requires account sign-in)
3. Kernel config changes (cfg80211 as module, disable built-in BT)
4. Add ~9 packages to IMAGE_INSTALL (backports driver, summit supplicant, firmware, patchram, SDMA modules)
5. Adapt device tree from NXP EVK to CompuLab board (different GPIO routing)
6. Set regulatory domain
7. Rebuild and flash

---

## Communication Protocol

### Control (TCP, port 7700)

Registration flow:
1. Phone connects to box WiFi hotspot
2. Opens TCP to 10.0.0.1:7700
3. Sends REGISTER with speaker name + source language
4. Box responds with speaker_id + target languages
5. First phone becomes admin, sets target languages for session

Heartbeat: every 2 seconds over TCP. 3 missed (6 seconds) = speaker disconnected.

### Audio (UDP, port 7701)

10-byte header + PCM payload per packet:
```
| speaker_id (1B) | stream_type (1B) | sequence (4B) | timestamp (4B) | PCM payload (640B) |
```

- stream_type: 0 = original, 1 = lang1, 2 = lang2
- Each packet = 20ms of audio (650 bytes total)
- Rate: 50 packets/sec per stream
- 3 phones × 3 streams = 450 packets/sec total (~2.3 Mbps)
- No codec — raw PCM to minimize latency

---

## Translation Pipeline (Not Yet Implemented)

Planned cascaded approach (model decision pending):

```
Mic → Silero VAD → Whisper STT → NLLB Translate → Piper TTS → UDP Send
```

**Silero VAD:** ONNX model (~2MB). Detects speech vs silence. Segments on pauses (>700ms) or max duration (15s). Gates all downstream processing — models idle during silence.

**Whisper STT:** Speech-to-text. ONNX, ~75MB. Produces caption text.

**NLLB-200:** Text-to-text translation. ONNX, ~600MB distilled.

**Piper TTS:** Text-to-speech. ONNX, ~60MB per voice pack (downloaded per target language on demand).

Estimated latency from end of utterance: 1.5-2.5 seconds (within 2-3s target).

Alternative considered: SeamlessM4T (end-to-end S2S) — but ONNX conversion for mobile is incomplete/archived. No one has successfully exported it for mobile inference.

---

## Client Context

Client: Gonzalo Chirinos (GCEX TRADING USA CORP, Miami FL).

Two parallel projects from same client on same hardware:
1. **Translation project** (this repo) — real-time translation broadcasting
2. **Companion project** — AI conversational agent (separate, on Linux Yocto)

Key client decisions:
- Max 3 speakers per session
- 2 target languages per session (global)
- Both Android and iOS (start with Android, iOS after MVP)
- No Android OS — Linux Yocto for both projects (lower overhead)
- WiFi for phone-to-box communication (not Bluetooth)
- Phone does all heavy processing, box is lightweight passthrough
- High quality TTS is critical ("avoid robotic voices")

Budget: $1,600 for translation project. 30% advance paid. Timeline: 2 weeks dev + 1 week testing.

Development kit shipped via UPS to India, cleared customs after duty payment (~$53).

---

## Key Observations

### Things That Work
- Expo app boots and renders on Android phone
- NativeWind styling works correctly (light theme, custom colors)
- File-based routing with expo-router
- Zustand stores connected to UI
- Board boots and runs with Intel AX210 WiFi

### Things That Need Attention
1. **app.json splash bg** still uses dark theme color `#1a1a2e` — should be updated to `#FFFFFF` or brand color to match light theme
2. **android adaptive icon bg** also uses `#1a1a2e`
3. **Button component** uses `active:opacity-85` — the decimal value may not be recognized by NativeWind (Tailwind only supports opacity values in steps of 5: 80, 85, 90, 95, 100 — `85` should work as it's a standard Tailwind value)
4. **No linting/typecheck CI** — only manual commands available
5. **Service factory functions** return plain objects, not classes — works fine for this use case but means no `this` context or lifecycle
6. **main.c has include guard** (`#ifndef VOXSWAP_MAIN_C`) — .c files shouldn't have include guards, only headers
7. **Package manager** is pnpm (based on pnpm-lock.yaml) but CLAUDE.md references `npx expo install` — should use `pnpm` consistently

### Architecture Risks
1. **Model stack undecided** — cascaded (Whisper+NLLB+Piper) is the fallback, but SeamlessM4T would be better. No ONNX path exists for SeamlessM4T on mobile.
2. **Sona IF573 blocked** — Auracast is the core differentiator but can't be tested until Yocto rebuild. Everything else can be tested with Intel AX210.
3. **Audio capture library not chosen** — expo-av vs react-native-live-audio-stream. The latter gives raw PCM access which is needed. expo-av may not expose raw PCM streaming.
4. **TCP/UDP library not chosen** — needs a React Native socket library for both TCP control and UDP audio streaming.
5. **3 ONNX models in RAM simultaneously** — Whisper (~75MB) + NLLB (~600MB) + Piper (~60MB) = ~735MB of model weights, plus inference working memory. Will need careful memory management on phones with limited RAM.
6. **No error boundaries** — app has no React error boundaries or crash reporting setup.
