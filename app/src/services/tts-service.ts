import { File, Directory, Paths } from 'expo-file-system';

import { getModelPath } from '@/services/model-manager';
import { tokenizePhonemes, KOKORO_STYLE_DIM, KOKORO_MAX_PHONEMES } from '@/services/phonemizer';
import { KOKORO_VOICES_URL, KOKORO_SAMPLE_RATE } from '@/constants/models';

const DEFAULT_VOICE = 'af_heart';
const DEFAULT_SPEED = 1.0;

/* Lazy-loaded to avoid crash when native module isn't linked yet */
let ORT: typeof import('onnxruntime-react-native') | null = null;

function getOrt(): typeof import('onnxruntime-react-native') {
  if (!ORT) {
    ORT = require('onnxruntime-react-native') as typeof import('onnxruntime-react-native');
  }
  return ORT;
}

let session: unknown | null = null;
const voiceCache = new Map<string, Float32Array>();

function uriToPath(uri: string): string {
  if (uri.startsWith('file://')) return uri.slice(7);
  return uri;
}

function getVoicesDir(): Directory {
  return new Directory(Paths.document, 'models', 'kokoro-voices');
}

async function ensureVoice(voiceId: string): Promise<void> {
  const dir = getVoicesDir();
  if (!dir.exists) dir.create();

  const voiceFile = new File(dir, `${voiceId}.bin`);
  if (voiceFile.exists) return;

  const url = `${KOKORO_VOICES_URL}/${voiceId}.bin`;
  await File.downloadFileAsync(url, dir);
}

async function getVoiceData(voiceId: string): Promise<Float32Array> {
  const cached = voiceCache.get(voiceId);
  if (cached) return cached;

  await ensureVoice(voiceId);

  const voiceFile = new File(getVoicesDir(), `${voiceId}.bin`);
  const bytes = await voiceFile.bytes();
  const data = new Float32Array(bytes.buffer);
  voiceCache.set(voiceId, data);
  return data;
}

export async function initTts(): Promise<void> {
  if (session) return;

  const { InferenceSession } = getOrt();
  const modelPath = uriToPath(getModelPath('kokoro'));
  session = await InferenceSession.create(modelPath, {
    executionProviders: ['cpu'],
  });

  /* Pre-download default voice */
  await ensureVoice(DEFAULT_VOICE);
}

/**
 * Synthesize text to raw PCM audio (float32, 24kHz mono).
 * @returns Float32Array of audio samples in [-1, 1] range.
 */
export async function synthesize(
  text: string,
  voiceId: string = DEFAULT_VOICE,
  speed: number = DEFAULT_SPEED,
): Promise<Float32Array> {
  if (!session) throw new Error('TTS not initialized. Call initTts() first.');

  const { Tensor } = getOrt();
  const ttsSession = session as { run: (feeds: Record<string, unknown>) => Promise<Record<string, { data: Float32Array }>> };

  const tokens = tokenizePhonemes(text);
  const numTokens = Math.min(Math.max(tokens.length - 2, 0), KOKORO_MAX_PHONEMES - 1);

  /* Get voice embedding indexed by phoneme count */
  const voiceData = await getVoiceData(voiceId);
  const offset = numTokens * KOKORO_STYLE_DIM;
  const styleData = voiceData.slice(offset, offset + KOKORO_STYLE_DIM);

  const feeds: Record<string, unknown> = {
    input_ids: new Tensor('int64', new Int32Array(tokens), [1, tokens.length]),
    style: new Tensor('float32', new Float32Array(styleData), [1, KOKORO_STYLE_DIM]),
    speed: new Tensor('float32', new Float32Array([speed]), [1]),
  };

  const results = await ttsSession.run(feeds);
  return results['waveform'].data as Float32Array;
}

/** Convert float32 waveform to 16-bit PCM Int16Array. */
export function floatToPcm16(waveform: Float32Array): Int16Array {
  const pcm = new Int16Array(waveform.length);
  for (let i = 0; i < waveform.length; i++) {
    const clamped = Math.max(-1, Math.min(1, waveform[i]));
    pcm[i] = clamped < 0 ? clamped * 32768 : clamped * 32767;
  }
  return pcm;
}

export function isTtsLoaded(): boolean {
  return session !== null;
}

export async function releaseTts(): Promise<void> {
  if (session) {
    const s = session as { release: () => Promise<void> };
    await s.release();
    session = null;
  }
  voiceCache.clear();
}

export { KOKORO_SAMPLE_RATE };
