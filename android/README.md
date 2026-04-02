# VoxSwap

Real-time speech-to-speech translation system. Speaker phones capture voice, translate locally using on-device AI models, and stream translated audio over WiFi to a Raspberry Pi box that outputs to speakers or Auracast transmitters.

## Architecture

```
Speaker Phones (up to 3)              Raspberry Pi Box
+-----------------------+              +----------------------+
|  Android App          |              |  Receiver            |
|                       |              |                      |
|  Mic -> VAD -> STT    |   WiFi       |  Receive audio       |
|  -> Translate -> TTS  | ---------->  |  via TCP/UDP         |
|                       | (raw PCM     |                      |
|  Sends translated     |  over UDP)   |  Output to:          |
|  audio to box         |              |  - 3.5mm audio jack  |
|                       |              |  - Auracast (future) |
+-----------------------+              +----------------------+
```

**Translation pipeline (runs entirely on-phone, offline):**

```
Microphone -> Silero VAD -> Whisper Base STT -> NLLB-200 Translation -> Piper TTS -> Audio out + UDP stream
```

## Repository Structure

```
.
+-- app/           # Android phone app (Java, Gradle)
+-- box/           # Raspberry Pi receiver software (C, CMake)
```

---

## Phone App (`app/`)

### Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17** (bundled with Android Studio)
- **Android SDK** with:
  - SDK Platform 33 (Android 13)
  - NDK (Side by side) - any recent version
  - CMake 3.22.1
- **Physical Android device** with 4GB+ RAM (arm64-v8a only, no emulator support)
  - Models run on CPU via ONNX Runtime, need real hardware for performance testing

### Setup

1. **Clone the repository:**
   ```bash
   git clone <repo-url>
   cd voxswap-translator
   ```

2. **Open in Android Studio:**
   - Open Android Studio
   - File -> Open -> select the `app/` directory
   - Wait for Gradle sync to complete (first sync downloads dependencies)

3. **Check SDK setup:**
   - If Android Studio complains about missing SDK, go to:
     - File -> Project Structure -> SDK Location
     - Set Android SDK path (typically `~/Android/Sdk` on Linux/Mac)
   - Install any missing SDK components via SDK Manager

4. **sherpa-onnx AAR:**
   - The pre-built `sherpa-onnx-static-1.12.29.aar` is in `app/libs/`
   - This is a static build that bundles ONNX Runtime internally to avoid conflicts with the separate `onnxruntime-android` dependency used by the translator
   - If `app/libs/` is missing, you'll need to obtain this AAR (see Build Dependencies below)

### Build

**From Android Studio:**
- Select `app` configuration in the run dropdown
- Click Run (or Shift+F10)
- Select your connected device

**From command line:**
```bash
cd app
./gradlew assembleDebug
```

The APK is output to:
```
app/app/build/outputs/apk/debug/app-debug.apk
```

### Install

```bash
adb install -r app/app/build/outputs/apk/debug/app-debug.apk
```

### First Launch

On first launch, the app downloads AI models (~1GB total). This requires an internet connection.

**Models downloaded automatically:**

| Model | Size | Purpose |
|-------|------|---------|
| NLLB-200 (4 ONNX files) | ~950 MB | Text translation (200 languages) |
| Whisper Base (encoder + decoder + tokens) | ~60 MB | Speech-to-text |
| English Piper voice | ~30 MB | Default TTS voice |

Additional Piper TTS voices (~30-60 MB each) are downloaded on-demand when you select a target language that doesn't have a voice yet. The app will prompt before downloading.

After models are downloaded, the app works **fully offline**.

### Usage

1. Launch the app
2. Tap "Change Languages" to select:
   - **Source language** - the language you speak
   - **Target language 1** - first translation output
   - **Target language 2** (optional) - second translation output
3. Tap the mic button to start
4. Speak naturally - the app will:
   - Detect speech via VAD (voice activity detection)
   - Transcribe with Whisper STT
   - Translate with NLLB-200
   - Speak the translation via Piper TTS
   - Stream audio to the box (if connected)

### Key Configuration

**Box connection:** The box IP is configured in `WalkieTalkieService.java`:
```java
private static final String BOX_HOST = "192.168.31.133";  // Change to your box IP
```
For production (box hosts its own hotspot), change to `10.0.0.1`.

**Debug/profiling:** See `DebugConfig.java` for ONNX profiling and optimization flags.

**Mic sensitivity and VAD settings:** Adjustable in the app's Settings screen.

### App Architecture

