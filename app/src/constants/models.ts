import type { ModelConfig, ModelId } from '@/types';

const HUGGINGFACE_BASE = 'https://huggingface.co';

const NLLB_BASE = `${HUGGINGFACE_BASE}/Xenova/nllb-200-distilled-600M/resolve/main/onnx`;

export const MODEL_CONFIGS: Record<ModelId, ModelConfig> = {
  'whisper-small': {
    id: 'whisper-small',
    name: 'Whisper Small Q8',
    url: `${HUGGINGFACE_BASE}/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin`,
    sizeBytes: 264_000_000,
    filename: 'ggml-small-q8_0.bin',
    type: 'whisper',
  },
  'nllb-encoder': {
    id: 'nllb-encoder',
    name: 'NLLB Encoder',
    url: `${NLLB_BASE}/encoder_model_quantized.onnx`,
    sizeBytes: 419_000_000,
    filename: 'encoder_model_quantized.onnx',
    type: 'nllb',
  },
  'nllb-decoder': {
    id: 'nllb-decoder',
    name: 'NLLB Decoder',
    url: `${NLLB_BASE}/decoder_model_merged_quantized.onnx`,
    sizeBytes: 476_000_000,
    filename: 'decoder_model_merged_quantized.onnx',
    type: 'nllb',
  },
  'nllb-tokenizer': {
    id: 'nllb-tokenizer',
    name: 'NLLB Tokenizer',
    url: `${HUGGINGFACE_BASE}/facebook/nllb-200-distilled-600M/resolve/main/tokenizer.json`,
    sizeBytes: 17_400_000,
    filename: 'tokenizer.json',
    type: 'nllb',
  },
  kokoro: {
    id: 'kokoro',
    name: 'Kokoro 82M',
    url: `${HUGGINGFACE_BASE}/onnx-community/Kokoro-82M-ONNX/resolve/main/onnx/model_q8f16.onnx`,
    sizeBytes: 86_000_000,
    filename: 'kokoro-model.onnx',
    type: 'kokoro',
  },
  'piper-de': {
    id: 'piper-de',
    name: 'Piper German',
    url: `${HUGGINGFACE_BASE}/rhasspy/piper-voices/resolve/main/de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx`,
    sizeBytes: 60_000_000,
    filename: 'piper-de.onnx',
    type: 'piper',
  },
  'piper-ar': {
    id: 'piper-ar',
    name: 'Piper Arabic',
    url: `${HUGGINGFACE_BASE}/rhasspy/piper-voices/resolve/main/ar/ar_JO/kareem/medium/ar_JO-kareem-medium.onnx`,
    sizeBytes: 60_000_000,
    filename: 'piper-ar.onnx',
    type: 'piper',
  },
  'piper-ru': {
    id: 'piper-ru',
    name: 'Piper Russian',
    url: `${HUGGINGFACE_BASE}/rhasspy/piper-voices/resolve/main/ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx`,
    sizeBytes: 60_000_000,
    filename: 'piper-ru.onnx',
    type: 'piper',
  },
};

export const ALL_MODEL_IDS: ModelId[] = Object.keys(MODEL_CONFIGS) as ModelId[];

export const REQUIRED_MODEL_IDS: ModelId[] = [
  'whisper-small',
  'nllb-encoder',
  'nllb-decoder',
  'nllb-tokenizer',
  'kokoro',
];

export const NLLB_MODEL_IDS: ModelId[] = ['nllb-encoder', 'nllb-decoder', 'nllb-tokenizer'];

export const KOKORO_VOICES_URL = `${HUGGINGFACE_BASE}/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices`;

export const KOKORO_SAMPLE_RATE = 24000;
export const PIPER_SAMPLE_RATE = 22050;
