# Library Research — React Native Integration

Research on how each library from the plan works, API patterns, and key findings.

---

## 1. whisper.rn (STT)

**Package**: `whisper.rn` (React Native binding for whisper.cpp)
**Repo**: https://github.com/mybigday/whisper.rn

### Basic Usage

```ts
import { initWhisper } from 'whisper.rn';

/* Load model from file path (downloaded to app documents dir) */
const whisperContext = await initWhisper({
  filePath: 'file:///path/to/ggml-small.bin',
});

/* Transcribe an audio file */
const { stop, promise } = whisperContext.transcribe(
  'file:///path/to/audio.wav',
  { language: 'en' }
);
const { result } = await promise;
```

### TranscribeOptions

```ts
type TranscribeOptions = {
  language?: string;     /* 'en', 'es', 'fr', etc. or undefined for auto-detect */
  beamSize?: number;
  bestOf?: number;
  maxThreads?: number;
  translate?: boolean;   /* translate to English */
  tokenTimestamps?: boolean;
  temperature?: number;
  maxLen?: number;
  duration?: number;
  offset?: number;
  prompt?: string;
};
```

### Realtime Transcription (Built-in VAD!)

whisper.rn has a `RealtimeTranscriber` class with **built-in VAD** via `initWhisperVad()`.
This means we may NOT need a separate Silero VAD + onnxruntime-react-native for VAD.

```ts
import { initWhisper, initWhisperVad } from 'whisper.rn';
import { RealtimeTranscriber } from 'whisper.rn/realtime-transcription';
import { AudioPcmStreamAdapter } from 'whisper.rn/realtime-transcription/adapters';
import RNFS from 'react-native-fs';

const whisperContext = await initWhisper({ filePath: modelPath });
const vadContext = await initWhisperVad({ /* VAD options */ });
const audioStream = new AudioPcmStreamAdapter();
/* requires @fugood/react-native-audio-pcm-stream */

const transcriber = new RealtimeTranscriber(
  { whisperContext, vadContext, audioStream, fs: RNFS },
  {
    audioSliceSec: 30,
    vadPreset: 'default',
    autoSliceOnSpeechEnd: true,
    transcribeOptions: { language: 'en' },
  },
  {
    onTranscribe: (event) => {
      /* event.type: 'start' | 'transcribe' | 'end' | 'error' */
      /* event.data?.result — transcription text */
      /* event.processTime — ms to process */
      /* event.sliceIndex — current slice */
    },
    onVad: (event) => {
      /* event.type, event.confidence */
    },
    onStatusChange: (isActive) => { /* recording active/inactive */ },
    onError: (error) => { /* error string */ },
  },
);

await transcriber.start();
/* ... */
await transcriber.stop();
```

### RealtimeTranscriber Controls

- `transcriber.start()` / `transcriber.stop()` — start/stop
- `transcriber.reset()` — reset internal state
- `transcriber.updateAutoSliceOptions({ autoSliceOnSpeechEnd, autoSliceThreshold })`
- `transcriber.updateVadOptions(...)` — adjust VAD sensitivity
- `transcriber.updateCallbacks(...)` — change callbacks

### Key Notes

- Model files must be in ggml format (not ONNX). Download from HuggingFace ggml repos.
- RN packager has 2GB file limit — use quantized models (small is ~244MB, fine).
- Metro config needs `.bin` extension added to `assetExts` if bundling models as assets.
- For our use case: models downloaded at runtime, so no Metro config needed.
- Audio adapter uses `@fugood/react-native-audio-pcm-stream` (fork of react-native-live-audio-stream).

---

## 2. onnxruntime-react-native (NLLB + Silero VAD + Kokoro)

**Package**: `onnxruntime-react-native`
**Docs**: https://onnxruntime.ai/docs/get-started/with-javascript/react-native.html

### Install & Import

```ts
import { InferenceSession, Tensor } from 'onnxruntime-react-native';
```

