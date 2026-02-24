import { useCallback, useEffect, useRef } from 'react';
import { AppState } from 'react-native';
import type { AppStateStatus } from 'react-native';

import { useTranslationStore } from '@/stores/translation-store';
import { useConnectionStore } from '@/stores/connection-store';
import {
  startPipeline,
  stopPipeline,
  releaseAll,
  loadModels,
  updateLanguages,
  onStatusChange,
  onCaption,
  onAudioLevel,
  isPipelineRunning,
  areModelsLoaded,
} from '@/services/pipeline';

export function usePipeline() {
  const status = useTranslationStore((s) => s.status);
  const setStatus = useTranslationStore((s) => s.setStatus);
  const setLastCaption = useTranslationStore((s) => s.setLastCaption);
  const setAudioLevel = useTranslationStore((s) => s.setAudioLevel);
  const setIsRecording = useTranslationStore((s) => s.setIsRecording);
  const sourceLanguage = useTranslationStore((s) => s.sourceLanguage);
  const targetLanguage1 = useTranslationStore((s) => s.targetLanguage1);
  const targetLanguage2 = useTranslationStore((s) => s.targetLanguage2);

  const session = useConnectionStore((s) => s.session);

  const isSetup = useRef(false);
  const wasRunningBeforeBackground = useRef(false);

  useEffect(() => {
    if (isSetup.current) return;
    isSetup.current = true;

    const unsubStatus = onStatusChange(setStatus);
    const unsubCaption = onCaption(setLastCaption);
    const unsubLevel = onAudioLevel(setAudioLevel);

    return () => {
      unsubStatus();
      unsubCaption();
      unsubLevel();
    };
  }, [setStatus, setLastCaption, setAudioLevel]);

  /* Pause/resume on phone call or app background */
  useEffect(() => {
    const handleAppState = (next: AppStateStatus) => {
      if (next === 'inactive' && isPipelineRunning()) {
        wasRunningBeforeBackground.current = true;
        stopPipeline().catch(() => {});
        setIsRecording(false);
        setAudioLevel(0);
      } else if (next === 'active' && wasRunningBeforeBackground.current) {
        wasRunningBeforeBackground.current = false;
        const targets = session
          ? [session.targetLanguage1, session.targetLanguage2]
          : [];
        startPipeline(sourceLanguage, targets)
          .then(() => setIsRecording(true))
          .catch((err) => console.error('[Pipeline] Resume failed:', err));
      }
    };

    const sub = AppState.addEventListener('change', handleAppState);
    return () => sub.remove();
  }, [sourceLanguage, session, setIsRecording, setAudioLevel]);

  useEffect(() => {
    const targets = session
      ? [session.targetLanguage1, session.targetLanguage2]
      : [targetLanguage1, targetLanguage2];
    updateLanguages(sourceLanguage, targets);
  }, [sourceLanguage, session]);

  const start = useCallback(async () => {
    const targets = session
      ? [session.targetLanguage1, session.targetLanguage2]
      : [targetLanguage1, targetLanguage2];

    await startPipeline(sourceLanguage, targets);
    setIsRecording(true);
  }, [sourceLanguage, session, setIsRecording]);

  const stop = useCallback(async () => {
    await stopPipeline();
    setIsRecording(false);
    setAudioLevel(0);
  }, [setIsRecording, setAudioLevel]);

  const release = useCallback(async () => {
    await releaseAll();
    setIsRecording(false);
    setAudioLevel(0);
  }, [setIsRecording, setAudioLevel]);

  const preload = useCallback(async () => {
    await loadModels();
  }, []);

  return {
    status,
    start,
    stop,
    release,
    preload,
    isRunning: isPipelineRunning(),
    isReady: areModelsLoaded(),
  };
}
