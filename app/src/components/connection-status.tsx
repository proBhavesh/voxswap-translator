import { View, Text } from 'react-native';

import { Icon } from '@/components/ui/icon';
import { useConnectionStore } from '@/stores/connection-store';
import { COLORS } from '@/constants/colors';
import { BOX_IP } from '@/constants';
import type { IconName } from '@/constants/icons';
import type { ConnectionStatus as StatusType } from '@/types';

const STATUS_CONFIG: Record<StatusType, { color: string; icon: IconName; label: string }> = {
  connected: { color: COLORS.status.success, icon: 'wifi', label: 'Connected' },
  connecting: { color: COLORS.status.warning, icon: 'sync-outline', label: 'Connecting...' },
  disconnected: { color: COLORS.gray[400], icon: 'wifi-outline', label: 'Disconnected' },
  error: { color: COLORS.status.error, icon: 'warning-outline', label: 'Error' },
};

export function ConnectionStatusCard() {
  const status = useConnectionStore((s) => s.status);
  const speaker = useConnectionStore((s) => s.speaker);
  const cfg = STATUS_CONFIG[status];

  return (
    <View className="bg-bg-secondary rounded-xl px-4 py-3 border border-border-light">
      <View className="flex-row items-center gap-3">
        <View
          className="w-10 h-10 rounded-full items-center justify-center"
          style={{ backgroundColor: cfg.color + '20' }}
        >
          <Icon name={cfg.icon} size="md" color="primary" />
        </View>
        <View className="flex-1">
          <Text className="text-text-primary text-base font-medium">
            {cfg.label}
          </Text>
          <Text className="text-text-muted text-sm">
            {status === 'connected'
              ? `Box at ${BOX_IP} • Speaker #${speaker?.speakerId ?? '?'}`
              : `Target: ${BOX_IP}`}
          </Text>
        </View>
        <View
          className="h-3 w-3 rounded-full"
          style={{ backgroundColor: cfg.color }}
        />
      </View>
    </View>
  );
}
