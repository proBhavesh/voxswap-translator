import { useState, useMemo, useCallback } from 'react';
import { View, Text, FlatList, Pressable, TextInput } from 'react-native';

import { Icon } from '@/components/ui/icon';
import { SUPPORTED_LANGUAGES } from '@/constants';
import { COLORS } from '@/constants/colors';
import type { Language } from '@/types';

interface LanguagePickerProps {
  selectedCode: string;
  onSelect: (code: string) => void;
  showAutoDetect?: boolean;
}

export function LanguagePicker({
  selectedCode,
  onSelect,
  showAutoDetect = false,
}: LanguagePickerProps) {
  const [search, setSearch] = useState('');

  const filtered = useMemo(() => {
    if (!search.trim()) return SUPPORTED_LANGUAGES;
    const q = search.toLowerCase();
    return SUPPORTED_LANGUAGES.filter(
      (lang) =>
        lang.name.toLowerCase().includes(q) ||
        lang.nativeName.toLowerCase().includes(q) ||
        lang.code.includes(q),
    );
  }, [search]);

  const renderItem = useCallback(
    ({ item }: { item: Language }) => {
      const isSelected = item.code === selectedCode;
      return (
        <Pressable
          onPress={() => onSelect(item.code)}
          className={`flex-row items-center justify-between px-4 py-3 border-b border-border-light ${
            isSelected ? 'bg-brand-primary/5' : ''
          }`}
        >
          <View className="flex-1">
            <Text className="text-text-primary text-base font-medium">
              {item.name}
            </Text>
            <Text className="text-text-muted text-sm">{item.nativeName}</Text>
          </View>
          <View className="flex-row items-center gap-2">
            <View
              className="px-2 py-0.5 rounded"
              style={{
                backgroundColor:
                  item.ttsEngine === 'kokoro'
                    ? COLORS.status.infoMuted
                    : COLORS.status.warningMuted,
              }}
            >
              <Text className="text-xs font-medium" style={{
                color: item.ttsEngine === 'kokoro'
                  ? COLORS.status.info
                  : COLORS.status.warning,
              }}>
                {item.ttsEngine === 'kokoro' ? 'Kokoro' : 'Piper'}
              </Text>
            </View>
            {isSelected && (
              <Icon name="checkmark" size="md" color="brand" />
            )}
          </View>
        </Pressable>
      );
    },
    [selectedCode, onSelect],
  );

  return (
    <View className="flex-1">
      <View className="px-4 py-2">
        <TextInput
          value={search}
          onChangeText={setSearch}
          placeholder="Search languages..."
          placeholderTextColor={COLORS.text.muted}
          className="bg-bg-secondary text-text-primary text-base px-3 py-2.5 rounded-lg border border-border-light"
        />
      </View>

      {showAutoDetect && (
        <Pressable
          onPress={() => onSelect('auto')}
          className={`flex-row items-center justify-between px-4 py-3 border-b border-border-light ${
            selectedCode === 'auto' ? 'bg-brand-primary/5' : ''
          }`}
        >
          <View>
            <Text className="text-text-primary text-base font-medium">
              Auto-detect
            </Text>
            <Text className="text-text-muted text-sm">
              Let Whisper detect the language
            </Text>
          </View>
          {selectedCode === 'auto' && (
            <Icon name="checkmark" size="md" color="brand" />
          )}
        </Pressable>
      )}

      <FlatList
        data={filtered}
        keyExtractor={(item) => item.code}
        renderItem={renderItem}
      />
    </View>
  );
}
