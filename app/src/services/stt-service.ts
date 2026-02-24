import { initWhisper } from 'whisper.rn';
import type { WhisperContext } from 'whisper.rn';
import { RealtimeTranscriber } from 'whisper.rn/src/realtime-transcription';
import { AudioPcmStreamAdapter } from 'whisper.rn/src/realtime-transcription/adapters/AudioPcmStreamAdapter';
import type {
  RealtimeTranscribeEvent,
  RealtimeVadEvent,
} from 'whisper.rn/src/realtime-transcription';

import { getModelPath } from '@/services/model-manager';

type TranscriptionCallback = (text: string, sliceIndex: number) => void;
type VadCallback = (event: RealtimeVadEvent) => void;

let whisperContext: WhisperContext | null = null;
let transcriber: RealtimeTranscriber | null = null;
let isTranscriberActive = false;

let onTranscriptionCallback: TranscriptionCallback | null = null;
let onVadCallback: VadCallback | null = null;

export async function initStt(): Promise<void> {
  if (whisperContext) return;

  const modelPath = getModelPath('whisper-small');
  whisperContext = await initWhisper({ filePath: modelPath });
}

export async function startRealtime(language?: string): Promise<void> {
  if (!whisperContext) {
    throw new Error('Whisper context not initialized. Call initStt() first.');
  }
  if (isTranscriberActive) return;

  const audioStream = new AudioPcmStreamAdapter();

  transcriber = new RealtimeTranscriber(
    {
      whisperContext,
      audioStream,
    },
    {
      audioSliceSec: 15,
      vadPreset: 'default',
      autoSliceOnSpeechEnd: true,
      transcribeOptions: {
        language: language ?? undefined,
        maxThreads: 4,
      },
    },
    {
      onTranscribe: (event: RealtimeTranscribeEvent) => {
        if (event.data?.result) {
          onTranscriptionCallback?.(event.data.result.trim(), event.sliceIndex);
        }
      },
      onVad: (event: RealtimeVadEvent) => {
        onVadCallback?.(event);
      },
      onError: (error: string) => {
        console.error('[STT] Error:', error);
      },
      onStatusChange: (active: boolean) => {
        isTranscriberActive = active;
      },
    },
  );

  await transcriber.start();
  isTranscriberActive = true;
}

export async function stopRealtime(): Promise<void> {
  if (transcriber) {
    await transcriber.stop();
    transcriber = null;
  }
  isTranscriberActive = false;
}

export function onTranscription(callback: TranscriptionCallback): () => void {
  onTranscriptionCallback = callback;
  return () => {
    onTranscriptionCallback = null;
  };
}

export function onVad(callback: VadCallback): () => void {
  onVadCallback = callback;
  return () => {
    onVadCallback = null;
  };
}

export function isSttLoaded(): boolean {
  return whisperContext !== null;
}

export function isSttActive(): boolean {
  return isTranscriberActive;
}

export async function releaseStt(): Promise<void> {
  await stopRealtime();
  if (whisperContext) {
    await whisperContext.release();
    whisperContext = null;
  }
}
