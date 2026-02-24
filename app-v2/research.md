# Codebase Research

## Overview

This is a native Android app (Java) for offline speech-to-speech translation using on-device ML models (Whisper STT + NLLB-200 translation + system TTS). It supports 3 modes: Conversation (2 phones via Bluetooth), WalkieTalkie (translate-and-speak), and TextTranslation (type-to-translate).

---

## 1. ML & Neural Networks

### 1.1 ONNX Model Architecture

**Whisper STT -- 6 ONNX sessions** (`Recognizer.java`):
- `Whisper_initializer.onnx` (`initSession`) -- Pre-ops: takes raw PCM audio (`audio_pcm`), produces mel spectrogram features. Uses `OrtxPackage` custom ops for audio preprocessing.
- `Whisper_encoder.onnx` (`encoderSession`) -- Encodes mel features into hidden states (`last_hidden_state`). RAM-adaptive options (see below).
- `Whisper_cache_initializer.onnx` (`cacheInitSession`) -- Converts encoder hidden states into initial cross-attention KV-cache for decoder (batch=1).
- `Whisper_cache_initializer_batch.onnx` (`cacheInitBatchSession`) -- Same as above but for batch=2 (dual-language mode).
- `Whisper_decoder.onnx` (`decoderSession`) -- Autoregressive decoder with KV-cache. 12 layers, 64 hidden size per head, 16 heads. Inputs: `input_ids` + `past_key_values.{0-11}.{decoder,encoder}.{key,value}`. Outputs: `logits` + updated `present.{0-11}.decoder.{key,value}`.
- `Whisper_detokenizer.onnx` (`detokenizerSession`) -- Converts token ID sequences to text strings. Also uses `OrtxPackage` custom ops. Note: optimization is NOT disabled for this session (unlike all other sessions).

**NLLB Translation -- 4 ONNX sessions** (`Translator.java`):
- `NLLB_encoder.onnx` (`encoderSession`) -- Encodes tokenized text into hidden states.
- `NLLB_decoder.onnx` (`decoderSession`) -- Autoregressive decoder with KV-cache. 12 layers, 64 hidden size, 16 heads.
- `NLLB_cache_initializer.onnx` (`cacheInitSession`) -- Converts encoder output to initial cross-attention KV-cache.
- `NLLB_embed_and_lm_head.onnx` (`embedAndLmHeadSession`) -- Shared embedding + LM head model. Dual-purpose: (a) `use_lm_head=false` produces `embed_matrix` from `input_ids`, (b) `use_lm_head=true` produces `logits` from `pre_logits`. Embedding and LM head are separated from encoder/decoder to reduce model duplication (they share weights).

All model files loaded from `context.getFilesDir()` (app internal storage), not bundled in APK.

### 1.2 Memory Optimizations

- **`NO_OPT` optimization level** on all sessions except Whisper detokenizer -- prevents ONNX Runtime from creating optimized graph copies that consume extra RAM.
- **`setCPUArenaAllocator(false)`** on all sessions -- disables pre-allocated memory arena, reducing peak memory.
- **`setMemoryPatternOptimization(false)`** on all sessions -- disables memory pattern optimization that pre-plans allocation.
- **RAM-adaptive settings** for Whisper encoder only: if `global.getTotalRamSize() <= 7000` (MB), arena allocator and memory pattern are disabled; if >7GB RAM, they are enabled for speed.
- **Explicit `result.close()`** after every decoder iteration -- `oldResult.close()` releases the memory occupied by the previous decoder output. Without this, KV-cache tensors accumulate and RAM grows rapidly.
- **`embedResult.close()`** after each use of the embedding session.
- **`ByteBuffer.allocateDirect()`** for zero-valued tensors in `TensorUtils` -- avoids copying buffer values, reducing execution time from ~500ms to ~4ms.
- **`OnnxTensor.getBuffer()` via reflection** in `CacheContainerNative` and `TensorUtils.extractFloatMatrix()` -- accesses the private buffer directly without copying data.
- **Null-setting arrays after use** (e.g., `encoderValue = null`) to hint GC for large intermediate arrays.
- **Batched encoder output creation** uses direct `ByteBuffer` with `OnnxJavaType.FLOAT` for efficient tensor creation.

### 1.3 Whisper STT Pipeline

1. **Audio input**: `Recognizer.recognize()` receives `float[]` PCM audio (16kHz mono, values in [-1, 1]) from `Recorder`.
2. **Queue processing**: Audio chunks queued in `dataToRecognize` (ArrayDeque), processed serially with a `synchronized` lock.
3. **Pre-ops** (`initSession`): Raw PCM wrapped as `OnnxTensor` -> session produces mel spectrogram features.
4. **Encoder** (`encoderSession`): Mel features -> encoder hidden states.
5. **Cache initialization**: Encoder hidden states -> initial encoder KV-cache. Uses `cacheInitSession` for batch=1, `cacheInitBatchSession` for batch=2.
6. **Decoder loop** (greedy, autoregressive):
   - First 4 tokens are fixed: `[50258 (START), languageID, 50359 (TRANSCRIBE), 50363 (NO_TIMESTAMPS)]`.
   - Each iteration feeds `input_ids` + full KV-cache (decoder self-attention grows, encoder cross-attention stays fixed from init).
   - Greedy decoding: `Utils.getIndexOfLargest(outputValues)` picks argmax token.
   - Loop until EOS token (50257) or `maxTokens` limit (30 tokens/second of audio, capped at 445).
   - `oldResult.close()` every iteration to free memory.
7. **Detokenizer** (`detokenizerSession`): Token IDs -> final text string.
8. **Post-processing** (`correctText()`): Removes timestamp artifacts (`<|0.00|>`), trims whitespace, capitalizes first letter, removes `"..."`.

**Dual-language batch mode**: When two language codes provided, batch_size=2. Both languages decoded in parallel in the same decoder run. Outputs split into `completeOutput` and `completeOutput2`. Probabilities tracked separately, normalized by sequence length. Results reported via `RecognizerMultiListener.onSpeechRecognizedResult()` with both texts + scores. If either hits max token limit, its text is set to `UNDEFINED_TEXT = "[(und)]"`.

### 1.4 NLLB Translation Pipeline

1. **Text splitting**: `BreakIterator.getSentenceInstance()` splits input into sentences. Adjacent sentences re-joined if combined token count < 200, or if a segment has < 5 tokens.
2. **Text correction** (`correctText()`): Trims whitespace, appends language-appropriate sentence terminator (`.`, `。`, `।`, `။`) if missing.
3. **Tokenization** (`Tokenizer.tokenize()`):
   - `SentencePieceProcessorJava` (JNI wrapper around native SentencePiece C++ library) encodes text to token IDs.
   - ID remapping: SentencePiece IDs are shifted by +1 for NLLB compatibility. Special tokens 0-3 are remapped (`1->3, 2->0, 3->2`).
   - Source language ID prepended, EOS (`</s>`) appended. Format: `[srcLangID, token1, token2, ..., EOS]`.
   - Language IDs: `DICTIONARY_LENGTH (256000) + language_index + 1`. Languages stored in `languagesNLLB` array (204 languages).
   - Attention mask: all 1s for actual tokens.
4. **Encoder**: Embedding done separately via `embedAndLmHeadSession` (with `use_lm_head=false`) -> `embed_matrix` fed to encoder along with `input_ids` and `attention_mask`.
5. **Cache initialization**: Encoder output -> cross-attention KV-cache.
6. **Decoder loop** (greedy with cache, `executeCacheDecoderGreedy()`):
   - Initial `input_ids = [2]` (BOS for NLLB).
   - First iteration: decoder self-attention cache is empty (shape `[1, 16, 0, 64]`), encoder cache from init.
   - After iteration 1: `input_ids` set to target language ID (not the predicted token).
   - After iteration 2+: `input_ids` set to the predicted token (greedy argmax).
   - LM head executed separately: decoder outputs `pre_logits` -> `embedAndLmHeadSession` with `use_lm_head=true` -> `logits`.
   - Partial results decoded and returned each iteration via callback.
   - **Early stop**: If decoder loops too long relative to input length (3x-8x input tokens depending on input size).
7. **Beam search** (`executeCacheDecoderBeam()`): Implemented but noted as unstable ("random crashes"). Uses `CacheContainerNative` for efficient cache reordering via JNI. First iteration batch=1, then expanded to batch=beamSize. Log-probability scoring with EOS penalty (0.9 divisor). Uses `logSumExpFast()` for efficient probability calculation.
8. **Decoding**: `Tokenizer.decode()` converts IDs back to text by calling `SentencePieceProcessorJava.IDToPiece()` for each token, skipping special tokens (IDs 0-3 and IDs >= 256000). Replaces SentencePiece word boundary character `▁` with spaces.
9. **Language detection**: `Translator.detectLanguage()` uses Google ML Kit `LanguageIdentification` (online) with configurable confidence threshold. Supports single and dual-result detection.

### 1.5 TTS (Text-to-Speech)

- **System Android TTS** via `android.speech.tts.TextToSpeech` -- NOT an on-device ONNX model.
- `TTS.java` is a wrapper around the platform TTS engine (Google TTS, Samsung TTS, Huawei, etc.).
- Key methods: `speak()`, `setLanguage()`, `stop()`, `shutdown()`.
- `getSupportedLanguages()`: Creates a temporary TTS instance, queries `getVoices()`, filters by quality (>= `QUALITY_NORMAL` by default, or `QUALITY_VERY_LOW` if low-quality setting enabled), excludes `legacySetLanguageVoice` voices.
- Language set via `new Locale(locale.getLanguage())` -- only the language portion, no region.
- Supports `UtteranceProgressListener` for tracking speech completion.

