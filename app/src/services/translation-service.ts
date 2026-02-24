import { File } from 'expo-file-system';

import { getModelPath } from '@/services/model-manager';
import {
  loadTokenizer,
  encodeForNllb,
  decode,
  releaseTokenizer,
  isTokenizerLoaded,
} from '@/services/tokenizer';
import { getNllbLanguageId, EOS_TOKEN_ID } from '@/constants/nllb-languages';

/* NLLB-200-distilled-600M architecture constants (Xenova quantized export) */
const NUM_DECODER_LAYERS = 12;
const NUM_ENCODER_LAYERS = 12;
const NUM_HEADS = 16;
const HEAD_DIM = 64;
const HIDDEN_SIZE = 1024;
const MAX_OUTPUT_TOKENS = 256;

/* Lazy-loaded to avoid crash when native module isn't linked yet */
let ORT: typeof import('onnxruntime-react-native') | null = null;

function getOrt(): typeof import('onnxruntime-react-native') {
  if (!ORT) {
    ORT = require('onnxruntime-react-native') as typeof import('onnxruntime-react-native');
  }
  return ORT;
}

let encoderSession: unknown | null = null;
let decoderSession: unknown | null = null;

function uriToPath(uri: string): string {
  if (uri.startsWith('file://')) return uri.slice(7);
  return uri;
}

export async function initTranslation(): Promise<void> {
  if (encoderSession && decoderSession && isTokenizerLoaded()) return;

  const { InferenceSession } = getOrt();

  /* Load tokenizer.json */
  const tokenizerPath = getModelPath('nllb-tokenizer');
  const tokenizerFile = new File(tokenizerPath);
  const tokenizerText = await tokenizerFile.text();
  const tokenizerJson = JSON.parse(tokenizerText) as Record<string, unknown>;
  loadTokenizer(tokenizerJson);

  /* Load ONNX sessions */
  const encoderPath = uriToPath(getModelPath('nllb-encoder'));
  const decoderPath = uriToPath(getModelPath('nllb-decoder'));

  encoderSession = await InferenceSession.create(encoderPath);
  decoderSession = await InferenceSession.create(decoderPath);
}

/** Translate text from source to target language. Returns translated text. */
export async function translate(
  text: string,
  srcLangNllbCode: string,
  tgtLangNllbCode: string,
): Promise<string> {
  if (!encoderSession || !decoderSession) {
    throw new Error('Translation not initialized. Call initTranslation() first.');
  }

  /* 1. Tokenize: [src_lang, ...text_tokens, EOS] */
  const inputIds = encodeForNllb(text, srcLangNllbCode);
  const seqLen = inputIds.length;

  /* 2. Run encoder */
  const encoderOutput = await runEncoder(inputIds, seqLen);

  /* 3. Run decoder autoregressively */
  const outputIds = await runDecoder(encoderOutput, seqLen, tgtLangNllbCode);

  /* 4. Decode output token IDs to text */
  return decode(outputIds);
}

/**
 * Translate text to multiple target languages.
 * Encoder runs once, decoder runs per target — more efficient than calling translate() twice.
 */
export async function translateToMultiple(
  text: string,
  srcLangNllbCode: string,
  tgtLangNllbCodes: string[],
): Promise<string[]> {
  if (!encoderSession || !decoderSession) {
    throw new Error('Translation not initialized. Call initTranslation() first.');
  }

  const inputIds = encodeForNllb(text, srcLangNllbCode);
  const seqLen = inputIds.length;

  /* Encoder runs once */
  const encoderOutput = await runEncoder(inputIds, seqLen);

  /* Decoder runs per target language */
  const results: string[] = [];
  for (const tgtLang of tgtLangNllbCodes) {
    const outputIds = await runDecoder(encoderOutput, seqLen, tgtLang);
    results.push(decode(outputIds));
  }

  return results;
}

