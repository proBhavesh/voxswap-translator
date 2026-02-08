import { View, Text } from 'react-native';

export default function LanguageSelectScreen() {
  return (
    <View className="flex-1 items-center justify-center bg-[#1a1a2e] px-6">
      <Text className="text-2xl font-bold text-white">Language Selection</Text>
      <Text className="text-gray-400 mt-2">Pick source and target languages</Text>
    </View>
  );
}