### 1.6 Audio Recording (`Recorder.java`)

- **Android `AudioRecord`** with `MediaRecorder.AudioSource.MIC`.
- **Sample rate**: 16000 Hz (only candidate in `SAMPLE_RATE_CANDIDATES`).
- **Encoding**: `ENCODING_PCM_FLOAT` (values in [-1, 1]) by default. Falls back to `ENCODING_PCM_16BIT` on Vivo phones (bug workaround), with manual conversion to float.
- **Channel**: Mono (`CHANNEL_IN_MONO`).
- **Buffer**: Circular `float[]` buffer sized for `MAX_SPEECH_LENGTH_MILLIS + 1000ms = 30s` (480,000 samples). `readSize = (minBufferSize/4) * 2`.
- **VAD-like behavior** (amplitude threshold):
  - `isHearingVoice()`: Iterates buffer section, counts samples exceeding threshold (converted back to 16-bit scale). Requires 15+ samples above threshold to trigger.
  - Configurable: `amplitudeThreshold` (default 2000, range 400-15000), `speechTimeout` (default 1300ms, range 100-5000ms), `prevVoiceDuration` (default 1300ms, range 100-1800ms).
  - `MAX_SPEECH_LENGTH_MILLIS = 29s` -- hard cap, calls `end()` to flush audio.
  - On silence timeout (`speechTimeout`): calls `end()` which extracts the voice segment from circular buffer and delivers via `mCallback.onVoice(data, length)`.
- **Previous voice capture**: When voice starts, `startVoiceIndex` is set `prevVoiceDuration` samples back, capturing audio just before the trigger.
- **Manual mode**: `isManualMode` bypasses VAD, records continuously until `stopRecording()`.
- **Bluetooth headset support**: Detects SCO, BLE headset, and A2DP devices. Sets preferred device or starts BluetoothSco. Registers `AudioDeviceCallback` for connect/disconnect events.
- **Volume level**: Computes average amplitude of each read chunk, normalizes to [0, 1], reports via `mCallback.onVolumeLevel()`.

### 1.7 Model Download & Integrity

- `NeuralNetworkApi.testModelIntegrity()`: Verifies a model file by attempting to load it as an ONNX session. If `OrtException` is thrown, the model is corrupt (`ERROR_LOADING_MODEL`).
  - Uses same restrictive session options: `NO_OPT`, no arena allocator, no memory pattern.
  - Exception: `Whisper_detokenizer.onnx` is tested WITH optimization (because it runs that way in production).
  - `isVerifying` static flag prevents concurrent verification.
- Model files expected in `context.getFilesDir()`: `NLLB_encoder.onnx`, `NLLB_decoder.onnx`, `NLLB_embed_and_lm_head.onnx`, `NLLB_cache_initializer.onnx`, `Whisper_initializer.onnx`, `Whisper_encoder.onnx`, `Whisper_decoder.onnx`, `Whisper_cache_initializer.onnx`, `Whisper_cache_initializer_batch.onnx`, `Whisper_detokenizer.onnx`.
- `sentencepiece_bpe.model` copied from assets to internal storage on first use (SentencePiece JNI requires a file path, cannot read from assets directly).

### 1.8 Language Support

- **CustomLocale**: Wrapper around `java.util.Locale` used throughout the app for language representation.
- **Whisper language codes**: 99 languages in `LANGUAGES` array (ISO 639-1 codes: `"en"`, `"zh"`, etc.). Language ID = `50258 + index + 1`. Supported languages filtered via XML resource (`R.raw.whisper_supported_languages`) by WER quality threshold.
- **NLLB language codes**: 204 languages in NLLB format (`"eng_Latn"`, `"fra_Latn"`, etc.) in `Tokenizer.languagesNLLB` array. Mapping from ISO codes to NLLB codes loaded from XML resource (`R.raw.nllb_supported_languages_all`) into `nllbLanguagesCodes` HashMap. Conversion via `getNllbLanguageCode()`.
- **Quality filtering**: `SharedPreferences` boolean `languagesNNQualityLow` toggles between curated high-quality language list and full language list for both Whisper and NLLB.
- **TTS languages**: Determined at runtime by querying the system TTS engine's available voices.
- **Language intersection**: The app computes the intersection of Whisper-supported, NLLB-supported, and TTS-supported languages to determine the final available set (handled outside these files).

### 1.9 Key Methods

| Class | Method | Purpose |
|-------|--------|---------|
| `Recognizer` | `recognize(float[], int, String)` | Queue audio for single-language STT |
| `Recognizer` | `recognize(float[], int, String, String)` | Queue audio for dual-language batch STT |
| `Recognizer` | `getLanguageID(String)` | Convert ISO code to Whisper token ID |
| `Recognizer` | `getSupportedLanguages(Context)` | Get filtered list of Whisper languages |
| `Translator` | `translate(String, CustomLocale, CustomLocale, int, boolean)` | Translate text between languages |
| `Translator` | `translateMessage(ConversationMessage, CustomLocale, int, TranslateMessageListener)` | Queue-based message translation |
| `Translator` | `detectLanguage(NeuralNetworkApiResult, boolean, DetectLanguageListener)` | Identify language of text via ML Kit |
| `Translator` | `executeCacheDecoderGreedy()` | Run NLLB greedy decoding loop with KV-cache |
| `Translator` | `executeCacheDecoderBeam()` | Run NLLB beam search (unstable) |
| `Translator` | `getSupportedLanguages(Context, int)` | Get filtered list of NLLB languages |
| `Tokenizer` | `tokenize(String, String, String)` | SentencePiece encode + language ID prepend + attention mask |
| `Tokenizer` | `decode(int[])` | Convert token IDs back to text |
| `Tokenizer` | `getLanguageID(String)` | Convert NLLB language code to token ID (256000 + index + 1) |
| `SentencePieceProcessorJava` | `encode(String)` / `PieceToID(String)` / `IDToPiece(int)` | JNI bridge to native SentencePiece |
| `Recorder` | `start()` / `stop()` / `end()` | Control audio recording lifecycle |
| `Recorder` | `isHearingVoice()` | Amplitude-based voice activity detection |
| `TTS` | `speak()` / `setLanguage()` / `getSupportedLanguages()` | System TTS wrapper |
| `NeuralNetworkApi` | `testModelIntegrity(String, InitListener)` | Verify ONNX model file is loadable |
| `TensorUtils` | `convertIntArrayToTensor()` / `createFloatTensorWithSingleValue()` | Efficient OnnxTensor creation helpers |
| `CacheContainerNative` | `reorder(int[])` / `getCacheResult()` | JNI-backed KV-cache management for beam search |
| `Utils` | `getIndexOfLargest()` / `softmax()` / `logSumExpFast()` | Greedy/beam decoding math utilities |

---

## 2. App Architecture & Services

### 2.1 App Lifecycle

- **Entry point**: `LoadingActivity` (extends `GeneralActivity`), declared as launcher activity
- **Splash screen**: Uses `SplashScreen.installSplashScreen()`, stays visible until model loading completes or an error is shown
- **Boot sequence** (`LoadingActivity.onResume()`):
  1. If `Global.isFirstStart()` is true -> launches `AccessActivity` (permissions + model download)
  2. If `Translator` and `Recognizer` are already initialized on `Global` -> go straight to `VoiceTranslationActivity`
  3. Otherwise -> `initializeApp()`: loads language lists (`Global.getLanguages()`), initializes `Translator` (NLLB), initializes `Recognizer` (Whisper), then launches `VoiceTranslationActivity`
- **Error handling during boot**: Model loading errors -> dialog offering "Fix" (re-downloads corrupted models via `restartDownload()`), internet errors -> retry dialog, TTS errors -> continue-without-TTS option
- **Global.java** (`extends Application, implements DefaultLifecycleObserver`):
  - Singleton state holder for the entire app
  - Holds shared instances: `Translator`, `Recognizer` (Whisper), `ConversationBluetoothCommunicator`, `RecentPeersDataManager`
  - Lazy initialization: `initializeTranslator()`, `initializeSpeechRecognizer()`, `initializeBluetoothCommunicator()` -- creates only if null, otherwise calls `onInitializationFinished()` immediately
  - Language management: stores `language` (device lang), `firstLanguage`, `secondLanguage` (for voice modes), `firstTextLanguage`, `secondTextLanguage` (for text mode) -- all persisted to `SharedPreferences("default")`
  - `getLanguages()` returns the intersection of Translator-supported and Recognizer-supported languages (optionally also TTS-supported)
  - Mic settings: `micSensitivity` (0-100, maps to `amplitudeThreshold`), `speechTimeout`, `prevVoiceDuration`, `beamSize` -- all persisted
  - Tracks foreground state via `DefaultLifecycleObserver.onStart()/onStop()`
  - Creates notification channel `"service_background_notification"` on startup

### 2.2 Activity Hierarchy

- **`GeneralActivity`** (abstract, extends `FragmentActivity`):
  - Base class for all activities
  - Provides shared error dialog methods: `showMissingGoogleTTSDialog()`, `showGoogleTTSErrorDialog()`, `showInternetLackDialog()`, `showConfirmDeleteDialog()`, `showConfirmExitDialog()`
  - Has empty `onError()` hook for subclasses

- **`LoadingActivity`** (extends `GeneralActivity`):
  - Splash/loading screen, initializes ML models, routes to `AccessActivity` or `VoiceTranslationActivity`

