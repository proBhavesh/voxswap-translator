import { create } from 'zustand';

import type { ModelId, ModelStatus, DownloadProgress } from '@/types';
import { ALL_MODEL_IDS } from '@/constants/models';

interface ModelState {
  statuses: Record<ModelId, ModelStatus>;
  progress: Record<ModelId, DownloadProgress | null>;

  setModelStatus: (modelId: ModelId, status: ModelStatus) => void;
  setDownloadProgress: (modelId: ModelId, progress: DownloadProgress | null) => void;
  isAllReady: () => boolean;
  reset: () => void;
}

function createInitialStatuses(): Record<ModelId, ModelStatus> {
  const statuses = {} as Record<ModelId, ModelStatus>;
  for (const id of ALL_MODEL_IDS) {
    statuses[id] = 'not_downloaded';
  }
  return statuses;
}

function createInitialProgress(): Record<ModelId, DownloadProgress | null> {
  const progress = {} as Record<ModelId, DownloadProgress | null>;
  for (const id of ALL_MODEL_IDS) {
    progress[id] = null;
  }
  return progress;
}

export const useModelStore = create<ModelState>((set, get) => ({
  statuses: createInitialStatuses(),
  progress: createInitialProgress(),

  setModelStatus: (modelId, status) =>
    set((state) => ({
      statuses: { ...state.statuses, [modelId]: status },
    })),

  setDownloadProgress: (modelId, progress) =>
    set((state) => ({
      progress: { ...state.progress, [modelId]: progress },
    })),

  isAllReady: () => {
    const { statuses } = get();
    return ALL_MODEL_IDS.every((id) => statuses[id] === 'ready');
  },

  reset: () =>
    set({
      statuses: createInitialStatuses(),
      progress: createInitialProgress(),
    }),
}));