### Load Model

```ts
const session = await InferenceSession.create(modelPath, {
  executionProviders: ['cpu'],  /* or 'coreml' on iOS, 'nnapi' on Android */
  graphOptimizationLevel: 'all',
  enableCpuMemArena: true,
  enableMemPattern: true,
  executionMode: 'sequential',
});
```

### Create Tensors & Run Inference

```ts
const inputTensor = new Tensor('float32', new Float32Array(data), [1, seqLen]);
const int64Tensor = new Tensor('int64', new Int32Array(ids), [1, ids.length]);

const feeds = { input_ids: int64Tensor, attention_mask: maskTensor };
const results = await session.run(feeds);
/* results['output_name'].data — Float32Array or similar */
```

### Key Notes

- Requires Expo prebuild + custom dev client (no Expo Go).
- Supports ONNX and ORT format models.
- Enable extensions: add `"onnxruntimeExtensionsEnabled": "true"` to root package.json.
- `int64` tensors can use `Int32Array` as fallback if BigInt64Array not available.
- CPU execution provider is most reliable. CoreML/NNAPI can be tried for speed.

---

## 3. NLLB-200 Translation (via onnxruntime-react-native)

**Reference**: RTranslator by niedev — https://github.com/niedev/RTranslator

### Model Architecture (from RTranslator maintainer)

NLLB is split into 4 ONNX files to reduce peak RAM:

| File | Purpose |
|------|---------|
| `NLLB_embed_and_lm_head.onnx` | Shared embedding matrix + language model head (same weights) |
| `NLLB_encoder.onnx` | Encoder |
| `NLLB_decoder.onnx` | Decoder with kv-cache (only "with past" variant) |
| `NLLB_cache_initializer.onnx` | Generates initial encoder kv-cache |

### Inference Flow

1. **Tokenize** input sentence with SentencePiece → `input_ids`
2. **Embed** input_ids using `NLLB_embed_and_lm_head.onnx`
3. **Encode** embeddings using `NLLB_encoder.onnx` → encoder output
4. **Initialize kv-cache** using `NLLB_cache_initializer.onnx` (encoder kv-cache)
5. **Decode iteratively** (autoregressive loop):
   - Pass: decoder kv-cache + encoder kv-cache + embedded token
   - First 3 iterations use special tokens (start, source language, target language)
   - Then each iteration: embed previous output token → decoder → lm_head → logits → next token
   - Continue until EOS token generated
6. **Detokenize** output token IDs back to text

### Tokenization

- Uses **SentencePiece** (integrated in app code, not ONNX model)
- Need a JS SentencePiece implementation for React Native
- Options: `sentencepiece-js` npm package, or custom WASM/native binding
- NLLB uses language-prefixed tokens (e.g., `eng_Latn`, `fra_Latn`, `deu_Latn`)

### Model Export

```bash
/* Export with Hugging Face Optimum */
optimum-cli export onnx --model facebook/nllb-200-distilled-600M ./nllb-onnx/

/* Quantize to INT8 */
/* Use OnnxRuntime default quantization method */
```

### Key Complexity

NLLB is the **most complex** integration:
- Autoregressive decoding loop (not a single forward pass)
- SentencePiece tokenizer needed in JS
- Model splitting for RAM optimization
- KV-cache management across iterations
- Language code mapping (NLLB uses BCP-47 variant codes like `eng_Latn`)

---

## 4. react-native-live-audio-stream (Audio Capture)

**Package**: `react-native-live-audio-stream`
**Repo**: https://github.com/mybigday/react-native-audio-pcm-stream

### Setup

```ts
import LiveAudioStream from 'react-native-live-audio-stream';
import { Buffer } from 'buffer';

await LiveAudioStream.init({
  sampleRate: 16000,       /* 16kHz for voice */
  channels: 1,             /* mono */
  bitsPerSample: 16,       /* 16-bit PCM */
  audioSource: 6,          /* VOICE_RECOGNITION (Android) */
  bufferSize: 4096,        /* bytes per chunk */
});
```