- **`VoiceTranslationActivity`** (extends `GeneralActivity`):
  - The single main activity after loading -- all 3 modes live here as fragments
  - Uses `FragmentTransaction.replace()` on `R.id.fragment_container` (a `CoordinatorLayout`) -- NOT a ViewPager at the activity level
  - Fragment constants: `PAIRING_FRAGMENT=0`, `CONVERSATION_FRAGMENT=1`, `WALKIE_TALKIE_FRAGMENT=2`, `TRANSLATION_FRAGMENT=3`, default is `TRANSLATION_FRAGMENT`
  - `setFragment(int)` switches fragments, stops irrelevant services (e.g. switching to TRANSLATION stops both ConversationService and WalkieTalkieService)
  - Saves current fragment to `SharedPreferences` via `saveFragment()`, restores on `onStart()`
  - Manages Bluetooth: delegates `startSearch()`, `stopSearch()`, `connect()`, `disconnect()`, `acceptConnection()`, `rejectConnection()` to `Global.getBluetoothCommunicator()`
  - Service management:
    - `startWalkieTalkieService()`: resolves first+second language, puts them as Intent extras, calls `startService()`
    - `startConversationService()`: resolves device language, starts service
    - `connectToWalkieTalkieService()` / `connectToConversationService()`: starts service, creates `CustomServiceConnection` with a `ServiceCommunicator`, calls `bindService()`
    - `disconnectFromWalkieTalkieService()` / `disconnectFromConversationService()`: finds matching connection, unbinds
    - `stopConversationService()` / `stopWalkieTalkieService()`: calls `stopService()`
  - Builds foreground notification (`buildNotification()`) for background operation
  - Callback system: `VoiceTranslationActivity.Callback` extends `ConversationBluetoothCommunicator.Callback`, registered with both the activity and the BT communicator

### 2.3 Service Hierarchy

- **`GeneralService`** (abstract, extends `Service`):
  - Base for all services
  - Defines `INITIALIZE_COMMUNICATION=50` command to receive client's `Messenger`
  - `executeCommand(int, Bundle)`: processes commands from client, returns true if handled
  - `notifyToClient(Bundle)`: sends messages to bound client via `Messenger`
  - `notifyError(int[], long)`: wraps error info into a Bundle with `callback=ON_ERROR`

- **`VoiceTranslationService`** (abstract, extends `GeneralService`):
  - Core service layer for both WalkieTalkie and Conversation modes
  - **Mic/Recorder management**:
    - `mVoiceRecorder` (nullable `Recorder`): wraps audio recording with VAD-like behavior
    - `startVoiceRecorder()`: checks permissions, calls `mVoiceRecorder.start()` if `isMicAutomatic` and not muted
    - `stopVoiceRecorder()`: calls `mVoiceRecorder.stop()` if automatic mode
    - `endVoice()`: forces end of current voice segment
    - Abstract `initializeVoiceRecorder()`: subclasses create the `Recorder` with their specific callbacks
  - **TTS management**:
    - `tts` (nullable `TTS` wrapper around Android `TextToSpeech`)
    - `speak(String, CustomLocale)`: queues speech, increments `utterancesCurrentlySpeaking`, stops mic during TTS (calls `stopVoiceRecorder()` + `notifyMicDeactivated()`), restarts mic when all utterances done (in `UtteranceProgressListener.onDone()`)
    - `shouldDeactivateMicDuringTTS()`: returns true by default, overridden by `ConversationService` to return `!isBluetoothHeadsetConnected()` (allows simultaneous listening on BT headset)
  - **WakeLock**: acquires `PARTIAL_WAKE_LOCK` with 10-min timeout, reacquires on a timer to keep alive indefinitely
  - **Foreground service**: `onStartCommand()` starts foreground with the notification passed via Intent
  - **Message history**: `ArrayList<GuiMessage> messages` -- `addOrUpdateMessage()` keeps a running log so fragments can restore state
  - **State flags**: `isMicMute`, `isAudioMute`, `isEditTextOpen`, `isMicActivated`, `isMicAutomatic`, `manualRecognizingFirstLanguage`, `manualRecognizingSecondLanguage`, `manualRecognizingAutoLanguage`
  - **Commands handled** (via `executeCommand()`): `START_MIC`, `STOP_MIC`, `START_SOUND`, `STOP_SOUND`, `SET_EDIT_TEXT_OPEN`, `GET_ATTRIBUTES` (returns full state snapshot to client)
  - **Client notifications**: `notifyVoiceStart()`, `notifyVoiceEnd()`, `notifyVolumeLevel()`, `notifyMicActivated()`, `notifyMicDeactivated()`, `notifyMessage()`
  - **Cleanup** (`onDestroy()`): stops recorder, destroys it, stops TTS, stops foreground, releases WakeLock
  - **Inner classes**:
    - `VoiceTranslationServiceCommunicator` (abstract, extends `ServiceCommunicator`): client-side handler that receives callbacks from service via `Messenger`, dispatches to `VoiceTranslationServiceCallback` listeners
    - `VoiceTranslationServiceCallback` (abstract): callback interface with `onVoiceStarted(mode)`, `onVoiceEnded()`, `onVolumeLevel()`, `onMicActivated()`, `onMicDeactivated()`, `onMessage()`, `onBluetoothHeadsetConnected()`, `onBluetoothHeadsetDisconnected()`
    - `AttributesListener`: one-shot listener for `GET_ATTRIBUTES` response
    - `VoiceTranslationServiceRecognizerListener`: abstract `RecognizerListener` that routes errors to `notifyError()`

- **`WalkieTalkieService`** (extends `VoiceTranslationService`):
  - Single-device translate-and-speak mode
  - See section 2.5 for detailed flow

- **`ConversationService`** (extends `VoiceTranslationService`):
  - Bluetooth peer-to-peer conversation mode
  - See section 2.6 for overview

### 2.4 Fragment-Service Communication (ServiceCommunicator Pattern)

- **Binding**: Fragment calls `activity.connectToWalkieTalkieService(callback, listener)` which:
  1. Starts the service via `startService(intent)` with language extras
  2. Creates a `CustomServiceConnection` wrapping a `WalkieTalkieServiceCommunicator`
  3. Calls `bindService()` with `BIND_ABOVE_CLIENT`
  4. On bind, `ServiceCommunicator.initializeCommunication()` sends `INITIALIZE_COMMUNICATION` command with client's `Messenger` to the service
  5. Calls `ServiceCommunicatorListener.onServiceCommunicator()` giving fragment the communicator
- **Fragment -> Service**: `ServiceCommunicator.sendToService(Bundle)` sends commands via `Messenger` with `bundle.putInt("command", CMD_ID)`
- **Service -> Fragment**: Service calls `notifyToClient(Bundle)` with `bundle.putInt("callback", CALLBACK_ID)`, received by `ServiceCommunicator.serviceHandler` which dispatches to `executeCallback()`, which calls registered `VoiceTranslationServiceCallback` methods
- **State restoration**: After binding, fragment calls `communicator.getAttributes(AttributesListener)` to get full service state (messages, mic mute, audio mute, TTS error, etc.) and rebuild UI
- **Unbinding**: Fragment's `onStop()` calls `activity.disconnectFromWalkieTalkieService(communicator)`, which unbinds and removes the connection
- **Multiple clients**: Activity maintains `ArrayList<CustomServiceConnection>` for each service type, identified by `connectionId`

### 2.5 WalkieTalkie Mode (Most Important for VoxSwap)

**Overview**: Single-device mode. User speaks in Language A, app translates and speaks aloud in Language B (and vice versa). Two languages configured: `firstLanguage` and `secondLanguage`.

**Fragment** (`WalkieTalkieFragment`, extends `VoiceTranslationFragment`):
- Three mic buttons: center (auto-detect), left (firstLanguage), right (secondLanguage)
- Two language selectors that open a language list dialog
- **Auto mode** (default, `isMicAutomatic=true`): center mic button controls start/stop. Voice auto-detected, language auto-determined from both recognizer results
- **Manual mode** (`isMicAutomatic=false`): left/right mic buttons with press-to-talk. Short press toggles, long press is push-to-talk (release stops). Sends `startRecognizingFirstLanguage()` / `startRecognizingSecondLanguage()` commands to service
- Switching between modes: clicking left/right mic when in auto mode calls `switchMicMode(false)` which sends `startManualRecognition()` to service. Clicking center mic calls `switchMicMode(true)` which sends `stopManualRecognition()`
- On `connectToService()`: binds to `WalkieTalkieService`, restores state, sets up language selectors
- Language changes: calls `walkieTalkieServiceCommunicator.changeFirstLanguage(language)` and saves to `Global`
- `WalkieTalkieServiceCallback`: routes `onVoiceStarted(mode)` to correct mic button animation, `onMessage()` adds/updates messages in RecyclerView

**Service** (`WalkieTalkieService`, extends `VoiceTranslationService`):
- `SPEECH_BEAM_SIZE=1`, `TRANSLATOR_BEAM_SIZE=1` (fast, single-best results)
- **Initialization** (`onCreate()`):
  - Gets `Translator` and `Recognizer` from `Global`
  - Sets up `clientHandler` to process commands from fragment
  - Sets up `mVoiceCallback` (`Recorder.SimpleCallback`) for VAD events
  - Sets up `speechRecognizerCallback` (`RecognizerMultiListener`) for dual-language recognition results
  - Sets up `speechRecognizerSingleCallback` (`RecognizerListener`) for single-language manual recognition
  - Sets up `firstResultTranslateListener` and `secondResultTranslateListener` for translation results
  - Creates `Recorder` via `initializeVoiceRecorder()` -- passes `mVoiceCallback`, no BT headset support (null)
