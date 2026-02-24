# VoxSwap Mobile App — Implementation Plan

> Library API details, code examples, and integration research: see [library-research.md](./library-research.md)

## Implementation Notes

- **expo-file-system v19** uses new class-based API: `File`, `Directory`, `Paths` from `expo-file-system`.
  - `Paths.document` / `Paths.cache` for directories
  - `new Directory(Paths.document, 'models').create()` to make dirs
  - `File.downloadFileAsync(url, destination)` to download files
  - `file.exists` to check existence
  - Old `documentDirectory` / `getInfoAsync` / `createDownloadResumable` are in `expo-file-system/legacy`
- **expo-file-system v19** does NOT have download progress callbacks in the new API. Use `expo/fetch` + manual progress or `expo-file-system/legacy` for progress tracking.
- **whisper.rn exports bug**: Package only has `"./*"` in exports (no `"."` entry). TypeScript `moduleResolution: "bundler"` can't resolve bare or subpath imports. Fix: add `paths` mappings in `tsconfig.json` pointing to the real `.d.ts` files inside the package.
- **RTranslator reference**: Cloned at `/home/bhavesh-choudhary/work/voxswap/RTranslator`. Key reference for NLLB-200 translation (model splitting, SentencePiece tokenization via native C++ JNI, autoregressive decoding with KV-cache). RTranslator uses Android system TTS (not on-device models).

## Model Stack (Decided)

| Component | Model | Size (INT8) | Purpose |
|-----------|-------|-------------|---------|
| VAD | whisper.rn built-in VAD | bundled | Speech detection, audio segmentation |
| STT | Whisper Small | ~244MB | Speech-to-text (99 languages) |
| Translation | NLLB-200 Distilled 600M | ~600MB | Text-to-text (200 languages) |
| TTS | Kokoro 82M | ~82MB | Text-to-speech (EN, FR, ES, JA, KO, ZH, HI, IT, PT) |
| TTS fallback | Piper (via sherpa-onnx) | ~60MB/voice | For languages Kokoro doesn't cover (DE, AR, RU) |

Total download: ~1.1-1.2GB on first launch.

## Integration Method (Revised after research)

| Component | Library | Why |
|-----------|---------|-----|
| STT + VAD | whisper.rn (RealtimeTranscriber) | Built-in VAD + STT in one, auto-slices on speech end, native C++ |
| Audio capture | react-native-live-audio-stream | Raw PCM for sending original audio to box via UDP |
| Translation | onnxruntime-react-native + SentencePiece | NLLB ONNX model, INT8 quantized, autoregressive decoding |
| TTS (Kokoro + Piper) | sherpa-onnx (forked/extended RN wrapper) | Native espeak-ng phonemizer, supports both Kokoro and Piper |
| Networking | react-native-tcp-socket + react-native-udp | TCP control + UDP audio |

Key change from original plan: whisper.rn handles both VAD and STT (no separate Silero VAD needed).
TTS uses sherpa-onnx for both engines (provides native espeak-ng phonemization that Kokoro requires).

## Supported Languages

Based on what both NLLB (translation) and Kokoro/Piper (TTS) can handle:

| Language | STT (Whisper) | Translation (NLLB) | TTS | TTS Engine |
|----------|---------------|---------------------|-----|------------|
| English | yes | yes | yes | Kokoro |
| Spanish | yes | yes | yes | Kokoro |
| French | yes | yes | yes | Kokoro |
| Portuguese | yes | yes | yes | Kokoro |
| Italian | yes | yes | yes | Kokoro |
| Chinese | yes | yes | yes | Kokoro |
| Japanese | yes | yes | yes | Kokoro |
| Korean | yes | yes | yes | Kokoro |
| Hindi | yes | yes | yes | Kokoro |
| German | yes | yes | yes | Piper |
| Arabic | yes | yes | yes | Piper |
| Russian | yes | yes | yes | Piper |

## Pipeline Flow

```
Mic (PCM 16kHz mono) — two parallel consumers:

1. react-native-live-audio-stream → buffer raw audio → UDP send (original stream)

2. whisper.rn RealtimeTranscriber (has built-in VAD + STT):
   → VAD detects speech start/end
   → on speech end (auto-slice): Whisper Small transcribes segment
     → display caption on screen
     → NLLB-200 (text → translated text, for each target language)
       → sherpa-onnx TTS (translated text → audio, Kokoro or Piper per language)
         → UDP send to box (2 translated audio streams)
```

