import { View, Text } from 'react-native';
import { useRouter } from 'expo-router';

import { SafeScreen } from '@/components/ui/safe-screen';
import { Button } from '@/components/ui/button';
import { LanguagePicker } from '@/components/language-picker';
import { useTranslationStore } from '@/stores/translation-store';
import { useConnectionStore } from '@/stores/connection-store';
import { SUPPORTED_LANGUAGES } from '@/constants';

function getLanguageName(code: string): string {
  if (code === 'auto') return 'Auto-detect';
  return SUPPORTED_LANGUAGES.find((l) => l.code === code)?.name ?? code;
}

export default function LanguageSelectScreen() {
  const router = useRouter();
  const sourceLanguage = useTranslationStore((s) => s.sourceLanguage);
  const setSourceLanguage = useTranslationStore((s) => s.setSourceLanguage);
  const targetLanguage1 = useTranslationStore((s) => s.targetLanguage1);
  const targetLanguage2 = useTranslationStore((s) => s.targetLanguage2);
  const setTargetLanguage1 = useTranslationStore((s) => s.setTargetLanguage1);
  const setTargetLanguage2 = useTranslationStore((s) => s.setTargetLanguage2);
  const session = useConnectionStore((s) => s.session);

  return (
    <SafeScreen>
      <View className="flex-row items-center justify-between pt-2 pb-4">
        <Text className="text-xl font-bold text-text-primary">Languages</Text>
        <Button onPress={() => router.back()} variant="outline" size="sm">
          Done
        </Button>
      </View>

      <Text className="text-sm font-semibold text-text-secondary uppercase tracking-wider mb-2">
        Your language
      </Text>

      <View className="bg-bg-secondary rounded-xl border border-border-light overflow-hidden mb-4" style={{ maxHeight: 200 }}>
        <LanguagePicker
          selectedCode={sourceLanguage}
          onSelect={setSourceLanguage}
          showAutoDetect
        />
      </View>

      {session ? (
        <View className="mb-4">
          <Text className="text-sm font-semibold text-text-secondary uppercase tracking-wider mb-2">
            Target languages (set by admin)
          </Text>
          <View className="bg-bg-secondary rounded-xl border border-border-light px-4 py-3 gap-1">
            <Text className="text-text-primary text-base">
              {getLanguageName(session.targetLanguage1)}
            </Text>
            <Text className="text-text-primary text-base">
              {getLanguageName(session.targetLanguage2)}
            </Text>
          </View>
        </View>
      ) : (
        <>
          <Text className="text-sm font-semibold text-text-secondary uppercase tracking-wider mb-2">
            Target language 1
          </Text>
          <View className="bg-bg-secondary rounded-xl border border-border-light overflow-hidden mb-4" style={{ maxHeight: 200 }}>
            <LanguagePicker
              selectedCode={targetLanguage1}
              onSelect={setTargetLanguage1}
            />
          </View>

          <Text className="text-sm font-semibold text-text-secondary uppercase tracking-wider mb-2">
            Target language 2
          </Text>
          <View className="bg-bg-secondary rounded-xl border border-border-light overflow-hidden mb-4" style={{ maxHeight: 200 }}>
            <LanguagePicker
              selectedCode={targetLanguage2}
              onSelect={setTargetLanguage2}
            />
          </View>
        </>
      )}
    </SafeScreen>
  );
}