- **Languages**: received via Intent extras in `onStartCommand()`, stored as `firstLanguage` and `secondLanguage`
- **Full audio flow (auto mode)**:
  1. `Recorder` detects voice via amplitude threshold -> `mVoiceCallback.onVoiceStart()` -> notifies fragment (mic animation)
  2. `Recorder` captures audio -> `mVoiceCallback.onVoice(float[] data, int size)` -> stops recorder, notifies mic deactivated
  3. Calls `speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, firstLanguage.getCode(), secondLanguage.getCode())` -- recognizes in BOTH languages simultaneously
  4. `speechRecognizerCallback.onSpeechRecognizedResult(text1, lang1, conf1, text2, lang2, conf2)` -> calls `compareResults()`
  5. `compareResults()` uses `translator.detectLanguage()` to determine which recognition result matches which language, then picks the best one based on language match and confidence score
  6. Calls `translate(text, sourceLang, targetLang, beamSize, false, translateListener)` -- skips if text is "meta text" (starts with `[` or `(`)
  7. `translator.translate()` runs NLLB-200 -> `translateListener.onTranslatedText(original, translated, resultID, isFinal, lang)`
  8. If `isFinal` and TTS language is supported -> `speak(translated, targetLang)` (Android TTS speaks aloud)
  9. Creates `GuiMessage` with original + translated text, notifies fragment, saves to message history
  10. When TTS finishes (or if TTS unavailable/muted) -> restarts recorder, notifies mic activated
- **Full audio flow (manual mode)**:
  1. Fragment sends `START_MANUAL_RECOGNITION` -> service sets `isMicAutomatic=false`, `mVoiceRecorder.setManualMode(true)`
  2. Fragment sends `START_RECOGNIZING_FIRST_LANGUAGE` -> `manualRecognizingFirstLanguage=true`, `mVoiceRecorder.startRecording()`
  3. `mVoiceCallback.onVoice()` checks flags -> calls `speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, firstLanguage.getCode())` (single language only)
  4. `speechRecognizerSingleCallback.onSpeechRecognizedResult()` -> calls `translate(text, firstLanguage, secondLanguage, ...)`
  5. Rest is same as auto mode (translate -> TTS -> notify)
- **Text input**: Fragment sends `RECEIVE_TEXT` -> service calls `translator.detectLanguage()` on the text to determine direction, then translates
- **Additional commands**: `CHANGE_FIRST_LANGUAGE`, `CHANGE_SECOND_LANGUAGE`, `GET_FIRST_LANGUAGE`, `GET_SECOND_LANGUAGE`, `START/STOP_MANUAL_RECOGNITION`, `START/STOP_RECOGNIZING_FIRST/SECOND/AUTO_LANGUAGE`

### 2.6 Conversation Mode (Brief)

- **Purpose**: Two phones communicate via Bluetooth (BLE via `ConversationBluetoothCommunicator`). Each user speaks their language, messages are translated on the receiving side.
- **Entry**: User goes to `PairingFragment` (BT peer discovery/connection), on successful connection -> switches to `ConversationFragment`
- **`PairingFragment`** (extends `PairingToolbarFragment`):
  - BT discovery: starts search via `activity.startSearch()`, shows found peers in `PeerListAdapter`
  - Connection: click peer -> confirm dialog -> `activity.connect(peer)` with 5-second timeout
  - On connection success -> `activity.setFragment(CONVERSATION_FRAGMENT)`
  - Manages recent peers via `RecentPeersDataManager`
- **`ConversationFragment`** (extends `PairingToolbarFragment`):
  - Contains a `ViewPager` with `CustomFragmentPagerAdapter` and 2 tabs: "Conversation" (`ConversationMainFragment`) and "Connection" (`PeersInfoFragment`)
  - Tab switching animated with custom tab UI (MaterialCardView tabs, not TabLayout)
  - Search button visible only on Connection tab
- **`ConversationMainFragment`** (extends `VoiceTranslationFragment`):
  - Similar to WalkieTalkie but: only one mic (auto-detect), has keyboard button for text input via EditText
  - Binds to `ConversationService`, same restore-attributes pattern
  - Mic button has 3 states: `STATE_NORMAL` (mic toggle), `STATE_RETURN` (close EditText), `STATE_SEND` (send typed text)
- **`ConversationService`** (extends `VoiceTranslationService`):
  - Uses `Global.getLanguage()` (single device language) for STT, not first/second language pair
  - `Recorder` created with `useBluetoothSco=true` (second arg), has `BluetoothHeadsetCallback`
  - `shouldDeactivateMicDuringTTS()` returns `!isBluetoothHeadsetConnected()` -- allows listening during TTS on BT headset
  - **Speech flow**: Mic -> Recorder -> `mVoiceCallback.onVoice()` -> `speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, language.getCode())` (single language) -> `onSpeechRecognizedResult()` -> if `isFinal`, sends text via BT: `global.getBluetoothCommunicator().sendMessage()` (text + language code appended)
  - **Receive flow**: `communicationCallback.onMessageReceived()` -> parses language code from end of message -> `translator.translateMessage()` -> TTS speaks translated text -> notifies fragment with GuiMessage
  - **ConversationMessage**: Parcelable wrapper with `Peer sender` + `NeuralNetworkApiText payload` (text + language)
  - Service stops itself when all peers disconnect (`peersLeft == 0`)

### 2.7 Text Translation Mode (Brief)

- **`TranslationFragment`** (extends `Fragment` directly, NOT `VoiceTranslationFragment`):
  - No service -- runs translation directly via `global.getTranslator().translate()`
  - Input EditText + output EditText, "Translate" button, language selectors with invert button
  - Uses separate language pair: `firstTextLanguage` / `secondTextLanguage` (persisted independently)
  - Has TTS playback for both input and output text (`ttsInputButton`, `ttsOutputButton`)
  - Copy-to-clipboard, cancel (clear) buttons for both fields
  - `beamSize` configurable via settings (default `DEFAULT_BEAM_SIZE=1`, max `MAX_BEAM_SIZE=6`)
  - No auto-detect: user explicitly picks source and target languages
  - FABs at bottom navigate to WalkieTalkie mode (`setFragment(WALKIE_TALKIE_FRAGMENT)`) or Conversation pairing (`setFragment(PAIRING_FRAGMENT)`)
  - Handles keyboard show/hide with animated GUI compression (hides action buttons, compresses toolbar)
  - Restores last input/output text from `Translator.getLastInputText()` / `getLastOutputText()`

### 2.8 Tab Navigation (Fragment Switching)

- NOT using a ViewPager at the Activity level -- `VoiceTranslationActivity.setFragment(int)` does `FragmentTransaction.replace()` on a single `CoordinatorLayout` container
- 4 possible fragments: `PairingFragment`, `ConversationFragment`, `WalkieTalkieFragment`, `TranslationFragment`
- Default fragment is `TRANSLATION_FRAGMENT` (text translation is the home screen)
- `TranslationFragment` has FABs (Walkie Talkie button, Conversation button) to navigate to other modes
- Current fragment index saved to SharedPreferences, restored on `onStart()`
- When switching away from voice modes, relevant services are stopped (`stopConversationService()`, `stopWalkieTalkieService()`)
- The **only ViewPager** is inside `ConversationFragment`, which uses `CustomFragmentPagerAdapter` to host `ConversationMainFragment` (tab 0) and `PeersInfoFragment` (tab 1)
- Back button behavior: WalkieTalkie/Pairing -> goes to DEFAULT_FRAGMENT. Conversation -> shows confirm-exit dialog, disconnects BT peers

### 2.9 Language Selection

- **Voice modes (WalkieTalkie)**: Two languages, `firstLanguage` and `secondLanguage`, stored on `Global`, persisted to SharedPreferences
  - Selected via dialog (`showLanguageListDialog()`) in `WalkieTalkieFragment`
  - Language list = intersection of `Translator.getSupportedLanguages()` (NLLB) and `Recognizer.getSupportedLanguages()` (Whisper)
  - Changes sent to service via `walkieTalkieServiceCommunicator.changeFirstLanguage()`
  - Auto-detect: in auto mode, Whisper recognizes in both languages simultaneously, `compareResults()` picks the best match by language match + confidence score
- **Voice modes (Conversation)**: Single language per device, `Global.language`, represents what the local user speaks. Translation direction determined by comparing local vs remote language
- **Text mode**: Separate pair `firstTextLanguage` / `secondTextLanguage`, only needs translator support (no STT required), so uses `Global.getTranslatorLanguages()` which is a superset of voice-mode languages
- **Defaults**: Falls back to device locale (`CustomLocale.getDefault()`), then to `"en"` if not in supported list

### 2.10 Audio Flow (WalkieTalkie, End-to-End)

```
Mic (AudioRecord)
  -> Recorder (VAD via amplitude threshold + speech timeout)
    -> mVoiceCallback.onVoice(float[] data, int size)
      -> Recognizer.recognize(data, beamSize, languageCodes...)
        [Whisper ONNX inference]
        -> RecognizerMultiListener.onSpeechRecognizedResult(text1, lang1, conf1, text2, lang2, conf2)
          -> Translator.detectLanguage() picks best result
            -> Translator.translate(text, srcLang, tgtLang, beamSize)
              [NLLB-200 ONNX inference]
              -> TranslateListener.onTranslatedText(original, translated, resultID, isFinal, lang)
                -> VoiceTranslationService.speak(translated, lang)
                  [Android TextToSpeech speaks aloud]
                -> notifyMessage(GuiMessage) -> Fragment updates RecyclerView
                -> When TTS done: startVoiceRecorder() -> cycle repeats
```

