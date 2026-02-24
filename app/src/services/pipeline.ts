import { Buffer } from 'buffer';

import { initStt, startRealtime, stopRealtime, releaseStt, onTranscription, onVad } from '@/services/stt-service';
import { initTranslation, translateToMultiple, releaseTranslation } from '@/services/translation-service';
import { initTts, synthesize, floatToPcm16, releaseTts, KOKORO_SAMPLE_RATE } from '@/services/tts-service';
import { initAudioCapture, startRecording, stopRecording, onAudioChunk, requestMicPermission, computeRms } from '@/services/audio-capture';
import { sendAudioChunked } from '@/services/stream-sender';
import { resampleLinear } from '@/utils/audio-resample';
import { SUPPORTED_LANGUAGES, AUDIO_SAMPLE_RATE } from '@/constants';
import type { TranslationStatus } from '@/types';

type StatusCallback = (status: TranslationStatus) => void;
type CaptionCallback = (text: string) => void;
type AudioLevelCallback = (level: number) => void;

let statusCallback: StatusCallback | null = null;
let captionCallback: CaptionCallback | null = null;
let audioLevelCallback: AudioLevelCallback | null = null;

let isRunning = false;
let modelsLoaded = false;

let unsubAudioChunk: (() => void) | null = null;
let unsubTranscription: (() => void) | null = null;
let unsubVad: (() => void) | null = null;

let currentSourceLanguage = 'en';
let currentTargetLanguages: string[] = [];

function getLanguageConfig(code: string) {
  return SUPPORTED_LANGUAGES.find((l) => l.code === code);
}

function getNllbCode(code: string): string {
  return getLanguageConfig(code)?.nllbCode ?? 'eng_Latn';
}

function getWhisperCode(code: string): string | undefined {
  if (code === 'auto') return undefined;
  return getLanguageConfig(code)?.whisperCode;
}

async function processTranscription(text: string): Promise<void> {
  if (!text.trim() || currentTargetLanguages.length === 0) return;

  captionCallback?.(text);

  try {
    const srcNllb = getNllbCode(currentSourceLanguage);
    const tgtNllbCodes = currentTargetLanguages.map(getNllbCode);

    const translations = await translateToMultiple(text, srcNllb, tgtNllbCodes);

    for (let i = 0; i < translations.length && i < 2; i++) {
      const translatedText = translations[i];
      if (!translatedText.trim()) continue;

      try {
        const waveform = await synthesize(translatedText);
        const pcm16 = floatToPcm16(waveform);

        const resampled = resampleLinear(pcm16, KOKORO_SAMPLE_RATE, AUDIO_SAMPLE_RATE) as Int16Array;

        const buf = Buffer.from(resampled.buffer, resampled.byteOffset, resampled.byteLength);
        const streamType = i === 0 ? 'translation1' as const : 'translation2' as const;
        sendAudioChunked(streamType, buf);
      } catch (ttsErr) {
        console.error(`[Pipeline] TTS failed for target ${i + 1}:`, ttsErr);
      }
    }
  } catch (err) {
    console.error('[Pipeline] Translation failed:', err);
  }
}

export async function loadModels(): Promise<void> {
  if (modelsLoaded) return;

  statusCallback?.('loading_models');

  try {
    await initStt();
    await initTranslation();
    await initTts();
    modelsLoaded = true;
    statusCallback?.('ready');
  } catch (err) {
    console.error('[Pipeline] Model loading failed:', err);
    statusCallback?.('error');
    throw err;
  }
}

export async function startPipeline(
  sourceLanguage: string,
  targetLanguages: string[],
): Promise<void> {
  if (isRunning) return;

  currentSourceLanguage = sourceLanguage;
  currentTargetLanguages = targetLanguages;

  const hasPermission = await requestMicPermission();
  if (!hasPermission) {
    statusCallback?.('error');
    throw new Error('Microphone permission denied');
  }

  if (!modelsLoaded) {
    await loadModels();
  }

  statusCallback?.('translating');

  await initAudioCapture();

  unsubAudioChunk = onAudioChunk((chunk) => {
    const rms = computeRms(chunk);
    audioLevelCallback?.(Math.min(rms * 5, 1));

    const buf = Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength);
    sendAudioChunked('original', buf);
  });

  unsubTranscription = onTranscription((text, _sliceIndex) => {
    processTranscription(text);
  });

  unsubVad = onVad((_event) => {
    /* VAD events can be used to show speaking indicator in the future */
  });

  const whisperLang = getWhisperCode(sourceLanguage);
  await startRealtime(whisperLang);
  startRecording();

  isRunning = true;
}

export async function stopPipeline(): Promise<void> {
  if (!isRunning) return;

  stopRecording();
  await stopRealtime();

  unsubAudioChunk?.();
  unsubTranscription?.();
  unsubVad?.();
  unsubAudioChunk = null;
  unsubTranscription = null;
  unsubVad = null;

  audioLevelCallback?.(0);
  isRunning = false;
  statusCallback?.('ready');
}

export async function releaseAll(): Promise<void> {
  await stopPipeline();
  await Promise.all([
    releaseStt(),
    releaseTranslation(),
    releaseTts(),
  ]);
  modelsLoaded = false;
  statusCallback?.('idle');
}

export function updateLanguages(sourceLanguage: string, targetLanguages: string[]): void {
  currentSourceLanguage = sourceLanguage;
  currentTargetLanguages = targetLanguages;
}

export function onStatusChange(cb: StatusCallback): () => void {
  statusCallback = cb;
  return () => { statusCallback = null; };
}

export function onCaption(cb: CaptionCallback): () => void {
  captionCallback = cb;
  return () => { captionCallback = null; };
}

export function onAudioLevel(cb: AudioLevelCallback): () => void {
  audioLevelCallback = cb;
  return () => { audioLevelCallback = null; };
}

export function isPipelineRunning(): boolean {
  return isRunning;
}

export function areModelsLoaded(): boolean {
  return modelsLoaded;
}
