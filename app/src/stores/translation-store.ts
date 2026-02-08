import { create } from 'zustand';
import type { TranslationStatus } from '@/types';

interface TranslationState {
  status: TranslationStatus;
  sourceLanguage: string;
  isRecording: boolean;
  isSpeaking: boolean;
  audioLevel: number;
  lastCaption: string;

  setStatus: (status: TranslationStatus) => void;
  setSourceLanguage: (language: string) => void;
  setIsRecording: (isRecording: boolean) => void;
  setIsSpeaking: (isSpeaking: boolean) => void;
  setAudioLevel: (level: number) => void;
  setLastCaption: (caption: string) => void;
  reset: () => void;
}

export const useTranslationStore = create<TranslationState>((set) => ({
  status: 'idle',
  sourceLanguage: 'en',
  isRecording: false,
  isSpeaking: false,
  audioLevel: 0,
  lastCaption: '',

  setStatus: (status) => set({ status }),
  setSourceLanguage: (sourceLanguage) => set({ sourceLanguage }),
  setIsRecording: (isRecording) => set({ isRecording }),
  setIsSpeaking: (isSpeaking) => set({ isSpeaking }),
  setAudioLevel: (audioLevel) => set({ audioLevel }),
  setLastCaption: (lastCaption) => set({ lastCaption }),
  reset: () =>
    set({
      status: 'idle',
      isRecording: false,
      isSpeaking: false,
      audioLevel: 0,
      lastCaption: '',
    }),
}));