Key points:
- Mic is stopped during STT+Translation+TTS to avoid feedback loop (except in Conversation mode with BT headset)
- `Recorder` handles VAD via `amplitudeThreshold` (derived from `micSensitivity` setting) and `speechTimeout`
- `Recognizer` runs Whisper with configurable `beamSize` (1 for WalkieTalkie, 4 for Conversation)
- `Translator` runs NLLB-200 with `beamSize=1`
- TTS is Android system `TextToSpeech` (Google TTS), language set per utterance
- Messages tracked by `resultID` -- non-final results update existing message in RecyclerView, final results are permanently saved
- The flow is fully serial: mic -> STT -> translate -> TTS -> mic (no parallel pipeline)

---

## 3. Bluetooth, Tools & Infrastructure

### 3.1 Bluetooth Communication (P2P via BLE)

**Overview**: RTranslator uses a custom BLE library (not classic Bluetooth) for peer-to-peer communication in Conversation mode. Each device acts as both GATT client and GATT server simultaneously.

**`BluetoothCommunicator`** -- top-level API class:
- Constructor takes a name (max 18 chars) + strategy (`STRATEGY_P2P_WITH_RECONNECTION`)
- Appends a 2-char random ID to the name for unique identification (`BluetoothTools.generateBluetoothNameId()`) -- persisted in SharedPreferences
- Creates both a `BluetoothConnectionClient` and `BluetoothConnectionServer` in `initializeConnection()`
- **Advertising**: uses `BluetoothLeAdvertiser` with `ADVERTISE_MODE_LOW_LATENCY`, `TX_POWER_HIGH`, connectable, service UUID `00001234-0000-1000-8000-00805F9B34FB`
- **Discovery**: uses `BluetoothLeScanner` with `SCAN_MODE_LOW_LATENCY`, filtered by service UUID
- Temporarily renames the device's BT adapter name to `uniqueName` during advertising (restores on stop)
- Manages BT on/off lifecycle -- will enable BT if off, restores original state when done
- Message queue: separate `ArrayDeque<Message>` for pending messages and pending data, processed serially
- Callback pattern: `BluetoothCommunicator.Callback` extends `BluetoothConnection.Callback`, events include `onPeerFound`, `onConnectionRequest`, `onConnectionSuccess`, `onConnectionLost`, `onConnectionResumed`, `onMessageReceived`, `onDataReceived`, `onDisconnected`

**`BluetoothConnection`** -- abstract base for client/server:
- Defines shared UUID `APP_UUID`, MTU = 247, `SUB_MESSAGES_LENGTH` = 192
- Maintains list of `Channel` objects (one per connected peer)
- `sendMessage()` / `sendData()` iterate over channels, skipping disconnecting peers
- Tracks reconnecting peers via `getReconnectingPeers()`

**`BluetoothConnectionClient`** (GATT client side):
- Uses `BluetoothGattCallback` to handle `onConnectionStateChange`, `onServicesDiscovered`, `onMtuChanged`, `onCharacteristicChanged`, `onCharacteristicRead`, `onCharacteristicWrite`
- Connection flow: `connect()` -> `BluetoothDevice.connectGatt()` -> `onServicesDiscovered` -> MTU negotiation -> `requestConnection(uniqueName)` via writing to `CONNECTION_REQUEST_UUID` characteristic -> server responds via `CONNECTION_RESPONSE_UUID` (ACCEPT/REJECT)
- Reconnection: on connection lost, starts 30s reconnection timer, re-scans for the peer, retries `connectGatt()`
- Manages a `ConnectionDeque` for serializing pending connection requests
- Calls `refreshDeviceCache()` via reflection on each new connection (workaround for Android BLE cache issues)

**`BluetoothConnectionServer`** (GATT server side):
- Opens a `BluetoothGattServer` with a single service containing 16 characteristics for:
  - Connection request/response (`CONNECTION_REQUEST_UUID`, `CONNECTION_RESPONSE_UUID`)
  - Connection resume (reconnection handshake)
  - MTU negotiation (`MTU_REQUEST_UUID`, `MTU_RESPONSE_UUID`)
  - Message send/receive (`MESSAGE_SEND_UUID`, `MESSAGE_RECEIVE_UUID`)
  - Data send/receive (`DATA_SEND_UUID`, `DATA_RECEIVE_UUID`)
  - Read response confirmations (`READ_RESPONSE_MESSAGE_RECEIVED_UUID`, `READ_RESPONSE_DATA_RECEIVED_UUID`)
  - Name updates (`NAME_UPDATE_SEND_UUID`, `NAME_UPDATE_RECEIVE_UUID`)
  - Disconnection (`DISCONNECTION_SEND_UUID`, `DISCONNECTION_RECEIVE_UUID`)
- Handles `onCharacteristicWriteRequest` for receiving messages, data, connection requests, reconnection, name updates, disconnection
- Handles `onNotificationSent` to confirm connection acceptance, message/data send completion, disconnection

**`Channel` / `ClientChannel` / `ServerChannel`** -- per-peer connection wrapper:
- `Channel` (abstract): manages message splitting/reassembly, timers, and queues
  - Timers: `RECONNECTION_TIMEOUT` = 30s, `CONNECTION_COMPLETE_TIMEOUT` = 10s, `MESSAGE_TIMEOUT` = 1s, `NOTIFY_DISCONNECTION_TIMEOUT` = 5s, `DISCONNECTION_TIMEOUT` = 4s
  - `writeMessage()`: splits a `Message` into `BluetoothMessage` sub-messages via `Message.splitInBluetoothMessages()`, sends sequentially
  - Tracks `receivingMessages` / `receivingData` (partial), `receivedMessages` / `receivedData` (completed, for deduplication)
  - Supports pause/resume of pending messages (used when receiving to avoid collision)
- `ClientChannel`: wraps a `BluetoothGatt` object, writes to server's characteristics
- `ServerChannel`: wraps a `BluetoothGattServer`, uses `notifyCharacteristicChanged()` to send to client, tracks `sendingCharacteristic` UUID for routing `onNotificationSent`

**`BluetoothMessage`** -- chunking/reassembly:
- Header: 4-byte ID + 3-byte sequence number + 1-byte type (NON_FINAL=1 / FINAL=2) = 8 bytes header
- Payload max per sub-message: `SUB_MESSAGES_LENGTH` (192) - `TOTAL_LENGTH` (8) = 184 bytes
- `createFromBytes()`: factory for parsing received sub-messages
- `addMessage()`: appends subsequent sub-messages (by sequence number) for reassembly
- `convertInMessage()`: extracts the 1-byte header and remaining data into a `Message`
- `SequenceNumber`: uses a character-based counter over `BluetoothTools.getSupportedUTFCharacters()` (95 printable ASCII chars)

**`Message`** -- high-level message container:
- Contains a 1-char header (for message type differentiation), text or byte[] data, sender `Peer`, optional receiver `Peer`
- `splitInBluetoothMessages()`: prepends header to data, splits into chunks of 184 bytes each, assigns ID and sequential numbers
- Also has a `textToTranslate` field (used by translation pipeline)

**`Peer`** -- device representation:
- Contains `uniqueName` (name + 2-char ID), `name` (display name without ID), `BluetoothDevice`
- State flags: `isHardwareConnected`, `isConnected`, `isReconnecting`, `isRequestingReconnection`, `isDisconnecting`
- `equals()` compares by `BluetoothDevice.getAddress()` (MAC address)
- Parcelable for IPC

**`BluetoothTools`** -- utility class:
- `getSupportedUTFCharacters()`: returns 95 printable ASCII characters (space through tilde)
- `generateBluetoothNameId()`: generates and persists a 2-char random ID in SharedPreferences
- `fixLength()`: pads or truncates strings to exact length (left-pad for numbers, right-pad for text)
- `splitBytes()` / `concatBytes()` / `subBytes()`: byte array manipulation for message chunking

### 3.2 Model Download System

**`Downloader`** -- thin wrapper around Android `DownloadManager`:
- `downloadModel(url, filename)`: enqueues a download to external files dir (`getExternalFilesDir(null)`) with visible notification
- `getDownloadProgress(max)`: calculates overall progress across all downloads using `DOWNLOAD_SIZES[]` array and `COLUMN_BYTES_DOWNLOADED_SO_FAR`
- `findDownloadUrlIndex(downloadId)`: maps a download ID back to the URL index in `DOWNLOAD_URLS[]`
- `getRunningDownloadStatus()`: reads `currentDownloadId` from SharedPreferences, queries its status
- `cancelRunningDownload()`: cancels the running download (used for pause functionality)
- Progress tracking via SharedPreferences: `currentDownloadId`, `lastDownloadSuccess`, `lastTransferSuccess`, `lastTransferFailure`

**`DownloadFragment`** -- UI and orchestration:
- 10 ONNX models to download from GitHub releases (`https://github.com/niedev/RTranslator/releases/download/2.0.0/`):
  - NLLB: `NLLB_cache_initializer.onnx` (24MB), `NLLB_decoder.onnx` (171MB), `NLLB_embed_and_lm_head.onnx` (500MB), `NLLB_encoder.onnx` (254MB)
  - Whisper: `Whisper_cache_initializer.onnx` (14MB), `Whisper_cache_initializer_batch.onnx` (14MB), `Whisper_decoder.onnx` (173MB), `Whisper_detokenizer.onnx` (461KB), `Whisper_encoder.onnx` (88MB), `Whisper_initializer.onnx` (69KB)
