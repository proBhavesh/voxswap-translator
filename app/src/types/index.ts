/* TTS engine type */
export type TtsEngine = 'kokoro' | 'piper';

/* Supported language with all model-specific codes */
export interface Language {
  code: string;
  name: string;
  nativeName: string;
  nllbCode: string;
  whisperCode: string;
  ttsEngine: TtsEngine;
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

/* Model identifiers */
export type ModelId =
  | 'whisper-small'
  | 'nllb-encoder'
  | 'nllb-decoder'
  | 'nllb-tokenizer'
  | 'kokoro'
  | 'piper-de'
  | 'piper-ar'
  | 'piper-ru';

/* Model download/load status */
export type ModelStatus = 'not_downloaded' | 'downloading' | 'ready' | 'error';

/* Progress info for model downloads */
export interface DownloadProgress {
  modelId: ModelId;
  bytesDownloaded: number;
  totalBytes: number;
  percent: number;
}

/* Configuration for a downloadable model */
export interface ModelConfig {
  id: ModelId;
  name: string;
  url: string;
  sizeBytes: number;
  filename: string;
  type: 'whisper' | 'nllb' | 'kokoro' | 'piper';
}
