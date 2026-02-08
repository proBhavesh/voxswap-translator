import { View, Text } from 'react-native';

export default function SettingsScreen() {
  return (
    <View className="flex-1 items-center justify-center bg-[#1a1a2e] px-6">
      <Text className="text-2xl font-bold text-white">Settings</Text>
      <Text className="text-gray-400 mt-2">Box configuration and connection status</Text>
    </View>
  );
}