### Record & Stream

```ts
LiveAudioStream.on('data', (data: string) => {
  /* data is base64-encoded PCM chunk */
  const chunk = Buffer.from(data, 'base64');
  /* chunk is a Buffer of raw 16-bit PCM samples */
  processAudioChunk(chunk);
});

LiveAudioStream.start();
/* ... */
LiveAudioStream.stop();
```

### Permissions (Android)

```ts
const granted = await PermissionsAndroid.request(
  PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
);
```

### Key Notes

- Data arrives as **base64-encoded** PCM chunks via event emitter.
- Need `buffer` npm package for `Buffer.from()`.
- `audioSource: 6` = `VOICE_RECOGNITION` on Android (optimized for speech).
- If using whisper.rn's `RealtimeTranscriber`, it uses its own audio adapter
  (`@fugood/react-native-audio-pcm-stream`), so we might not need this separately.

---

## 5. TTS Options

### Option A: Kokoro via onnxruntime-react-native

**Reference**: https://github.com/isaiahbjork/expo-kokoro-onnx

Direct ONNX inference for Kokoro 82M model.

```ts
import { InferenceSession, Tensor } from 'onnxruntime-react-native';

/* Load model */
const session = await InferenceSession.create(modelPath);

/* Prepare inputs */
const inputs = {
  input_ids: new Tensor('int64', new Int32Array(tokens), [1, tokens.length]),
  style: new Tensor('float32', new Float32Array(styleData), [1, 256]),
  speed: new Tensor('float32', new Float32Array([1.0]), [1]),
};

/* Run inference */
const outputs = await session.run(inputs);
const waveform = outputs['waveform'].data; /* Float32Array at 24kHz */
```

**Model inputs**:
- `input_ids`: int64 tensor of phoneme token IDs [1, seq_len]
- `style`: float32 voice style vector [1, 256] — extracted from voice .bin file
- `speed`: float32 speed factor [1]

**Model output**:
- `waveform`: float32 audio samples at 24kHz

**Problem**: Requires phonemization (text → IPA phonemes → token IDs).
The expo-kokoro-onnx example has a terrible hardcoded phonemizer (only ~20 English words).
Production use needs **espeak-ng** which is not available as a JS library.

**Voice data**: Separate `.bin` files per voice from HuggingFace:
`https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/{voiceId}.bin`

### Option B: react-native-sherpa-onnx-offline-tts (Piper only)

**Package**: `react-native-sherpa-onnx-offline-tts`
**Repo**: https://github.com/kislay99/react-native-sherpa-onnx-offline-tts

Native wrapper around sherpa-onnx. Has built-in espeak-ng for phonemization.

```ts
import TTSManager from 'react-native-sherpa-onnx-offline-tts';

/* Configure with absolute paths */
const cfg = {
  modelPath: `${basePath}/en_US-ryan-medium.onnx`,
  tokensPath: `${basePath}/tokens.txt`,
  dataDirPath: `${basePath}/espeak-ng-data`,
};

/* Initialize (once per session) */
await TTSManager.initialize(JSON.stringify(cfg));

/* Generate and play */
await TTSManager.generateAndPlay(text, speakerId, speed);

/* Or generate and save to file */
const filePath = await TTSManager.generateAndSave(text);

/* Volume listener */
const sub = TTSManager.addVolumeListener((volume) => { /* 0-1 */ });
sub.remove();

/* Cleanup */
TTSManager.deinitialize();
```

**Supports**: Piper/VITS ONNX models only (not Kokoro yet).
**Internally uses**: 22050 Hz, mono audio.

### Option C: sherpa-onnx for both Kokoro and Piper