- Total size: ~1.24 GB
- GUI updater thread polls every 100ms, updates progress bar, shows current operation description (downloading/transferring/verifying)
- Pause/resume: cancels DownloadManager download, changes button icon, restarts on resume
- Error handling: separate download error and transfer error UI with retry buttons
- Storage warning: shown if available external or internal memory < (total download size + 800MB margin)
- On all downloads complete (`currentDownloadId == -2`): calls `startRTranslator()` which launches `LoadingActivity`

**`DownloadReceiver`** -- `BroadcastReceiver` for `DOWNLOAD_COMPLETE`:
- On download success: runs integrity check via `NeuralNetworkApi.testModelIntegrity()`
- On integrity pass: calls `transferModelAndStartNextDownload()` which moves file from external to internal storage via `FileTools.moveFile()`
- Before starting next download, checks if model already exists in internal storage (skip) or external storage (transfer only)
- On integrity fail: saves `currentDownloadId = -3` (marker for failed status)
- SharedPreferences keys used: `currentDownloadId` (-1=not started, -2=all complete, -3=integrity fail, >=0=active download ID), `lastDownloadSuccess`, `lastTransferSuccess`, `lastTransferFailure`
- File paths: downloads go to `getExternalFilesDir(null)/MODEL_NAME.onnx`, then moved to `getFilesDir()/MODEL_NAME.onnx` (internal storage)

### 3.3 Access Flow (First-Run Setup Wizard)

**`AccessActivity`** -- host activity for setup fragments:
- Fragment constants: `NOTICE_FRAGMENT=1`, `USER_DATA_FRAGMENT=0`, `DOWNLOAD_FRAGMENT=2`
- On first launch: shows `NoticeFragment` -> user confirms -> `UserDataFragment` -> user enters data -> `DownloadFragment`
- On subsequent launches (name already saved): jumps directly to `DownloadFragment`
- Back navigation: `DownloadFragment` -> `UserDataFragment` -> `NoticeFragment`

**`NoticeFragment`** -- informational screen:
- Shows description text with clickable links (license/privacy notice)
- RAM check: shows error if `getTotalRamSize() <= 5000` MB (models need 6+ GB RAM)
- Creates a `Readme.txt` in external files dir (instructions for sideloading models)
- Confirm button navigates to `UserDataFragment`

**`UserDataFragment`** -- user info collection:
- Fields: username (validated against `BluetoothTools.getSupportedUTFCharacters()`), user profile image (via `GalleryImageSelector`), privacy checkbox
- Username saved via `global.setName()` (SharedPreferences)
- Button to open system TTS settings (`com.android.settings.TTS_SETTINGS`)
- On confirm: saves name + image, navigates to `DownloadFragment`

**`DownloadFragment`** -- model download + transfer (see 3.2 above)

### 3.4 Settings

**`SettingsActivity`** -- standard toolbar activity hosting `SettingsFragment`:
- Blocks back navigation while downloads are in progress (shows dialog)

**`SettingsFragment`** -- extends `PreferenceFragmentCompat`:
- Loads preferences from `R.xml.preferences`
- Custom preference types:
  - `UserImagePreference`: profile image picker via `GalleryImageSelector`
  - `UserNamePreference`: edit username
  - `LanguagePreference`: personal language selection (dialog with language list)
  - `SupportLanguagesQuality`: toggle for low-quality language support (expands available languages)
  - `SupportTtsQualityPreference`: toggle for low-quality TTS voices
  - `ShowOriginalTranscriptionMsgPreference`: toggle to show original transcription in conversation
  - `SeekBarPreference`: used for 4 different settings:
    - Mic sensibility (`MIC_SENSIBILITY_MODE`)
    - Beam size (`BEAM_SIZE_MODE`)
    - Speech timeout (`SPEECH_TIMEOUT_MODE`)
    - Previous voice duration (`PREV_VOICE_DURATION_MODE`)
  - TTS settings link: opens system TTS settings intent
- Download tracking: `addDownload()` / `removeDownload()` with progress bar visibility
- Error handling via `onFailure()`: dispatches to `notifyInternetLack()` or `notifyMissingGoogleTTSDialog()` using `Messenger` self-messaging pattern

**`LanguagePreference`** -- custom preference for language selection:
- On click: shows dialog with loading spinner, then fetches language list via `global.getLanguages(true, true, ...)`
- Displays languages with TTS availability indicator (`"(no TTS)"` suffix if TTS not available for that language)
- On selection: saves via `global.setLanguage()`, updates summary text
- Error handling: shows reload button on failure, Toast for internet errors

### 3.5 CustomLocale

**`CustomLocale`** -- wrapper around `java.util.Locale`:
- Constructors: from language code, language+country, language+country+variant, or existing `Locale`
- `getInstance(code)`: parses hyphen-separated code (e.g., `"en-US"`) into `CustomLocale`
- `getCode()`: returns `"language-country"` format (e.g., `"en-US"`)
- `getDisplayName(ttsLanguages)`: returns display name with `" (no TTS)"` suffix if language not in TTS-supported list
- Comparison:
  - `equals()`: matches on language + country (both must be present)
  - `equalsLanguage()`: matches on ISO3 language code only (looser match)
  - `compareTo()`: alphabetical by display name
- `containsLanguage()`: checks if any locale in a list matches by language
- `search()`: first tries exact match, then language-only match
- **Note**: Unlike VoxSwap's model, this does NOT store NLLB/Whisper-specific language codes. Language code mapping happens elsewhere (in `NeuralNetworkApi` / translation pipeline). This is purely a `java.util.Locale` wrapper for UI display.

### 3.6 ServiceCommunicator (IPC Pattern)

**`ServiceCommunicator`** (abstract):
- Uses Android `Messenger` + `Handler` for Activity-to-Service IPC
- `initializeCommunication(Messenger serviceMessenger)`: stores the Service's messenger for sending
- `sendToService(Bundle)`: wraps Bundle in a `Message` and sends via `Messenger.send()`
- `isCommunicating()`: checks if serviceMessenger is non-null
- Has an `id` field for identifying communicator instances
- Abstract methods: `addCallback(ServiceCallback)`, `removeCallback(ServiceCallback)`

**`ServiceCallback`** (abstract):
- Single method: `onError(int[] reasons, long value)` -- error reporting from service to activity

**`ServiceCommunicatorListener`** (abstract):
- `onServiceCommunicator(ServiceCommunicator)`: called when a ServiceCommunicator is ready
- `onFailure(int[] reasons, long value)`: called on initialization failure

**Pattern usage**: Activities bind to Services (e.g., `VoiceTranslationService`), obtain a `Messenger`, pass it to a `ServiceCommunicator` subclass. The communicator sends commands as `Bundle`s to the service. The service handler dispatches commands and sends results back via callbacks.

### 3.7 FileManager

**`FileManager`** -- marked `/** Not used **/` in source:
- Simple Java serialization wrapper: `getObjectFromFile()` / `setObjectInFile()`
- Uses `ObjectInputStream` / `ObjectOutputStream` for reading/writing serialized objects
- Operates on a given directory
- **Not actively used** -- model file management is handled by `FileTools.moveFile()` and direct `File` operations in `DownloadReceiver`

**Actual model file management** (in `DownloadReceiver` / `DownloadFragment`):
- Download destination: `context.getExternalFilesDir(null)/MODEL_NAME.onnx` (external app-specific storage)
- Final destination: `context.getFilesDir()/MODEL_NAME.onnx` (internal app storage)
- Transfer: `FileTools.moveFile(from, to, callback)` -- copies then deletes source
- Integrity check: `NeuralNetworkApi.testModelIntegrity(path, listener)` -- attempts to load the ONNX model to verify it is not corrupted

### 3.8 Database (Room)

**`AppDatabase`** -- Room database (version 1):
- Single entity: `RecentPeerEntity`
- Single DAO: `MyDao`
- Only used for Conversation mode (remembering recently connected peers)

**`RecentPeerEntity`**:
- `deviceId` (PrimaryKey, String) -- BT device MAC address
- `uniqueName` (String) -- peer's unique BLE name
- `userImage` (byte[], BLOB) -- peer's profile image
- `getRecentPeer()`: converts to domain object `RecentPeer`

**`MyDao`**:
- `insertRecentPeers()`: upsert (REPLACE on conflict)
- `updateRecentPeers()`: update existing
- `deleteRecentPeers()`: delete
- `loadRecentPeers()`: load all
- `loadRecentPeer(deviceId)`: lookup by MAC address
- `loadRecentPeerByName(uniqueName)`: lookup by BLE unique name

### 3.9 Error Codes

**`ErrorCodes`** -- static constants for error reporting:

| Category | Code | Constant | Meaning |
|----------|------|----------|---------|
| General | 5 | `MISSED_CONNECTION` | No internet connection |
| General | 13 | `ERROR` | Generic error |
| Local | 0 | `MISSED_ARGUMENT` | Missing required argument |
| Local | 1 | `MISSED_CREDENTIALS` | Missing credentials |
| Local | 11 | `MAX_CREDIT_OFFET_REACHED` | Credit offset limit reached |
| SafetyNet | 8 | `SAFETY_NET_EXCEPTION` | SafetyNet attestation error |
| SafetyNet | 9 | `MISSING_PLAY_SERVICES` | Google Play Services missing |
| TTS | 100 | `GOOGLE_TTS_ERROR` | Google TTS engine error |
| TTS | 101 | `MISSING_GOOGLE_TTS` | Google TTS not installed |
| Neural Networks | 34 | `ERROR_LOADING_MODEL` | ONNX model load failure |
| Neural Networks | 35 | `ERROR_EXECUTING_MODEL` | ONNX model inference failure |
| Language ID | 15 | `LANGUAGE_UNKNOWN` | Could not identify language |
| Language ID | 16-19 | `FIRST/SECOND/BOTH_RESULTS_FAIL/SUCCESS` | Language identification result combinations |

