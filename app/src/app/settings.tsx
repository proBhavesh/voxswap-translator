import { View, Text, ScrollView } from 'react-native';
import { useRouter } from 'expo-router';

import { SafeScreen } from '@/components/ui/safe-screen';
import { Button } from '@/components/ui/button';
import { ConnectionStatusCard } from '@/components/connection-status';
import { useConnectionStore } from '@/stores/connection-store';
import { useModelStore } from '@/stores/model-store';
import { MODEL_CONFIGS, ALL_MODEL_IDS } from '@/constants/models';
import { COLORS } from '@/constants/colors';
import type { ModelStatus, ModelId } from '@/types';

const STATUS_LABEL: Record<ModelStatus, { text: string; color: string }> = {
  ready: { text: 'Downloaded', color: COLORS.status.success },
  downloading: { text: 'Downloading...', color: COLORS.status.warning },
  not_downloaded: { text: 'Not downloaded', color: COLORS.gray[400] },
  error: { text: 'Error', color: COLORS.status.error },
};

function formatBytes(bytes: number): string {
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
  if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(0)} MB`;
  return `${(bytes / 1_000).toFixed(0)} KB`;
}

function ModelRow({ modelId }: { modelId: ModelId }) {
  const status = useModelStore((s) => s.statuses[modelId]) ?? 'not_downloaded';
  const config = MODEL_CONFIGS[modelId];
  const statusCfg = STATUS_LABEL[status];

  return (
    <View className="flex-row items-center justify-between py-2.5 border-b border-border-light">
      <View className="flex-1">
        <Text className="text-text-primary text-sm font-medium">
          {config.name}
        </Text>
        <Text className="text-text-muted text-xs">
          {formatBytes(config.sizeBytes)}
        </Text>
      </View>
      <Text className="text-xs font-medium" style={{ color: statusCfg.color }}>
        {statusCfg.text}
      </Text>
    </View>
  );
}

export default function SettingsScreen() {
  const router = useRouter();
  const speaker = useConnectionStore((s) => s.speaker);

  return (
    <SafeScreen>
      <View className="flex-row items-center justify-between pt-2 pb-4">
        <Text className="text-xl font-bold text-text-primary">Settings</Text>
        <Button onPress={() => router.back()} variant="outline" size="sm">
          Back
        </Button>
      </View>

      <ScrollView className="flex-1" showsVerticalScrollIndicator={false}>
        {/* Connection */}
        <Text className="text-sm font-semibold text-text-secondary uppercase tracking-wider mb-2">
          Connection
        </Text>
        <ConnectionStatusCard />

        {speaker && (
          <View className="mt-2 bg-bg-secondary rounded-xl border border-border-light px-4 py-3">
            <Text className="text-text-muted text-xs uppercase tracking-wider mb-1">
              Speaker
            </Text>
            <Text className="text-text-primary text-base font-medium">
              {speaker.speakerName}
              {speaker.isAdmin ? ' (Admin)' : ''}
            </Text>
          </View>
        )}

        {/* Models */}
        <Text className="text-sm font-semibold text-text-secondary uppercase tracking-wider mt-6 mb-2">
          Models
        </Text>
        <View className="bg-bg-secondary rounded-xl border border-border-light px-4">
          {ALL_MODEL_IDS.map((id) => (
            <ModelRow key={id} modelId={id} />
          ))}
        </View>

        <Button
          onPress={() => router.push('/model-download' as never)}
          variant="outline"
          size="sm"
          className="mt-3"
        >
          Re-download Models
        </Button>

        {/* App info */}
        <Text className="text-sm font-semibold text-text-secondary uppercase tracking-wider mt-6 mb-2">
          About
        </Text>
        <View className="bg-bg-secondary rounded-xl border border-border-light px-4 py-3">
          <Text className="text-text-primary text-sm">VoxSwap Translator</Text>
          <Text className="text-text-muted text-xs mt-1">v0.1.0</Text>
        </View>
      </ScrollView>
    </SafeScreen>
  );
}