Sherpa-onnx itself supports Kokoro natively (with espeak-ng built in).
The React Native wrapper (`react-native-sherpa-onnx-offline-tts`) only exposes Piper,
but sherpa-onnx's native C++ layer supports Kokoro model config:

```cpp
/* sherpa-onnx native Kokoro config */
OfflineTtsKokoroModelConfig kokoroConfig;
kokoroConfig.model = "kokoro-en-v0_19/model.onnx";
kokoroConfig.voices = "kokoro-en-v0_19/voices.bin";
kokoroConfig.tokens = "kokoro-en-v0_19/tokens.txt";
kokoroConfig.dataDir = "kokoro-en-v0_19/espeak-ng-data";
```

**To use Kokoro in RN**: Either fork `react-native-sherpa-onnx-offline-tts` to add Kokoro
config support, or build a thin native module that calls sherpa-onnx's Kokoro API.

### Option D: @runanywhere/onnx (Full sherpa-onnx wrapper)

**Package**: `@runanywhere/onnx` + `@runanywhere/core`
**npm**: https://www.npmjs.com/package/@runanywhere/onnx

Comprehensive sherpa-onnx wrapper with STT + TTS + VAD. But:
- Currently only lists Piper for TTS (not Kokoro)
- Adds SDK abstraction layer (@runanywhere/core)
- Newer package, less battle-tested
- Includes native binaries for both iOS and Android

### TTS Decision

**Recommended approach**: Fork/extend `react-native-sherpa-onnx-offline-tts` to support
Kokoro model config alongside Piper. This gives us:
- Native espeak-ng phonemization (solves the phonemizer problem)
- Both Kokoro and Piper through one library
- Direct native performance without extra SDK layers

**Alternative**: Use sherpa-onnx directly for TTS and build minimal RN bindings.

---

## 6. react-native-tcp-socket (TCP Control)

**Package**: `react-native-tcp-socket`
**Repo**: https://github.com/Rapsssito/react-native-tcp-socket

Node.js `net` API compatible.

```ts
import TcpSocket from 'react-native-tcp-socket';

const client = TcpSocket.createConnection(
  {
    port: 7700,
    host: '10.0.0.1',
    reuseAddress: true,
    /* interface: 'wifi', */
  },
  () => {
    /* Connected */
    client.write('REGISTER ...');
  }
);

client.on('data', (data: Buffer) => {
  /* Handle incoming data from box */
});

client.on('error', (error: Error) => {
  /* Handle connection error */
});

client.on('close', () => {
  /* Handle disconnection, trigger reconnect */
});

/* Send data */
client.write(buffer);

/* Close */
client.destroy();
```

### Key Notes

- Mirrors Node.js `net` API.
- Supports SSL/TLS if needed.
- `interface: 'wifi'` option binds to WiFi interface specifically.

---

## 7. react-native-udp (UDP Audio Streaming)

**Package**: `react-native-udp`
**Repo**: https://github.com/tradle/react-native-udp

Node.js `dgram` API compatible.

```ts
import dgram from 'react-native-udp';

const socket = dgram.createSocket('udp4');
socket.bind(0); /* bind to random local port */

socket.once('listening', () => {
  /* Socket ready */
});

/* Send audio packet to box */
const packet = Buffer.alloc(650); /* 10-byte header + 640-byte PCM payload */
/* ... fill header (speaker_id, stream_type, sequence, timestamp) */
/* ... fill payload (PCM samples) */

socket.send(packet, 0, packet.length, 7701, '10.0.0.1', (err) => {
  if (err) console.error('UDP send error:', err);
});

socket.on('message', (msg: Buffer, rinfo) => {
  /* Handle incoming UDP (unlikely in our case) */
});

/* Close */
socket.close();
```

### Key Notes

- Mirrors Node.js `dgram` API.
- `socket.send(buffer, offset, length, port, host, callback)`.
- No connection state — fire and forget (good for audio).
- Need `buffer` npm package for `Buffer.alloc()`.

