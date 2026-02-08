import { View, Text, Pressable } from 'react-native';
import { useRouter } from 'expo-router';

export default function HomeScreen() {
  const router = useRouter();

  return (
    <View className="flex-1 items-center justify-center bg-[#1a1a2e] px-6">
      <Text className="text-4xl font-bold text-white mb-2">VoxSwap</Text>
      <Text className="text-lg text-gray-400 mb-12">Real-time translation</Text>

      <Pressable
        className="w-full bg-indigo-600 rounded-2xl py-4 items-center mb-4 active:bg-indigo-700"
        onPress={() => router.push('/language-select')}
      >
        <Text className="text-white text-lg font-semibold">Start Translating</Text>
      </Pressable>

      <Pressable
        className="w-full border border-gray-600 rounded-2xl py-4 items-center active:bg-gray-800"
        onPress={() => router.push('/settings')}
      >
        <Text className="text-gray-300 text-lg">Settings</Text>
      </Pressable>
    </View>
  );
}
