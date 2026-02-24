import '../global.css';

import { useEffect, useState } from 'react';
import { View, ActivityIndicator } from 'react-native';
import { Stack, useRouter, useSegments } from 'expo-router';
import { StatusBar } from 'expo-status-bar';

import { COLORS } from '@/constants/colors';
import { REQUIRED_MODEL_IDS } from '@/constants/models';
import { useModelStore } from '@/stores/model-store';
import { isModelDownloaded, ensureModelDir } from '@/services/model-manager';

function useModelGuard() {
  const [isChecking, setIsChecking] = useState(true);
  const [isModelsReady, setIsModelsReady] = useState(false);
  const setModelStatus = useModelStore((s) => s.setModelStatus);

  useEffect(() => {
    ensureModelDir();
    let allReady = true;
    for (const id of REQUIRED_MODEL_IDS) {
      const downloaded = isModelDownloaded(id);
      setModelStatus(id, downloaded ? 'ready' : 'not_downloaded');
      if (!downloaded) allReady = false;
    }
    setIsModelsReady(allReady);
    setIsChecking(false);
  }, [setModelStatus]);

  return { isChecking, isModelsReady };
}

export default function RootLayout() {
  const router = useRouter();
  const segments = useSegments();
  const { isChecking, isModelsReady } = useModelGuard();

  useEffect(() => {
    if (isChecking) return;
    const isOnDownloadScreen = (segments[0] as string) === 'model-download';

    if (!isModelsReady && !isOnDownloadScreen) {
      router.replace('/model-download' as never);
    }
  }, [isChecking, isModelsReady, segments, router]);

  if (isChecking) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: COLORS.background.primary }}>
        <ActivityIndicator size="large" color={COLORS.brand.primary} />
      </View>
    );
  }

  return (
    <>
      <StatusBar style="dark" />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: COLORS.background.primary },
        }}
      />
    </>
  );
}