---

## 8. Silero VAD (via onnxruntime-react-native)

**Alternative**: whisper.rn has built-in VAD, may not need separate Silero.

If we do use standalone Silero VAD:

```ts
import { InferenceSession, Tensor } from 'onnxruntime-react-native';

const vadSession = await InferenceSession.create(sileroModelPath);

/* Process 30ms audio chunks (480 samples at 16kHz) */
const audioTensor = new Tensor('float32', new Float32Array(samples), [1, 480]);
const srTensor = new Tensor('int64', new Int32Array([16000]), []);
const hTensor = new Tensor('float32', new Float32Array(128).fill(0), [2, 1, 64]);
const cTensor = new Tensor('float32', new Float32Array(128).fill(0), [2, 1, 64]);

const result = await vadSession.run({
  input: audioTensor,
  sr: srTensor,
  h: hTensor,
  c: cTensor,
});

const speechProbability = result['output'].data[0]; /* 0.0 to 1.0 */
/* Update h and c state for next chunk */
const newH = result['hn'];
const newC = result['cn'];
```

---

## Key Architecture Decisions from Research

### 1. VAD Strategy

**Option A (Simpler)**: Use whisper.rn's built-in `RealtimeTranscriber` which handles
VAD + STT together. It auto-slices on speech end.

**Option B (More control)**: Use standalone Silero VAD via onnxruntime-react-native,
then feed completed segments to whisper.rn for transcription.

**Recommendation**: Start with Option A (whisper.rn RealtimeTranscriber). If we need
more control over VAD behavior (e.g., sending raw audio segments to box), fall back to
Option B.

### 2. Audio Capture

If using whisper.rn's `RealtimeTranscriber`, it uses `AudioPcmStreamAdapter` from
`@fugood/react-native-audio-pcm-stream` internally. We'd still need
`react-native-live-audio-stream` separately to capture the original audio for sending
to the box via UDP.

### 3. NLLB Complexity

NLLB is the hardest integration. Key challenges:
- **SentencePiece tokenizer in JS**: No established RN library. Options:
  - Port sentencepiece to WASM and load via `onnxruntime-react-native` extensions
  - Use a pre-tokenized ONNX model that includes tokenization
  - Use `sentencepiece-js` npm package (pure JS, may be slow)
  - Build a thin native module wrapping sentencepiece C++ library
- **Autoregressive decoding**: Multiple sequential ONNX session.run() calls per translation
- **Memory**: Loading encoder + decoder + embed simultaneously uses ~600MB+
- **RTranslator approach**: Splits model into 4 parts, loads/unloads as needed

### 4. TTS Phonemization

Kokoro requires IPA phonemes as input (not raw text). Without espeak-ng:
- The expo-kokoro-onnx hardcoded map only covers ~20 English words
- Not viable for multilingual production use
- **Solution**: Use sherpa-onnx which bundles espeak-ng natively

### 5. Revised Integration Method

| Component | Library | Notes |
|-----------|---------|-------|
| STT + VAD | whisper.rn (RealtimeTranscriber) | Built-in VAD, auto-slicing |
| Audio capture (for UDP) | react-native-live-audio-stream | Raw PCM for sending original audio |
| Translation | onnxruntime-react-native + SentencePiece | NLLB ONNX, most complex part |
| TTS (all langs) | sherpa-onnx (forked/extended RN wrapper) | Kokoro + Piper, native espeak-ng |
| Networking | react-native-tcp-socket + react-native-udp | TCP control + UDP audio |

### 6. Open Questions

- Which SentencePiece approach to use for NLLB tokenization?
- Should we fork `react-native-sherpa-onnx-offline-tts` or build our own sherpa-onnx bindings?
- Can whisper.rn's VAD provide raw audio segments (not just text) for UDP streaming?
- Memory budget: can we keep all models loaded simultaneously on a phone with 4-6GB RAM?
