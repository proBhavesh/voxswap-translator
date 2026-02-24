import { File, Directory, Paths } from 'expo-file-system';
import { Platform } from 'react-native';
import RNFS from 'react-native-fs';

import type { ModelId } from '@/types';
import { MODEL_CONFIGS } from '@/constants/models';

function getModelDir(modelId: ModelId): Directory {
  return new Directory(Paths.document, 'models', modelId);
}

function getModelFile(modelId: ModelId): File {
  const config = MODEL_CONFIGS[modelId];
  return new File(getModelDir(modelId), config.filename);
}

export function ensureModelDir(): void {
  const modelsDir = new Directory(Paths.document, 'models');
  if (!modelsDir.exists) {
    modelsDir.create();
  }
}

export function isModelDownloaded(modelId: ModelId): boolean {
  return getModelFile(modelId).exists;
}

export function getModelPath(modelId: ModelId): string {
  return getModelFile(modelId).uri;
}

/**
 * Copy a model from bundled Android assets to the document directory.
 * Models are bundled in android/app/src/main/assets/models/{filename}.
 */
async function copyFromBundledAssets(modelId: ModelId): Promise<boolean> {
  if (Platform.OS !== 'android') return false;

  const config = MODEL_CONFIGS[modelId];
  const assetPath = `models/${config.filename}`;

  const exists = await RNFS.existsAssets(assetPath);
  if (!exists) return false;

  const dir = getModelDir(modelId);
  if (!dir.exists) dir.create();

  const destPath = `${RNFS.DocumentDirectoryPath}/models/${modelId}/${config.filename}`;
  await RNFS.copyFileAssets(assetPath, destPath);
  return true;
}

/**
 * Extract all bundled models from APK assets to document directory.
 * Skips models that are already extracted.
 */
export async function extractBundledModels(): Promise<void> {
  ensureModelDir();

  const modelIds = Object.keys(MODEL_CONFIGS) as ModelId[];
  for (const id of modelIds) {
    if (isModelDownloaded(id)) continue;

    try {
      const copied = await copyFromBundledAssets(id);
      if (copied) {
        console.log(`Extracted bundled model: ${id}`);
      }
    } catch (err) {
      console.warn(`Failed to extract bundled model ${id}:`, err);
    }
  }
}

const MAX_RETRIES = 3;

async function downloadOnce(modelId: ModelId): Promise<void> {
  const config = MODEL_CONFIGS[modelId];
  const dir = getModelDir(modelId);

  if (!dir.exists) {
    dir.create();
  }

  const output = await File.downloadFileAsync(config.url, dir);
  if (!output.exists) {
    throw new Error(`Download failed for ${modelId}`);
  }

  /* Rename to expected filename if different */
  const expectedFile = getModelFile(modelId);
  if (output.uri !== expectedFile.uri && !expectedFile.exists) {
    output.move(dir);
  }
}

export async function downloadModel(modelId: ModelId): Promise<void> {
  /* Try bundled assets first */
  if (!isModelDownloaded(modelId)) {
    const copied = await copyFromBundledAssets(modelId);
    if (copied) return;
  }

  for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
    try {
      await downloadOnce(modelId);
      return;
    } catch (err) {
      if (attempt === MAX_RETRIES - 1) throw err;
      const delay = 1000 * Math.pow(2, attempt);
      await new Promise((r) => setTimeout(r, delay));

      /* Clean up partial download before retry */
      const dir = getModelDir(modelId);
      if (dir.exists) dir.delete();
    }
  }
}

export function deleteModel(modelId: ModelId): void {
  const dir = getModelDir(modelId);
  if (dir.exists) {
    dir.delete();
  }
}

export function getAllModelStatuses(): Record<ModelId, boolean> {
  const statuses = {} as Record<ModelId, boolean>;
  const modelIds = Object.keys(MODEL_CONFIGS) as ModelId[];
  for (const id of modelIds) {
    statuses[id] = isModelDownloaded(id);
  }
  return statuses;
}
