import { useCallback, useEffect } from 'react';

import type { ModelId } from '@/types';
import { ALL_MODEL_IDS } from '@/constants/models';
import { useModelStore } from '@/stores/model-store';
import {
  ensureModelDir,
  isModelDownloaded,
  downloadModel,
  deleteModel,
  extractBundledModels,
} from '@/services/model-manager';

export function useModelManager() {
  const statuses = useModelStore((s) => s.statuses);
  const progress = useModelStore((s) => s.progress);
  const setModelStatus = useModelStore((s) => s.setModelStatus);
  const isAllReady = useModelStore((s) => s.isAllReady);

  const checkAllStatuses = useCallback(() => {
    ensureModelDir();
    for (const id of ALL_MODEL_IDS) {
      const downloaded = isModelDownloaded(id);
      setModelStatus(id, downloaded ? 'ready' : 'not_downloaded');
    }
  }, [setModelStatus]);

  useEffect(() => {
    /* Extract bundled models from APK assets, then check statuses */
    extractBundledModels().then(() => checkAllStatuses());
  }, [checkAllStatuses]);

  const startDownload = useCallback(
    async (modelId: ModelId) => {
      setModelStatus(modelId, 'downloading');
      try {
        await downloadModel(modelId);
        setModelStatus(modelId, 'ready');
      } catch {
        setModelStatus(modelId, 'error');
      }
    },
    [setModelStatus],
  );

  const downloadAllModels = useCallback(async () => {
    ensureModelDir();
    for (const id of ALL_MODEL_IDS) {
      if (statuses[id] !== 'ready') {
        await startDownload(id);
      }
    }
  }, [statuses, startDownload]);

  const removeModel = useCallback(
    (modelId: ModelId) => {
      deleteModel(modelId);
      setModelStatus(modelId, 'not_downloaded');
    },
    [setModelStatus],
  );

  return {
    statuses,
    progress,
    isAllReady: isAllReady(),
    checkAllStatuses,
    startDownload,
    downloadAllModels,
    removeModel,
  };
}
