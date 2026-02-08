/**
 * Network client service.
 * Handles TCP control connection to the box (registration, heartbeat, settings).
 *
 * @module network-client
 */

import type { SpeakerRegistration, SessionConfig } from '@/types';
import { BOX_IP, BOX_CONTROL_PORT, HEARTBEAT_INTERVAL_MS } from '@/constants';

export interface NetworkClientConfig {
  boxIp: string;
  controlPort: number;
  onConnected: (speaker: SpeakerRegistration, session: SessionConfig) => void;
  onDisconnected: () => void;
  onSessionUpdate: (session: SessionConfig) => void;
}

const DEFAULT_CONFIG: Pick<NetworkClientConfig, 'boxIp' | 'controlPort'> = {
  boxIp: BOX_IP,
  controlPort: BOX_CONTROL_PORT,
};

/* TODO: Implement with TCP sockets */
export function createNetworkClient(config: NetworkClientConfig) {
  const _config = { ...DEFAULT_CONFIG, ...config };
  let _heartbeatInterval: ReturnType<typeof setInterval> | null = null;

  return {
    connect: async (_speakerName: string, _sourceLanguage: string) => {
      /* TODO: Open TCP connection, send REGISTER, start heartbeat */
    },
    disconnect: async () => {
      if (_heartbeatInterval) {
        clearInterval(_heartbeatInterval);
        _heartbeatInterval = null;
      }
      /* TODO: Close TCP connection */
    },
    setTargetLanguages: async (_lang1: string, _lang2: string) => {
      /* TODO: Send SET_LANGUAGES command (admin only) */
    },
    isConnected: () => false,
  };
}
