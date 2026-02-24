import type { Language } from '@/types';

/* Re-export design system constants */
export { COLORS } from './colors';
export type { ColorValue } from './colors';
export { SPACING, RADIUS, SHADOWS, HIT_SLOP } from './spacing';
export { TEXT_STYLES } from './typography';
export type { TextStyleName } from './typography';
export { ICON_SIZES, ICON_COLORS, NAV_ICONS, ACTION_ICONS, STATUS_ICONS } from './icons';
export type { IconName, IconSize, IconColor } from './icons';
export { MODEL_CONFIGS, ALL_MODEL_IDS } from './models';

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

/* Supported languages with model-specific codes */
export const SUPPORTED_LANGUAGES: Language[] = [
  { code: 'en', name: 'English', nativeName: 'English', nllbCode: 'eng_Latn', whisperCode: 'en', ttsEngine: 'kokoro' },
  { code: 'es', name: 'Spanish', nativeName: 'Español', nllbCode: 'spa_Latn', whisperCode: 'es', ttsEngine: 'kokoro' },
  { code: 'fr', name: 'French', nativeName: 'Français', nllbCode: 'fra_Latn', whisperCode: 'fr', ttsEngine: 'kokoro' },
  { code: 'pt', name: 'Portuguese', nativeName: 'Português', nllbCode: 'por_Latn', whisperCode: 'pt', ttsEngine: 'kokoro' },
  { code: 'it', name: 'Italian', nativeName: 'Italiano', nllbCode: 'ita_Latn', whisperCode: 'it', ttsEngine: 'kokoro' },
  { code: 'zh', name: 'Chinese', nativeName: '中文', nllbCode: 'zho_Hans', whisperCode: 'zh', ttsEngine: 'kokoro' },
  { code: 'ja', name: 'Japanese', nativeName: '日本語', nllbCode: 'jpn_Jpan', whisperCode: 'ja', ttsEngine: 'kokoro' },
  { code: 'ko', name: 'Korean', nativeName: '한국어', nllbCode: 'kor_Hang', whisperCode: 'ko', ttsEngine: 'kokoro' },
  { code: 'hi', name: 'Hindi', nativeName: 'हिन्दी', nllbCode: 'hin_Deva', whisperCode: 'hi', ttsEngine: 'kokoro' },
  { code: 'de', name: 'German', nativeName: 'Deutsch', nllbCode: 'deu_Latn', whisperCode: 'de', ttsEngine: 'piper' },
  { code: 'ar', name: 'Arabic', nativeName: 'العربية', nllbCode: 'arb_Arab', whisperCode: 'ar', ttsEngine: 'piper' },
  { code: 'ru', name: 'Russian', nativeName: 'Русский', nllbCode: 'rus_Cyrl', whisperCode: 'ru', ttsEngine: 'piper' },
];