---

## Phases

### Phase 1: Constants, Types & Project Config

Update SUPPORTED_LANGUAGES to final 12 languages with TTS engine info.
Add model-related types (ModelStatus, DownloadProgress, etc.).
Add model config constants (URLs, sizes, filenames).
Update CLAUDE.md and PROJECT.md with decided model stack.

### Phase 2: Model Download & Management

Create model-manager service that:
- Checks which models are already downloaded
- Downloads missing models with progress callbacks
- Stores models in app's document directory
- Verifies file integrity after download
- Exposes status per model (not_downloaded, downloading, ready, error)

Create model-download screen:
- Shows each model with name, size, download status
- Progress bars for active downloads
- "Download All" button
- Shown on first launch or when models are missing

### Phase 3: Audio Capture

Install react-native-live-audio-stream.
Implement audio-capture service:
- Start/stop mic recording
- Stream raw PCM 16-bit 16kHz mono chunks
- Handle permissions (Android + iOS)
- Expose audio level (RMS) for UI visualization
- Buffer raw audio for sending original stream to box

### Phase 4: STT + VAD (whisper.rn RealtimeTranscriber)

Install whisper.rn + @fugood/react-native-audio-pcm-stream + react-native-fs.
Implement STT service using RealtimeTranscriber:
- Initialize whisper context + VAD context
- Start/stop realtime transcription
- onTranscribe callback feeds captions to translationStore
- onVad callback tracks speech activity for UI
- Auto-slicing on speech end provides completed text segments
- Language detection from Whisper output

### Phase 5: Translation (NLLB-200)

This is the most complex integration. See library-research.md §3 for details.
Implement translation service using NLLB-200 via ONNX Runtime:
- Export & quantize NLLB model (optimum CLI, INT8)
- Split model into 4 ONNX files (RTranslator approach) to reduce peak RAM
- Implement SentencePiece tokenizer (JS or native module)
- Implement autoregressive decoding loop with kv-cache
- Accept source text + source language, translate to both targets
- NLLB language code mapping (eng_Latn, fra_Latn, etc.)

### Phase 6: TTS (sherpa-onnx — Kokoro + Piper)

Fork/extend react-native-sherpa-onnx-offline-tts to support Kokoro config.
Implement TTS service:
- Initialize sherpa-onnx with Kokoro model config (model, voices, tokens, espeak-ng-data)
- Initialize Piper model config for fallback languages
- Accept translated text + target language
- Select correct engine based on target language
- Return synthesized PCM audio (Kokoro: 24kHz, Piper: 22050Hz)
- Resample to 16kHz for UDP streaming

### Phase 7: Networking

Install react-native-tcp-socket and react-native-udp.
Implement network-client service (TCP):
- Connect to box at 10.0.0.1:7700
- Send REGISTER with speaker info
- Handle heartbeat (2s interval)
- Receive session config (target languages)
- Auto-reconnect on disconnect

Implement stream-sender service (UDP):
- Send audio packets to 10.0.0.1:7701
- 10-byte header + PCM payload
- 3 streams per speaker (original, lang1, lang2)

### Phase 8: UI Screens

Build language-select screen:
- Source language picker (scrollable list with search)
- Show which TTS engine covers each language
- Target languages shown (set by admin via box, read-only for non-admin)

Build settings screen:
- Connection status, speaker name, model status
- Button to re-download models, WiFi info, app version

Build language-picker and connection-status components.

### Phase 9: Full Pipeline Integration

Wire everything end-to-end:
- Mic → (raw audio → UDP) + (RealtimeTranscriber → STT → Translation → TTS → UDP)
- Live captions from STT on home screen
- Audio level ring animation on mic button
- Handle concurrent model loading (load on app start, show loading state)
- Memory management (unload models when not translating)

### Phase 10: Polish & Edge Cases

- Background mode (Android foreground service, iOS audio background)
- Battery/thermal monitoring + user warnings
- Error handling + auto-reconnect
- Phone call interruption (pause/resume)
- Model download resume on network failure
- Test with 3 phones simultaneously
- iOS testing

