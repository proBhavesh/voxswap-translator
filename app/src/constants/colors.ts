/**
 * VoxSwap design system color palette.
 * Light theme — clean, minimal, high contrast.
 * Inspired by Linear, Vercel, and Apple HIG patterns.
 */

export const COLORS = {
  brand: {
    primary: '#4F46E5',
    primaryLight: '#6366F1',
    primaryDark: '#4338CA',
    accent: '#0EA5E9',
    accentDark: '#0284C7',
  },

  text: {
    primary: '#111827',
    secondary: '#6B7280',
    muted: '#9CA3AF',
    inverse: '#FFFFFF',
    accent: '#4F46E5',
  },

  background: {
    primary: '#FFFFFF',
    secondary: '#F9FAFB',
    card: '#F3F4F6',
    elevated: '#FFFFFF',
    overlay: 'rgba(0, 0, 0, 0.4)',
  },

  status: {
    success: '#16A34A',
    successMuted: '#DCFCE7',
    error: '#DC2626',
    errorMuted: '#FEE2E2',
    warning: '#D97706',
    warningMuted: '#FEF3C7',
    info: '#2563EB',
    infoMuted: '#DBEAFE',
  },

  gray: {
    50: '#F9FAFB',
    100: '#F3F4F6',
    200: '#E5E7EB',
    300: '#D1D5DB',
    400: '#9CA3AF',
    500: '#6B7280',
    600: '#4B5563',
    700: '#374151',
    800: '#1F2937',
    900: '#111827',
  },

  border: {
    light: '#F3F4F6',
    default: '#E5E7EB',
    dark: '#D1D5DB',
  },

  transparent: 'transparent',
  white: '#FFFFFF',
  black: '#000000',
} as const;

type NestedValues<T> = T extends string
  ? T
  : T extends Record<string, infer V>
    ? NestedValues<V>
    : never;

export type ColorValue = NestedValues<typeof COLORS>;
