/**
 * Audio capture service.
 * Handles microphone recording and PCM audio streaming.
 *
 * @module audio-capture
 */

import { AUDIO_SAMPLE_RATE, AUDIO_BIT_DEPTH, AUDIO_CHANNELS } from '@/constants';

export interface AudioCaptureConfig {
  sampleRate: number;
  bitDepth: number;
  channels: number;
  onAudioData: (pcmData: ArrayBuffer) => void;
}

const DEFAULT_CONFIG: Omit<AudioCaptureConfig, 'onAudioData'> = {
  sampleRate: AUDIO_SAMPLE_RATE,
  bitDepth: AUDIO_BIT_DEPTH,
  channels: AUDIO_CHANNELS,
};

/* TODO: Implement with expo-av or react-native-live-audio-stream */
export function createAudioCapture(config: AudioCaptureConfig) {
  const _config = { ...DEFAULT_CONFIG, ...config };

  return {
    start: async () => {
      /* TODO: Start mic recording, stream PCM chunks via onAudioData callback */
    },
    stop: async () => {
      /* TODO: Stop recording, release resources */
    },
    isRecording: () => false,
  };
}
