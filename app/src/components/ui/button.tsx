/**
 * Reusable button component with variant and size system.
 *
 * @example
 * <Button onPress={handlePress} variant="primary" size="lg">Start</Button>
 * <Button onPress={handlePress} variant="outline" isLoading>Connecting</Button>
 */

import { ActivityIndicator, Pressable, Text } from 'react-native';

import { COLORS } from '@/constants/colors';

type ButtonVariant = 'primary' | 'secondary' | 'outline';
type ButtonSize = 'sm' | 'md' | 'lg';

interface ButtonProps {
  onPress: () => void;
  children: string;
  variant?: ButtonVariant;
  size?: ButtonSize;
  isLoading?: boolean;
  disabled?: boolean;
  className?: string;
}

const VARIANT_STYLES: Record<ButtonVariant, { container: string; text: string }> = {
  primary: {
    container: 'bg-brand-primary',
    text: 'text-white',
  },
  secondary: {
    container: 'bg-bg-card',
    text: 'text-text-primary',
  },
  outline: {
    container: 'bg-transparent border border-border',
    text: 'text-text-secondary',
  },
};

const SIZE_STYLES: Record<ButtonSize, { container: string; text: string }> = {
  sm: {
    container: 'py-2 px-3 rounded-md',
    text: 'text-sm',
  },
  md: {
    container: 'py-3 px-4 rounded-lg',
    text: 'text-base',
  },
  lg: {
    container: 'py-4 px-6 rounded-xl',
    text: 'text-lg',
  },
};

const SPINNER_COLORS: Record<ButtonVariant, string> = {
  primary: COLORS.white,
  secondary: COLORS.text.primary,
  outline: COLORS.text.secondary,
};

export function Button({
  onPress,
  children,
  variant = 'primary',
  size = 'md',
  isLoading = false,
  disabled = false,
  className = '',
}: ButtonProps) {
  const isDisabled = disabled || isLoading;
  const variantStyle = VARIANT_STYLES[variant];
  const sizeStyle = SIZE_STYLES[size];

  return (
    <Pressable
      onPress={onPress}
      disabled={isDisabled}
      className={`flex-row items-center justify-center ${sizeStyle.container} ${variantStyle.container} ${isDisabled ? 'opacity-50' : ''} ${className}`}
      style={({ pressed }) =>
        pressed && !isDisabled ? { opacity: 0.7 } : undefined
      }
    >
      {isLoading ? (
        <ActivityIndicator
          size="small"
          color={SPINNER_COLORS[variant]}
          className="mr-2"
        />
      ) : null}
      <Text
        className={`font-semibold tracking-wide ${sizeStyle.text} ${variantStyle.text}`}
      >
        {children}
      </Text>
    </Pressable>
  );
}
