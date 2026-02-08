/**
 * Stream sender service.
 * Sends raw PCM audio streams to the box over UDP.
 *
 * @module stream-sender
 */

import type { AudioPacketHeader, StreamType } from '@/types';
import { BOX_IP, BOX_AUDIO_PORT, AUDIO_CHUNK_BYTES } from '@/constants';

export interface StreamSenderConfig {
  boxIp: string;
  audioPort: number;
  speakerId: number;
}

const DEFAULT_CONFIG: Pick<StreamSenderConfig, 'boxIp' | 'audioPort'> = {
  boxIp: BOX_IP,
  audioPort: BOX_AUDIO_PORT,
};

const STREAM_TYPE_MAP: Record<StreamType, number> = {
  original: 0,
  translation1: 1,
  translation2: 2,
};

/* TODO: Implement with UDP sockets */
export function createStreamSender(config: StreamSenderConfig) {
  const _config = { ...DEFAULT_CONFIG, ...config };
  let _sequence = 0;

  return {
    send: async (streamType: StreamType, pcmData: ArrayBuffer) => {
      const header: AudioPacketHeader = {
        speakerId: _config.speakerId,
        streamType: STREAM_TYPE_MAP[streamType],
        sequence: _sequence++,
        timestamp: Date.now(),
      };
      /* TODO: Serialize header + pcmData into UDP packet, send to box */
    },
    resetSequence: () => {
      _sequence = 0;
    },
  };
}
