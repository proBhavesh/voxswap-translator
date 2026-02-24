import dgram from 'react-native-udp';
import type { StreamType } from '@/types';
import { BOX_IP, BOX_AUDIO_PORT, AUDIO_CHUNK_BYTES } from '@/constants';

/* 10-byte packet header:
   speaker_id (1) + stream_type (1) + sequence (4) + timestamp (4) */
const HEADER_SIZE = 10;

const STREAM_TYPE_MAP: Record<StreamType, number> = {
  original: 0,
  translation1: 1,
  translation2: 2,
};

let udpSocket: ReturnType<typeof dgram.createSocket> | null = null;
let speakerId = 0;

/* Per-stream sequence counters */
const sequences: Record<number, number> = { 0: 0, 1: 0, 2: 0 };

export function initStreamSender(assignedSpeakerId: number): Promise<void> {
  return new Promise((resolve, reject) => {
    if (udpSocket) {
      speakerId = assignedSpeakerId;
      resolve();
      return;
    }

    speakerId = assignedSpeakerId;
    udpSocket = dgram.createSocket({ type: 'udp4' });

    udpSocket.once('listening', () => {
      resolve();
    });

    udpSocket.once('error', (err) => {
      reject(err);
    });

    /* Bind to any available port */
    udpSocket.bind(0);
  });
}

function buildPacket(streamType: number, pcmData: Buffer): Buffer {
  const packet = Buffer.alloc(HEADER_SIZE + pcmData.length);

  packet.writeUInt8(speakerId, 0);
  packet.writeUInt8(streamType, 1);
  packet.writeUInt32BE(sequences[streamType]++, 2);
  packet.writeUInt32BE(Date.now() & 0xFFFFFFFF, 6);

  pcmData.copy(packet, HEADER_SIZE);
  return packet;
}

/** Send a raw PCM audio chunk for a specific stream type. */
export function sendAudio(streamType: StreamType, pcmData: Buffer): void {
  if (!udpSocket) return;

  const typeNum = STREAM_TYPE_MAP[streamType];
  const packet = buildPacket(typeNum, pcmData);

  udpSocket.send(packet, 0, packet.length, BOX_AUDIO_PORT, BOX_IP, (err) => {
    if (err) console.error('[UDP] Send error:', err);
  });
}

/**
 * Send PCM audio in chunked 20ms packets (640 bytes each at 16kHz 16-bit mono).
 * Large buffers are split into AUDIO_CHUNK_BYTES-sized packets.
 */
export function sendAudioChunked(streamType: StreamType, pcmData: Buffer): void {
  for (let offset = 0; offset < pcmData.length; offset += AUDIO_CHUNK_BYTES) {
    const chunk = pcmData.subarray(offset, offset + AUDIO_CHUNK_BYTES);
    sendAudio(streamType, chunk);
  }
}

export function resetSequences(): void {
  sequences[0] = 0;
  sequences[1] = 0;
  sequences[2] = 0;
}

export function closeStreamSender(): void {
  if (udpSocket) {
    udpSocket.close();
    udpSocket = null;
  }
  resetSequences();
}