---

## Detailed TODO

### Phase 1: Constants, Types & Project Config ✅

- [x] 1.1 Update `SUPPORTED_LANGUAGES` in `src/constants/index.ts`
- [x] 1.2 Add model types to `src/types/index.ts`
- [x] 1.3 Add model config constants to `src/constants/models.ts`
- [x] 1.4 Add model store to `src/stores/model-store.ts`
- [x] 1.5 Update `CLAUDE.md` with revised model stack and integration method
- [x] 1.6 Update `PROJECT.md` with decided model stack (replace "TBD" sections)

### Phase 2: Model Download & Management ✅

- [x] 2.1 Create `src/services/model-manager.ts`
- [x] 2.2 Create `src/hooks/use-model-manager.ts`
- [x] 2.3 Create `src/app/model-download.tsx` screen
- [x] 2.4 Add route guard in `src/app/_layout.tsx`

### Phase 3: Audio Capture ✅

- [x] 3.1 Install `react-native-live-audio-stream` + `buffer`
- [x] 3.2 Create `src/services/audio-capture.ts`
- [x] 3.3 Create `src/hooks/use-audio-recorder.ts`
- [x] 3.4 Update home screen mic button

### Phase 4: STT + VAD (whisper.rn) ✅

- [x] 4.1 Install whisper.rn dependencies
- [x] 4.2 Create `src/services/stt-service.ts`
- [x] 4.3 Create `src/hooks/use-stt.ts`
- [x] 4.4 Added ambient type declarations for whisper.rn (`src/types/whisper.rn.d.ts`)

### Phase 5: Translation (NLLB-200) ✅

- [ ] 5.1 Prepare NLLB ONNX models (on dev machine, not in app code)
  - Export with `optimum-cli export onnx --model facebook/nllb-200-distilled-600M`
  - Quantize to INT8
  - Host encoder_model.onnx + decoder_model.onnx + tokenizer.json for download
- [x] 5.2 Solve SentencePiece tokenization
  - Pure TS BPE tokenizer that loads HuggingFace tokenizer.json (17MB, downloaded with model)
  - No native deps, no WASM — works cross-platform
  - Handles SentencePiece ▁ prefix, byte fallback, merge priorities
- [x] 5.3 Install `onnxruntime-react-native` (already installed)
- [x] 5.4 Create NLLB language code mapping in `src/constants/nllb-languages.ts`
- [x] 5.5 Create `src/services/translation-service.ts`
  - Uses standard optimum ONNX export (2 files: encoder + decoder)
  - Autoregressive decoding with KV-cache (6 decoder layers, 16 heads, head_dim=64)
  - `translateToMultiple()` runs encoder once, decoder per target
  - Greedy decoding with forced target language token
- [x] 5.6 Create `src/hooks/use-translation.ts`
- [ ] 5.7 Test translation standalone (requires models on device)

### Phase 6: TTS (Kokoro via ONNX Runtime) ✅

- [x] 6.1 Kokoro TTS via direct `onnxruntime-react-native` (no sherpa-onnx needed)
  - Reference: expo-kokoro-onnx (cloned at /home/bhavesh-choudhary/work/voxswap/expo-kokoro-onnx)
  - Model: kokoro-v1.0 q8f16 (86MB), voices as .bin files (~520KB each)
  - Input tensors: input_ids (int64, phoneme tokens), style (float32, [1,256]), speed (float32)
  - Output: waveform (float32, 24kHz mono)
- [x] 6.2 Create `src/services/phonemizer.ts`
  - IPA vocabulary → token ID mapping (full Kokoro vocab)
  - English phonemizer with digraph handling + common word dictionary
  - Extensible for other languages (TODO: add FR, ES, etc.)
- [x] 6.3 Create `src/services/tts-service.ts`
  - `initTts()` — load Kokoro ONNX session + default voice
  - `synthesize(text, voiceId, speed)` → Float32Array (24kHz)
  - `floatToPcm16()` — convert waveform to 16-bit PCM
  - Voice embedding management (download on demand, cache in memory)
- [x] 6.4 Create `src/utils/audio-resample.ts`
  - Linear interpolation resampler (24kHz → 16kHz for UDP)
