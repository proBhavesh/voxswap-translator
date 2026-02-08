/* Supported languages for translation */
export interface Language {
  code: string;
  name: string;
  nativeName: string;
}

/* Connection state between phone and box */
export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

/* Translation pipeline state */
export type TranslationStatus = 'idle' | 'loading_models' | 'ready' | 'translating' | 'error';

/* Speaker registration with the box */
export interface SpeakerRegistration {
  speakerId: number;
  speakerName: string;
  sourceLanguage: string;
  isAdmin: boolean;
}

/* Session configuration set by admin */
export interface SessionConfig {
  targetLanguage1: string;
  targetLanguage2: string;
  wifiPassword: string;
}

/* Audio stream types sent over UDP */
export type StreamType = 'original' | 'translation1' | 'translation2';

/* UDP packet header */
export interface AudioPacketHeader {
  speakerId: number;
  streamType: number;
  sequence: number;
  timestamp: number;
}