async function runEncoder(inputIds: number[], seqLen: number): Promise<Float32Array> {
  const { Tensor } = getOrt();
  const session = encoderSession as { run: (feeds: Record<string, unknown>) => Promise<Record<string, { data: Float32Array }>> };
  const feeds: Record<string, unknown> = {
    input_ids: new Tensor(
      'int64',
      BigInt64Array.from(inputIds.map(BigInt)),
      [1, seqLen],
    ),
    attention_mask: new Tensor(
      'int64',
      new BigInt64Array(seqLen).fill(1n),
      [1, seqLen],
    ),
  };

  const results = await session.run(feeds);
  return results['last_hidden_state'].data as Float32Array;
}

async function runDecoder(
  encoderHiddenStates: Float32Array,
  encoderSeqLen: number,
  tgtLangNllbCode: string,
): Promise<number[]> {
  const { Tensor } = getOrt();
  const session = decoderSession as { run: (feeds: Record<string, unknown>) => Promise<Record<string, { data: Float32Array }>> };
  const tgtLangId = getNllbLanguageId(tgtLangNllbCode);
  if (tgtLangId === -1) throw new Error(`Unknown target language: ${tgtLangNllbCode}`);

  const outputIds: number[] = [];

  /* Build full decoder input sequence: [EOS, tgtLangId, token1, token2, ...] */
  const decoderTokens: number[] = [EOS_TOKEN_ID];

  for (let step = 0; step < MAX_OUTPUT_TOKENS; step++) {
    const seqLen = decoderTokens.length;

    const feeds: Record<string, unknown> = {
      input_ids: new Tensor(
        'int64',
        BigInt64Array.from(decoderTokens.map(BigInt)),
        [1, seqLen],
      ),
      encoder_hidden_states: new Tensor(
        'float32',
        encoderHiddenStates,
        [1, encoderSeqLen, HIDDEN_SIZE],
      ),
      encoder_attention_mask: new Tensor(
        'int64',
        new BigInt64Array(encoderSeqLen).fill(1n),
        [1, encoderSeqLen],
      ),
    };

    /* Empty KV-cache on every step (no caching, model recomputes each time) */
    for (let i = 0; i < NUM_DECODER_LAYERS; i++) {
      feeds[`past_key_values.${i}.decoder.key`] = new Tensor('float32', new Float32Array(0), [1, NUM_HEADS, 0, HEAD_DIM]);
      feeds[`past_key_values.${i}.decoder.value`] = new Tensor('float32', new Float32Array(0), [1, NUM_HEADS, 0, HEAD_DIM]);
      feeds[`past_key_values.${i}.encoder.key`] = new Tensor('float32', new Float32Array(0), [1, NUM_HEADS, 0, HEAD_DIM]);
      feeds[`past_key_values.${i}.encoder.value`] = new Tensor('float32', new Float32Array(0), [1, NUM_HEADS, 0, HEAD_DIM]);
    }

    feeds['use_cache_branch'] = new Tensor('bool', [false], [1]);

    const results = await session.run(feeds);

    /* Greedy decode: argmax over last token's logits */
    const allLogits = results['logits'].data as Float32Array;
    const vocabSize = allLogits.length / seqLen;
    const lastTokenOffset = (seqLen - 1) * vocabSize;

    let nextTokenId: number;

    /* Force first output token to be the target language */
    if (step === 0) {
      nextTokenId = tgtLangId;
    } else {
      let maxIdx = 0;
      let maxVal = allLogits[lastTokenOffset];
      for (let v = 1; v < vocabSize; v++) {
        if (allLogits[lastTokenOffset + v] > maxVal) {
          maxVal = allLogits[lastTokenOffset + v];
          maxIdx = v;
        }
      }
      nextTokenId = maxIdx;
    }

    if (nextTokenId === EOS_TOKEN_ID && step > 0) break;

    decoderTokens.push(nextTokenId);
    if (step > 0) {
      outputIds.push(nextTokenId);
    }
  }

  return outputIds;
}

export function isTranslationLoaded(): boolean {
  return encoderSession !== null && decoderSession !== null && isTokenizerLoaded();
}

export async function releaseTranslation(): Promise<void> {
  const session = encoderSession as { release: () => Promise<void> } | null;
  if (session) {
    await session.release();
    encoderSession = null;
  }
  const decoder = decoderSession as { release: () => Promise<void> } | null;
  if (decoder) {
    await decoder.release();
    decoderSession = null;
  }
  releaseTokenizer();
}
