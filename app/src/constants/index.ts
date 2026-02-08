import type { Language } from '@/types';

/* Box network configuration */
export const BOX_IP = '10.0.0.1';
export const BOX_CONTROL_PORT = 7700;
export const BOX_AUDIO_PORT = 7701;

/* Audio format */
export const AUDIO_SAMPLE_RATE = 16000;
export const AUDIO_BIT_DEPTH = 16;
export const AUDIO_CHANNELS = 1;
export const AUDIO_CHUNK_MS = 20;
export const AUDIO_CHUNK_BYTES = (AUDIO_SAMPLE_RATE * AUDIO_BIT_DEPTH * AUDIO_CHANNELS * AUDIO_CHUNK_MS) / (8 * 1000);

/* VAD configuration */
export const VAD_SILENCE_THRESHOLD_MS = 700;
export const VAD_MAX_CHUNK_DURATION_S = 15;

/* Heartbeat */
export const HEARTBEAT_INTERVAL_MS = 2000;
export const HEARTBEAT_TIMEOUT_MS = 6000;

/* WiFi */
export const WIFI_SSID_PREFIX = 'VoxSwap-';

/* Supported languages (Tier 1 — good Whisper + NLLB + Piper support) */
export const SUPPORTED_LANGUAGES: Language[] = [
  { code: 'en', name: 'English', nativeName: 'English' },
  { code: 'es', name: 'Spanish', nativeName: 'Espanol' },
  { code: 'fr', name: 'French', nativeName: 'Francais' },
  { code: 'de', name: 'German', nativeName: 'Deutsch' },
  { code: 'pt', name: 'Portuguese', nativeName: 'Portugues' },
  { code: 'it', name: 'Italian', nativeName: 'Italiano' },
  { code: 'zh', name: 'Chinese', nativeName: '中文' },
  { code: 'ja', name: 'Japanese', nativeName: '日本語' },
  { code: 'ko', name: 'Korean', nativeName: '한국어' },
  { code: 'ar', name: 'Arabic', nativeName: 'العربية' },
  { code: 'hi', name: 'Hindi', nativeName: 'हिन्दी' },
  { code: 'ru', name: 'Russian', nativeName: 'Русский' },
];