Used throughout the app in `onError(int[] reasons, long value)` callbacks. Multiple error codes can be reported simultaneously via the `int[]` array.

---

## 4. Build Config, Resources & Native Code

### 4.1 Gradle Build Config

- **AGP**: `com.android.tools.build:gradle:8.2.2`
- **compileSdkVersion**: 33
- **targetSdkVersion**: 32
- **minSdkVersion**: 24
- **versionCode**: 25, **versionName**: `2.1.5`
- **applicationId**: `nie.translator.rtranslator`
- **namespace**: `nie.translator.rtranslator`
- **NDK ABI filter**: `arm64-v8a` only (no x86, no 32-bit ARM)
- **CMake version**: `3.22.1`, path: `src/main/cpp/CMakeLists.txt`
- **Build types**:
  - `debug`: minifyEnabled=false, multiDexEnabled=true, debuggable=true
  - `release`: minifyEnabled=false, shrinkResources=false (ProGuard effectively disabled), signed with debug key
- **Java compatibility**: Not explicitly set (defaults to source/target compat from AGP 8.2 = Java 8)
- **gradle.properties**: AndroidX enabled, Jetifier enabled, JVM heap 2048M, buildconfig enabled, nonFinalResIds=false, nonTransitiveRClass=false
- **Repositories**: google(), jcenter(), jitpack.io, maven.google.com

**Dependencies**:
- `com.google.android.material:material:1.9.0` (Material Components)
- `androidx.cardview:cardview:1.0.0`
- `androidx.recyclerview:recyclerview:1.0.0`
- `androidx.constraintlayout:constraintlayout:1.1.3`
- `androidx.preference:preference:1.1.0-alpha02`
- `androidx.core:core-splashscreen:1.0.1`
- `androidx.lifecycle:lifecycle-extensions:2.2.0`
- `androidx.work:work-runtime:2.7.1`
- `androidx.exifinterface:exifinterface:1.3.7`
- `com.microsoft.onnxruntime:onnxruntime-android:1.19.0` (ONNX Runtime for inference)
- `com.microsoft.onnxruntime:onnxruntime-extensions-android:0.12.4` (ONNX Runtime extensions)
- `com.google.mlkit:language-id:17.0.5` (ML Kit language identification)
- `com.nimbusds:nimbus-jose-jwt:5.1` (JWT/JWS parsing)
- `androidx.room:room-runtime:2.1.0` + room-compiler, room-rxjava2, room-guava (Room DB)
- Test: JUnit 4.12, Espresso 3.2.0

### 4.2 AndroidManifest

**Permissions (14 declared)**:
- `ACCESS_NETWORK_STATE`, `INTERNET` (network)
- `SEND_DOWNLOAD_COMPLETED_INTENTS`, `BROADCAST_STICKY` (download management)
- `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` (microphone/audio)
- `FOREGROUND_SERVICE` (background operation)
- `BLUETOOTH` (maxSdk 30), `BLUETOOTH_ADMIN` (maxSdk 30), `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Bluetooth for Conversation mode)
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (required for Bluetooth scanning)
- `WAKE_LOCK` (keep CPU awake)

**Application attributes**:
- `android:largeHeap="true"` (critical for loading ML models)
- `android:name="nie.translator.rtranslator.Global"` (custom Application class)
- `android:requestLegacyExternalStorage="true"` (scoped storage compat)
- `android:allowBackup="false"`
- Theme: `@style/Theme.Speech`

**Activities (6)**:
- `LoadingActivity` (launcher, singleTask, noHistory, splash screen theme `Theme.App.Starting`) -- MAIN intent filter
- `VoiceTranslationActivity` (singleTask, handles orientation/screenSize changes, `Theme.Speech`) -- main translation UI
- `SettingsActivity` (singleTask, `Theme.Settings`)
- `AccessActivity` (singleTask, `Theme.Speech`) -- onboarding/permissions
- `GeneralActivity` (no special config)
- `ImageActivity` (singleTask, `Theme.Speech`)

**Services (3)**:
- `ConversationService` (foregroundServiceType=`microphone`) -- Conversation mode
- `WalkieTalkieService` (foregroundServiceType=`microphone`) -- WalkieTalkie mode
- `GeneralService` (no foreground type)

**Other**:
- `FileProvider` (authority `com.gallery.RTranslator.2.0.provider`, for sharing files)
- `DownloadReceiver` (BroadcastReceiver for `DOWNLOAD_COMPLETE` intent, handles model download completion)
- Queries for `android.intent.action.TTS_SERVICE` (TTS engine discovery)
- Samsung multi-window library (optional)

### 4.3 Key Layouts

**`fragment_walkie_talkie.xml`** -- Main WalkieTalkie UI:
- Top toolbar: back button, title "WalkieTalkie", sound toggle button, settings button
- Middle section: 2 language selector cards (`firstLanguageSelector`, `secondLanguageSelector`) in dark green `CardView`s with `AnimatedTextView` for language names, separated by guidelines at 13%/43%/57%/87% of screen width
- Bottom card (MaterialCardView with rounded top corners): includes `fragment_voice_translation.xml` (messages area) + bottom bar with 3 mic buttons:
  - `leftMic` (56dp, `buttonMicLeft`) -- first language
  - `centralMic` (66dp, `buttonMic`, green background) -- "Automatic" detection, larger/primary
  - `rightMic` (56dp, `buttonMicRight`) -- second language
  - Each mic button has 3 audio level indicator bars (left/center/right lines, initially `gone`)
  - Custom `ButtonMic` widget, labels below each button showing language name

**`fragment_voice_translation.xml`** -- Message display area:
- `RecyclerView` with `LinearLayoutManager` for chat-style message bubbles (using `preview_messages` item layout)
- `TextView` for description text (shown when no messages)
- Initially RecyclerView is `gone`, description is visible

**`activity_main.xml`** -- Just a `CoordinatorLayout` as a fragment container (empty shell for fragments)

**`activity_loading.xml`** -- Splash/loading screen:
- Centered 160x160dp `ImageView` showing `app_icon_vector`

**`activity_access.xml`** -- Onboarding container:
- `FrameLayout` (`fragment_initialization_container`) for hosting setup fragments

**`fragment_download.xml`** -- Model download screen:
- Title "Download of models"
- Description text
- `LinearProgressIndicator` (Material, green themed, 6dp track, 4dp corner radius)
- Progress description text (e.g., "Downloading Whisper...")
- Progress numbers text (e.g., "0.51 / 1.2 GB")
- Pause/cancel button (cancel icon)
- Retry button (reload icon, initially `gone`)
- Error text fields for download and transfer errors (red, initially `gone`)
- Low storage warning text (initially `gone`)

### 4.4 Language Resources

All in `res/raw/`, custom XML format with `<languages>` root and `<language>` children.

- **`nllb_supported_languages.xml`**: **31 languages** -- each entry has `<code>` (ISO 639-1, e.g., "ar") and `<code_NLLB>` (NLLB BCP-47 style, e.g., "arb_Arab"). Languages include: ar, bg, ca, zh, cs, da, de, el, en, es, fi, tl, fr, gl, hr, it, ja, ko, mk, nl, pl, pt, ro, ru, sk, sv, ta, th, tr, uk, ur, vi
- **`whisper_supported_languages.xml`**: **31 languages** -- each entry has only `<code>` (ISO 639-1). Same set as NLLB minus tl (Filipino), plus ms (Malay) and no (Norwegian)
- **`madlad_supported_launguages.xml`** (typo in filename): **33 languages** -- each entry has only `<code>`. Same as Whisper set plus tl (Filipino) and id (Indonesian)
- The intersection of NLLB and Whisper lists determines which languages are usable end-to-end (STT + translation)

### 4.5 Styles & Theming

Green Material theme based on `Theme.MaterialComponents.Light.NoActionBar`:

- **`Theme.Speech`** (main app theme): primary=#43a047 (green), primaryDark=#388e3c, accent=#36b024, transparent status bar with light status bar icons, custom `CardView` style, preference theme overlay
- **`Theme.Settings`**: same green palette, but status bar color = `primary_very_lite` (#E6F8E6)
- **`Theme.App.Starting`** (splash): white background, splash icon `splash_screen2`, post-splash transitions to `Theme.Speech`
- **`Theme.Toolbar`**: AppCompat dark action bar with `primary_very_dark` text colors
- **`Theme.TabLayout`**: 13sp, sans-serif-light bold, all caps
- **`CustomCardViewStyle`**: rounded top corners (24dp), square bottom corners (0dp) -- used for the bottom message card in WalkieTalkie
- **`AppTheme`**: empty MaterialComponents.Light.NoActionBar (unused/minimal)

### 4.6 Colors

Green-centric palette:
- **Primary greens**: primary `#43a047`, primary_dark `#388e3c`, primary_very_dark `#296B2C`, primary_lite `#66bb6a`, primary_very_lite `#E6F8E6`
- **Accent**: `#36b024` (bright green)
- **Secondary**: `#ff0099cc` (cyan), secondaryTransparent `#320099cc` (~20% alpha)
- **Grays** (10 shades): black `#212121`, light_black `#404040`, very_very_dark_gray `#5A5A5A`, very_dark_gray `#757575`, dark_gray `#8C8C8C`, gray `#9e9e9e`, light_gray `#bdbdbd`, very_light_gray `#e0e0e0`, very_light_gray2 `#E9E9E9`, very_very_light_gray `#F5F5F5`
- **Whites**: white `#fafafa`, accent_white `#ffffff`
- **Semantic**: red `#d50000`, yellow `#ffd600`, green `#00c853`
- **BT discovery backgrounds**: `#5a43a047` (green at ~35% alpha)

