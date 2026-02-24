import { View, Text, FlatList } from 'react-native';
import { useRouter } from 'expo-router';

import { SafeScreen } from '@/components/ui/safe-screen';
import { Button } from '@/components/ui/button';
import { Icon } from '@/components/ui/icon';
import { useModelManager } from '@/hooks/use-model-manager';
import { MODEL_CONFIGS } from '@/constants/models';
import { ALL_MODEL_IDS } from '@/constants/models';
import { COLORS } from '@/constants/colors';
import type { ModelId, ModelStatus } from '@/types';

const STATUS_CONFIG: Record<ModelStatus, { icon: 'checkmark-circle' | 'cloud-download-outline' | 'alert-circle' | 'hourglass'; color: string; label: string }> = {
  ready: { icon: 'checkmark-circle', color: COLORS.status.success, label: 'Downloaded' },
  not_downloaded: { icon: 'cloud-download-outline', color: COLORS.gray[400], label: 'Not downloaded' },
  downloading: { icon: 'hourglass', color: COLORS.status.warning, label: 'Downloading...' },
  error: { icon: 'alert-circle', color: COLORS.status.error, label: 'Error' },
};

function formatSize(bytes: number): string {
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  return `${(bytes / 1_000_000).toFixed(0)} MB`;
}

function ModelItem({ modelId, status, percent }: { modelId: ModelId; status: ModelStatus; percent: number }) {
  const config = MODEL_CONFIGS[modelId];
  const statusConfig = STATUS_CONFIG[status];

  return (
    <View className="flex-row items-center justify-between py-3 border-b border-border-light">
      <View className="flex-1 mr-3">
        <Text className="text-text-primary text-base font-medium">{config.name}</Text>
        <Text className="text-text-muted text-sm">{formatSize(config.sizeBytes)}</Text>
        {status === 'downloading' && (
          <View className="mt-1 h-1.5 bg-gray-200 rounded-full overflow-hidden">
            <View
              className="h-full bg-brand-primary rounded-full"
              style={{ width: `${Math.round(percent * 100)}%` }}
            />
          </View>
        )}
      </View>
      <View className="flex-row items-center gap-1.5">
        <Icon name={statusConfig.icon} size="sm" color={statusConfig.color} />
        <Text className="text-xs" style={{ color: statusConfig.color }}>
          {status === 'downloading' ? `${Math.round(percent * 100)}%` : statusConfig.label}
        </Text>
      </View>
    </View>
  );
}

export default function ModelDownloadScreen() {
  const router = useRouter();
  const { statuses, progress, isAllReady, downloadAllModels } = useModelManager();

  const isDownloading = ALL_MODEL_IDS.some((id) => statuses[id] === 'downloading');

  return (
    <SafeScreen>
      <View className="pt-4 pb-2">
        <Text className="text-2xl font-bold text-text-primary">Download Models</Text>
        <Text className="text-text-secondary mt-1">
          Models are required for offline translation. Download them once over WiFi.
        </Text>
      </View>

      <FlatList
        data={ALL_MODEL_IDS}
        keyExtractor={(item) => item}
        renderItem={({ item }) => (
          <ModelItem
            modelId={item}
            status={statuses[item]}
            percent={progress[item]?.percent ?? 0}
          />
        )}
        className="flex-1"
      />

      <View className="py-4 gap-3">
        {!isAllReady && (
          <Button
            onPress={downloadAllModels}
            variant="primary"
            size="lg"
            isLoading={isDownloading}
            disabled={isDownloading}
          >
            {isDownloading ? 'Downloading...' : 'Download All'}
          </Button>
        )}
        {isAllReady && (
          <Button onPress={() => router.replace('/')} variant="primary" size="lg">
            Continue
          </Button>
        )}
      </View>
    </SafeScreen>
  );
}
