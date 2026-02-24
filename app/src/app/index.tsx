import { View, Text, Pressable } from 'react-native';
import { useRouter } from 'expo-router';

import { SafeScreen } from '@/components/ui/safe-screen';
import { Button } from '@/components/ui/button';
import { Icon } from '@/components/ui/icon';
import { useConnectionStore } from '@/stores/connection-store';
import { useTranslationStore } from '@/stores/translation-store';
import { usePipeline } from '@/hooks/use-pipeline';
import { useBatteryMonitor } from '@/hooks/use-battery-monitor';
import { SUPPORTED_LANGUAGES } from '@/constants';
import { COLORS } from '@/constants/colors';
import { ACTION_ICONS } from '@/constants/icons';
import type { ConnectionStatus, TranslationStatus } from '@/types';

const CONNECTION_INDICATOR: Record<
  ConnectionStatus,
  { color: string; label: string }
> = {
  connected: { color: COLORS.status.success, label: 'Connected' },
  connecting: { color: COLORS.status.warning, label: 'Connecting...' },
  disconnected: { color: COLORS.gray[400], label: 'Disconnected' },
  error: { color: COLORS.status.error, label: 'Connection Error' },
};

const STATUS_TEXT: Record<TranslationStatus, string> = {
  idle: 'Tap to start translating',
  loading_models: 'Loading models...',
  ready: 'Tap to start translating',
  translating: 'Tap to stop',
  error: 'Error — tap to retry',
};

function getLanguageName(code: string): string {
  return (
    SUPPORTED_LANGUAGES.find((lang) => lang.code === code)?.name ?? code
  );
}

export default function HomeScreen() {
  const router = useRouter();

  const connectionStatus = useConnectionStore((s) => s.status);
  const session = useConnectionStore((s) => s.session);

  const sourceLanguage = useTranslationStore((s) => s.sourceLanguage);
  const targetLanguage1 = useTranslationStore((s) => s.targetLanguage1);
  const targetLanguage2 = useTranslationStore((s) => s.targetLanguage2);
  const lastCaption = useTranslationStore((s) => s.lastCaption);
  const audioLevel = useTranslationStore((s) => s.audioLevel);
  const isRecording = useTranslationStore((s) => s.isRecording);
  const translationStatus = useTranslationStore((s) => s.status);

  const { start, stop } = usePipeline();
  const battery = useBatteryMonitor();

  const isLoading = translationStatus === 'loading_models';
  const isTranslating = translationStatus === 'translating';
  const indicator = CONNECTION_INDICATOR[connectionStatus];

  const handleToggle = async () => {
    if (isLoading) return;
    if (isRecording || isTranslating) {
      await stop();
    } else {
      await start();
    }
  };

  return (
    <SafeScreen>
      {/* Header */}
      <View className="flex-row items-center justify-between pt-2 pb-4">
        <View className="flex-row items-center gap-2">
          <View
            className="h-2.5 w-2.5 rounded-full"
            style={{ backgroundColor: indicator.color }}
          />
          <Text className="text-sm text-text-secondary">
            {indicator.label}
          </Text>
        </View>

        <Pressable
          onPress={() => router.push('/settings')}
          hitSlop={{ top: 12, bottom: 12, left: 12, right: 12 }}
        >
          <Icon name="settings-outline" size="lg" color="secondary" />
        </Pressable>
      </View>

      {/* Language selector */}
      <Pressable
        onPress={() => router.push('/language-select')}
        className="bg-bg-secondary rounded-xl px-4 py-3 flex-row items-center justify-between border border-border-light"
      >
        <View className="flex-1 flex-row items-center gap-3">
          <Icon name="language" size="md" color="brand" />
          <Text className="text-text-primary text-base font-medium">
            {getLanguageName(sourceLanguage)}
          </Text>
          <Icon name="arrow-forward" size="sm" color="muted" />
          <View className="flex-1">
            <Text className="text-text-secondary text-sm" numberOfLines={1}>
              {session
                ? `${getLanguageName(session.targetLanguage1)}, ${getLanguageName(session.targetLanguage2)}`
                : `${getLanguageName(targetLanguage1)}, ${getLanguageName(targetLanguage2)}`}
            </Text>
          </View>
        </View>
        <Icon name="chevron-forward" size="sm" color="muted" />
      </Pressable>

      {/* Center: Mic button */}
      <View className="flex-1 items-center justify-center">
        <View
          className="items-center justify-center rounded-full"
          style={{
            width: 160,
            height: 160,
            backgroundColor: isTranslating
              ? `rgba(220, 38, 38, ${0.15 + audioLevel * 0.2})`
              : `rgba(79, 70, 229, ${0.12 + audioLevel * 0.15})`,
          }}
        >
          <Pressable
            onPress={handleToggle}
            disabled={isLoading}
            className="items-center justify-center rounded-full active:opacity-85"
            style={{
              width: 120,
              height: 120,
              backgroundColor: isLoading
                ? COLORS.gray[400]
                : isTranslating
                  ? COLORS.status.error
                  : COLORS.brand.primary,
              opacity: isLoading ? 0.7 : 1,
            }}
          >
            <Icon
              name={isTranslating ? ACTION_ICONS.stop : ACTION_ICONS.mic}
              size="3xl"
              color="white"
            />
          </Pressable>
        </View>

        <Text className="text-text-muted text-sm mt-6">
          {STATUS_TEXT[translationStatus]}
        </Text>
      </View>

      {/* Battery warning */}
      {battery.isLow && isTranslating && (
        <View
          className="rounded-xl px-4 py-2 mb-2 flex-row items-center gap-2"
          style={{ backgroundColor: COLORS.status.warningMuted, borderWidth: 1, borderColor: COLORS.status.warning + '40' }}
        >
          <Icon name="battery-dead" size="sm" color="warning" />
          <Text className="text-sm flex-1" style={{ color: COLORS.status.warning }}>
            Low battery ({Math.round(battery.level * 100)}%) — plug in for best performance
          </Text>
        </View>
      )}

      {/* Caption area */}
      <View className="bg-bg-secondary border border-border-light rounded-xl px-4 py-4 mb-4 min-h-[80px] justify-center">
        <Text
          className={`text-center text-base ${lastCaption ? 'text-text-primary' : 'text-text-muted'}`}
        >
          {lastCaption || 'Captions will appear here'}
        </Text>
      </View>

      {/* Bottom action */}
      <View className="pb-2">
        <Button
          onPress={() => router.push('/language-select')}
          variant="outline"
          size="md"
        >
          Change Languages
        </Button>
      </View>
    </SafeScreen>
  );
}