### 4.7 C++ Native Code (SentencePiece via JNI)

The entire `app/src/main/cpp/` directory is a **vendored copy of Google's SentencePiece library** (C++17), built via CMake. Three custom JNI source files are added to the SentencePiece shared library build:

**`SentencePieceProcessorInterface.cpp`** -- JNI bridge for NLLB tokenization:
- `SentencePieceProcessorNative()` -- creates a new `SentencePieceProcessor` instance, returns pointer as `jlong`
- `LoadNative(processor, vocab_file)` -- loads a `.model` vocab file
- `encodeNative(processor, text)` -- tokenizes a string into int[] token IDs
- `PieceToIDNative(processor, token)` -- converts a single token string to its ID
- `IDToPieceNative(processor, id)` -- converts a token ID back to its string
- `decodeNative(processor, ids)` -- decodes int[] IDs back to a string (note: decode has a bug with uninitialized pointer, not used in practice)

Java side: `SentencePieceProcessorJava.java` loads `System.loadLibrary("sentencepiece")`, holds a native pointer, and wraps all JNI calls. Special tokens (`<s>`, `<pad>`, `</s>`, `<unk>`) are handled manually in Java with +1 offset on PieceToID.

**`CacheContainerNative.cpp`** -- JNI bridge for NLLB decoder KV-cache management:
- `initialize(dim1, dim2, dim3, dim4, dim5)` -- allocates a 5D cache container (dims: 32*2 layers x batch_size x 16 heads x seq_len x 128 hidden)
- `insertValues(pointer, index, data)` -- inserts a `DirectByteBuffer` at a cache index
- `reorder(pointer, indexes)` -- reorders cache entries for beam search (batch reordering)
- `getBuffer(pointer, index)` -- returns a `DirectByteBuffer` for a cache slot
- `close(pointer)` -- frees the native cache memory
- This manages the NLLB decoder's key-value cache in native memory for performance, avoiding Java heap pressure

**`NNAPITest.cpp`** -- Stub for NNAPI device enumeration (mostly commented out, returns empty string). Not actively used.

**Build output**: A shared library `libsentencepiece.so` (arm64-v8a only) containing SentencePiece core + internal protobuf-lite + all three JNI files, linked against Android `log` library. The inner `src/CMakeLists.txt` builds both shared and static libraries, includes protobuf-lite sources internally, and links the final `sentencepiece` target to `android` and `log` native libs.

### 4.8 Strings

Key string resources (~100+ entries):
- **App name**: "RTranslator"
- **Mode names**: "Conversation Mode", "WalkieTalkie Mode"
- **Download flow**: "Download of models", "Downloading %1$s", "Transferring %1$s in internal memory", "Checking integrity of %1$s"
- **Error messages** (extensive):
  - RAM check: "Your device has less than 6GB of RAM, the app needs at least 6GB of RAM to work"
  - Model loading: "There was an error loading the files of the models for translation and speech recognition, probably some files are corrupted"
  - Download/transfer errors with retry instructions
  - Missing permissions (location, mic, bluetooth LE)
  - TTS errors (missing engine, initialization failure)
  - Internet connectivity errors
  - Storage warnings: "Low storage space, the download may fail"
- **Notifications**: "RTranslator will work in background", "Download completed"
- **Dialogs**: connection confirmation, language selection (personal/primary/secondary), name input, delete/exit confirmation
- **Preference titles**: mic sensitivity, language quality support, speech timeout, voice anticipation duration, beam search size (beta), TTS engine selection, show original transcription
- **Descriptions**: explain Conversation mode (multi-device BT), WalkieTalkie mode (single phone), notice about Whisper/NLLB requirements (6GB+ RAM), privacy policy link, download explanation
- **Age/privacy**: "I declare that I am at least 14 years old", privacy policy checkbox with link

### 4.9 ProGuard

**Effectively unused.** `minifyEnabled` is `false` for both debug and release builds. The `proguard-rules.pro` file contains only commented-out rules for gRPC dependencies (`dontwarn` for `com.google.common`, `com.google.protobuf`, `io.grpc`, `okio`, `com.google.errorprone`) -- remnants from a previous version that used cloud APIs.

### 4.10 Important Config Values

**`dimens.xml`**:
- `spacing_tiny`: 4dp, `spacing_small`: 8dp, `spacing_medium`: 16dp
- `activity_horizontal_margin`: 16dp, `activity_vertical_margin`: 16dp
- `tablet_list_width`: 300dp, `phone_list_height`: 200dp
- `navigation_drawer_width`: 240dp

**`integers.xml`** -- Animation durations (all in ms):
- `durationAppear`: 50, `durationExpandVertically`: 100, `durationShort`: 200, `durationExtend`: 250, `durationSlideIn`/`durationStandard`/`durationRotate`: 300, `durationSlideOut`: 350, `durationLong`: 600, `durationVeryLong`: 800

**`fractions.xml`**:
- `loginDefaultVerticalBias`: 0.87 (positions login/proceed button near bottom of screen)

**`values.xml`** -- Custom styleable:
- `GraphViewXML`: declares attrs for `seriesData` (string), `seriesType` (string), `seriesTitle` (string), `android:title`, `seriesColor` (color) -- used for a cost/usage graph view (API cost tracking from v1, likely unused in v2)

**`preferences.xml`** -- Settings screen structure:
- User image preference (custom `UserImagePreference`)
- User name preference (custom `UserNamePreference`)
- Input category: mic sensitivity seekbar (`micSensibilitySetting`)
- Language category: support low quality languages toggle (`languagesNNQualityLow`), support low quality TTS toggle (`languagesQualityLow`), personal language picker
- Output category: TTS engine preference
- Advanced category: show original transcription toggle, beam search size seekbar (`BeamSizeSetting`), speech timeout seekbar (`SpeechTimeoutSetting`), previous voice duration seekbar (`PrevVoiceDurationSetting`)

---

## 5. Key Takeaways for VoxSwap

### What to Keep
- **Entire ML pipeline**: Whisper (6 ONNX sessions) + NLLB (4 ONNX sessions) + SentencePiece JNI -- proven, memory-optimized, working on 6GB+ phones
- **Model download system**: `Downloader` + `DownloadFragment` + `DownloadReceiver` -- handles 1.24GB of models with progress, pause/resume, integrity checks
- **Memory optimizations**: `NO_OPT`, no arena allocator, explicit `result.close()`, RAM-adaptive encoder settings -- critical for not OOMing
- **Audio recording**: `Recorder.java` with amplitude-based VAD, configurable thresholds, manual mode
- **Service architecture**: `VoiceTranslationService` -> `WalkieTalkieService` with foreground service, WakeLock, mic/TTS management
- **Language support**: `CustomLocale`, NLLB/Whisper language code mapping, quality filtering
- **C++ SentencePiece**: JNI tokenizer + KV-cache native memory management

### What to Remove
- **Bluetooth everything**: `bluetooth/` package (BLE communicator, channels, peers, messages) -- replacing with WiFi UDP/TCP to box
- **Conversation mode**: `_conversation_mode/` package, `ConversationService`, `PairingFragment`, `ConversationMainFragment` -- not needed
- **Text translation mode**: `TranslationFragment` -- not needed
- **Database (Room)**: `AppDatabase`, `MyDao`, `RecentPeerEntity` -- only for BT peer history
- **Google ML Kit language detection**: `com.google.mlkit:language-id` -- flaky, closed source, not needed (users pick language manually)
- **User profile**: `UserDataFragment`, `UserImagePreference`, `UserNamePreference`, `GalleryImageSelector` -- no peer-to-peer identity needed
- **SafetyNet / JWT**: `nimbus-jose-jwt` dependency, SafetyNet error codes -- legacy from cloud API era

### What to Modify
- **WalkieTalkieService**: Add second `Translator.translate()` call for 2 target languages. Currently translates to 1 language only
- **Audio output**: Replace system TTS (`TTS.java`) with WiFi UDP streaming to box (send PCM to 10.0.0.1:7701)
- **UI**: Replace green theme with VoxSwap indigo palette, redesign WalkieTalkie layout to match VoxSwap design (connection indicator, single centered mic, language bar with source → target1 + target2)
- **WalkieTalkieFragment**: Simplify from 3 mic buttons to 1 centered mic, remove auto-detect (manual language selection only)
- **AccessActivity flow**: Simplify -- skip user profile, go straight to model download
- **Settings**: Remove BT-related, beam search, profile settings. Add WiFi/box connection settings

### Architecture Insights
- The audio pipeline is **fully serial**: mic → STT → translate → TTS → mic. Mic stops during processing to avoid feedback. For VoxSwap, we keep this but output to UDP instead of TTS
- `Recorder` VAD uses amplitude threshold, not Silero VAD. Works well enough -- configurable via settings
- Whisper dual-language batch mode (batch=2) recognizes in both languages simultaneously -- useful for auto-detect but we may not need it if users pick language manually
- NLLB beam search is marked "unstable (random crashes)" -- stick with greedy (beam=1)
- All ONNX sessions share restrictive memory settings because enabling optimizations on any session causes OOM
- Models are ~1.24GB download, expand to ~1.7GB in RAM during inference. Needs 6GB+ RAM phone
- The `ServiceCommunicator` IPC pattern (Messenger + Handler) is verbose but reliable -- keep for fragment-service communication
