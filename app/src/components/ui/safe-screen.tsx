/**
 * Screen wrapper with safe area insets and consistent background.
 * Use this as the root view for every screen.
 *
 * @example
 * <SafeScreen>
 *   <Text>Screen content</Text>
 * </SafeScreen>
 */

import type { ReactNode } from 'react';
import { View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

interface SafeScreenProps {
  children: ReactNode;
  className?: string;
}

export function SafeScreen({ children, className = '' }: SafeScreenProps) {
  return (
    <SafeAreaView className="flex-1 bg-bg-primary" edges={['top', 'bottom']}>
      <View className={`flex-1 px-6 ${className}`}>{children}</View>
    </SafeAreaView>
  );
}