```
app/app/src/main/java/nie/translator/vtranslator/
|
+-- Global.java                    # App singleton, model init, language prefs
+-- DebugConfig.java               # Debug flags (ONNX profiling, optimization level)
+-- LoadingActivity.java           # Splash screen
+-- LanguageSelectActivity.java    # 3-picker language selection UI
|
+-- access/
|   +-- AccessActivity.java        # First-launch model download screen
|   +-- DownloadFragment.java      # Model download UI + URLs
|   +-- Downloader.java            # DownloadManager wrapper
|
+-- settings/
|   +-- SettingsActivity.java      # App settings (mic, audio devices, VAD)
|
+-- voice_translation/
|   +-- VoiceTranslationService.java    # Base service: TTS, playback, wake lock, Piper engines
|   +-- VoiceTranslationActivity.java   # Main activity container
|   +-- VoiceTranslationFragment.java   # Base fragment
|   |
|   +-- _walkie_talkie_mode/_walkie_talkie/
|   |   +-- WalkieTalkieService.java    # Pipeline orchestrator: VAD -> STT -> translate -> TTS -> stream
|   |   +-- WalkieTalkieFragment.java   # Home screen UI (mic button, captions, status)
|   |
|   +-- neural_networks/
|   |   +-- voice/
|   |   |   +-- Recognizer.java         # Whisper STT via sherpa-onnx
|   |   |   +-- Recorder.java           # Mic capture + Silero VAD
|   |   |   +-- SileroVad.java          # Silero VAD wrapper
|   |   +-- translation/
|   |       +-- Translator.java         # NLLB-200 via ONNX Runtime (4 sessions)
|   |       +-- Tokenizer.java          # SentencePiece tokenizer
|   |
|   +-- networking/
|       +-- BoxConnection.java          # TCP client (registration, heartbeat, reconnect)
|       +-- AudioStreamer.java          # UDP audio sender (650-byte packets)
|       +-- WifiNetworkBinder.java      # Binds sockets to WiFi (bypasses mobile data)
|       +-- PcmResampler.java           # Resamples TTS output to 16kHz
|
+-- tools/
    +-- PiperTtsEngine.java        # sherpa-onnx Piper VITS neural TTS wrapper
    +-- TTS.java                   # Android system TTS (fallback)
    +-- VoiceDownloadManager.java  # On-demand Piper voice downloads
    +-- AudioDeviceManager.java    # Audio input/output device selection
    +-- CustomLocale.java          # Language locale wrapper
    +-- nn/                        # ONNX tensor utilities
    +-- gui/                       # Custom UI components
```

### Build Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| ONNX Runtime Android | 1.19.0 | NLLB-200 translation inference |
| ONNX Runtime Extensions | 0.12.4 | Extended ops support |
| sherpa-onnx (static AAR) | 1.12.29 | Whisper STT + Piper TTS (bundles ORT internally) |
| Material Components | 1.9.0 | UI components |
| AndroidX SplashScreen | 1.0.1 | Splash screen |
| Guava | 32.1.3 | Tensor utilities (Floats/Ints/Longs) |

Native C++ (via CMake): SentencePiece tokenizer JNI bindings.

### Networking Protocol

**TCP (port 7700)** - Control:
- Phone sends REGISTER on connect (speaker name, source language, target languages)
- Box responds with REGISTER_ACK (assigned speaker_id 0-2)
- Heartbeat every 2 seconds, exponential backoff reconnect on disconnect

**UDP (port 7701)** - Audio streaming:
```
| speaker_id (1B) | stream_type (1B) | sequence (4B BE) | timestamp (4B BE) | PCM payload (640B LE) |
```
- 650 bytes per packet = 20ms of audio (16-bit PCM, 16kHz, mono)
- `stream_type`: 1 = target language 1, 2 = target language 2
- Paced at ~2x real-time to prevent WiFi packet drops

### Logs

```bash
# Pipeline timing (STT, translation, TTS durations)
adb logcat -s performance

# Translation input/output text
adb logcat -s translation

# Networking (box connection, audio streaming)
adb logcat -s VoxSwap

# Force stop
adb shell am force-stop nie.translator.vtranslator
```

---

## Box Software (`box/`)

Lightweight C daemon that receives audio from phones and outputs it. Runs on Raspberry Pi 4 (or any Linux board).

### Prerequisites

- **GCC** (C11 support)
- **CMake** 3.20+
- **ALSA dev libraries** (for audio output)
- Optional: **PipeWire** (for multi-speaker mixing), **BlueZ** (for future Auracast)

**On Raspberry Pi OS (Debian):**
```bash
sudo apt update
sudo apt install build-essential cmake libasound2-dev
# Optional:
sudo apt install libpipewire-0.3-dev avahi-daemon
```

### Build

```bash
cd box
mkdir -p build && cd build
cmake ..
make
```

### Run

```bash
./voxswap-box --no-hotspot
```

The `--no-hotspot` flag skips WiFi hotspot setup (for dev — the Pi connects to your home WiFi instead of hosting its own network).

**Production mode** (Pi hosts WiFi hotspot "VoxSwap-XXXX"):
```bash
./voxswap-box
```

### Deploy to Raspberry Pi

```bash
# Copy source
scp box/src/*.c box/src/*.h pi@<pi-ip>:~/voxswap-box/src/

# Build on Pi
ssh pi@<pi-ip> "cd ~/voxswap-box/build && cmake .. && make"

# Run on Pi
ssh pi@<pi-ip> "cd ~/voxswap-box/build && ./voxswap-box --no-hotspot"
```

If the Pi is running Avahi (mDNS), you can use `voxswap.local` instead of the IP address.

### Box Architecture

```
box/src/
+-- main.c                 # Entry point, epoll event loop
+-- stream-receiver.c/.h   # TCP/UDP server, receives audio from phones
+-- audio-mixer.c/.h       # Mixes multiple speaker streams per channel
+-- wifi-hotspot.c/.h      # WiFi AP management (hostapd + dnsmasq)
+-- auracast-broadcast.c/.h # LE Audio broadcast (stub - needs Auracast hardware)
+-- settings-server.c/.h   # Settings storage, admin management
```

---

## System Requirements

| Component | Minimum |
|-----------|---------|
| Phone | Android 7.0+ (API 24), arm64, 4GB+ RAM |
| Box | Raspberry Pi 4 (or any Linux board with WiFi + audio out) |
| Network | Both on same WiFi network (dev) or phone connected to box hotspot (production) |
| Internet | Required only for first-launch model download (~1GB) |

## Constraints

- Max 3 simultaneous speakers
- 2 target translation languages per session
- Latency: ~2-3 seconds from end of utterance to translated audio
- All AI inference runs on phone CPU (no GPU/NPU acceleration)
- Phone does all heavy processing; box is lightweight receive + output