- [ ] 6.5 Piper TTS for DE/AR/RU — deferred (needs native sherpa-onnx bridge)
- [ ] 6.6 Test TTS standalone (requires models on device)

### Phase 7: Networking ✅

- [x] 7.1 Install `react-native-tcp-socket` + `react-native-udp`
- [x] 7.2 Create `src/services/network-client.ts` (TCP)
  - Binary protocol: 4-byte length prefix + message body
  - Message types: REGISTER (0x01), HEARTBEAT (0x02), SET_LANGUAGES (0x03)
  - Response types: REGISTER_ACK (0x81), SESSION_CONFIG (0x82)
  - Auto-reconnect with exponential backoff
  - Heartbeat every 2s
- [x] 7.3 Create `src/services/stream-sender.ts` (UDP)
  - 10-byte header: speaker_id(1) + stream_type(1) + sequence(4) + timestamp(4)
  - Per-stream sequence counters, chunked sending for large buffers
- [x] 7.4 Create `src/hooks/use-connection.ts`
- [ ] 7.5 Test networking with mock server (requires box or mock)

### Phase 8: UI Screens ✅

- [x] 8.1 Build language-picker component (`src/components/language-picker.tsx`)
- [x] 8.2 Build language-select screen (`src/app/language-select.tsx`)
- [x] 8.3 Build connection-status component (`src/components/connection-status.tsx`)
- [x] 8.4 Build settings screen (`src/app/settings.tsx`)

### Phase 9: Full Pipeline Integration ✅

- [x] 9.1 Create `src/services/pipeline.ts` — orchestration service
  - `loadModels()` → parallel init of STT, NLLB, TTS
  - `startPipeline(srcLang, tgtLangs)` → mic permission → load models → start audio capture + STT
  - `stopPipeline()` → stop recording + STT, cleanup listeners
  - `releaseAll()` → release all model sessions
  - On transcription: translate → TTS → resample 24kHz→16kHz → UDP send (per target)
  - Original audio sent to UDP via audio-capture listener
  - Error handling: each stage catches and logs, skips failed chunks
- [x] 9.2 Create `src/hooks/use-pipeline.ts`
  - Wraps pipeline service, connects to translationStore + connectionStore
  - Auto-updates languages when session or sourceLanguage changes
  - Exposes: start(), stop(), release(), preload(), status, isRunning, isReady
- [x] 9.3 Wire home screen to pipeline
  - Mic button calls pipeline start/stop
  - Captions from STT → translationStore → UI
  - Audio level ring from pipeline audio-level callback
  - Loading models state disables button + shows "Loading models..."
  - Translating state shows red button + "Tap to stop"
- [x] 9.4 Wire original audio to UDP (in pipeline.ts audio chunk listener)
- [x] 9.5 Wire translated audio to UDP (in pipeline.ts processTranscription)
- [ ] 9.6 End-to-end test (requires device with models downloaded)

### Phase 10: Polish & Edge Cases ✅

- [x] 10.1 Background mode
  - iOS: UIBackgroundModes: ["audio"] already in app.json
  - Android: FOREGROUND_SERVICE + FOREGROUND_SERVICE_MICROPHONE permissions configured
  - react-native-live-audio-stream handles foreground service automatically
- [x] 10.2 Battery & thermal monitoring
  - Installed expo-battery
  - Created `src/hooks/use-battery-monitor.ts` (polls every 30s)
  - Home screen shows low battery warning banner when < 20% and translating
- [x] 10.3 Error handling
  - TCP auto-reconnect with exponential backoff (already in network-client.ts)
  - Pipeline catches and logs translation/TTS errors per chunk, continues with next
  - Status callbacks surface errors to UI
- [x] 10.4 Phone call interruption
  - AppState listener in use-pipeline.ts pauses pipeline on 'inactive' (phone call)
  - Auto-resumes pipeline when returning to 'active'
- [x] 10.5 Model download resilience
  - Added retry with exponential backoff (3 attempts, 1s→2s→4s) to model-manager.ts
  - Cleans up partial downloads before retry
- [ ] 10.6 Multi-speaker testing (requires 3 physical devices + box)
- [ ] 10.7 iOS testing (requires iOS device + EAS build)
