import { Platform, PermissionsAndroid } from 'react-native';
import LiveAudioStream from '@fugood/react-native-audio-pcm-stream';
import { Buffer } from 'buffer';

import { AUDIO_SAMPLE_RATE, AUDIO_BIT_DEPTH, AUDIO_CHANNELS } from '@/constants';

type AudioChunkListener = (pcmData: Buffer) => void;

const BUFFER_SIZE = 4096;

let isInitialized = false;
let isCurrentlyRecording = false;
const listeners: Set<AudioChunkListener> = new Set();

function computeRms(pcmBuffer: Buffer): number {
  const samples = new Int16Array(
    pcmBuffer.buffer,
    pcmBuffer.byteOffset,
    pcmBuffer.byteLength / 2,
  );
  let sumSquares = 0;
  for (let i = 0; i < samples.length; i++) {
    const normalized = samples[i] / 32768;
    sumSquares += normalized * normalized;
  }
  return Math.sqrt(sumSquares / samples.length);
}

export async function requestMicPermission(): Promise<boolean> {
  if (Platform.OS === 'android') {
    const granted = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    );
    return granted === PermissionsAndroid.RESULTS.GRANTED;
  }
  return true;
}

export async function initAudioCapture(): Promise<void> {
  if (isInitialized) return;

  await LiveAudioStream.init({
    sampleRate: AUDIO_SAMPLE_RATE,
    channels: AUDIO_CHANNELS,
    bitsPerSample: AUDIO_BIT_DEPTH,
    audioSource: 6,
    bufferSize: BUFFER_SIZE,
    wavFile: '',
  });

  LiveAudioStream.on('data', (base64Data: string) => {
    const chunk = Buffer.from(base64Data, 'base64');
    for (const listener of listeners) {
      listener(chunk);
    }
  });

  isInitialized = true;
}

export function startRecording(): void {
  if (!isInitialized || isCurrentlyRecording) return;
  LiveAudioStream.start();
  isCurrentlyRecording = true;
}

export function stopRecording(): void {
  if (!isCurrentlyRecording) return;
  LiveAudioStream.stop();
  isCurrentlyRecording = false;
}

export function isRecording(): boolean {
  return isCurrentlyRecording;
}

export function onAudioChunk(listener: AudioChunkListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export { computeRms };
