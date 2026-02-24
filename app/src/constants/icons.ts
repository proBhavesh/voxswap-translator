/**
 * VoxSwap design system icon constants.
 * Uses Ionicons via @expo/vector-icons.
 */

import type { ComponentProps } from 'react';
import type { Ionicons } from '@expo/vector-icons';

import { COLORS } from './colors';

export type IconName = ComponentProps<typeof Ionicons>['name'];

export const ICON_SIZES = {
  xs: 14,
  sm: 16,
  md: 20,
  lg: 24,
  xl: 28,
  '2xl': 32,
  '3xl': 48,
  '4xl': 64,
} as const;

export type IconSize = keyof typeof ICON_SIZES;

export const ICON_COLORS = {
  primary: COLORS.text.primary,
  secondary: COLORS.text.secondary,
  muted: COLORS.text.muted,
  brand: COLORS.brand.primary,
  accent: COLORS.brand.accent,
  success: COLORS.status.success,
  error: COLORS.status.error,
  warning: COLORS.status.warning,
  info: COLORS.status.info,
  white: COLORS.white,
  inverse: COLORS.white,
} as const;

export type IconColor = keyof typeof ICON_COLORS;

export const NAV_ICONS = {
  home: 'home',
  homeOutline: 'home-outline',
  settings: 'settings',
  settingsOutline: 'settings-outline',
  language: 'language',
  back: 'chevron-back',
  close: 'close',
} as const satisfies Record<string, IconName>;

export const ACTION_ICONS = {
  mic: 'mic',
  micOff: 'mic-off',
  play: 'play',
  stop: 'stop',
  send: 'send',
  refresh: 'refresh',
  swap: 'swap-horizontal',
  volume: 'volume-high',
  volumeOff: 'volume-mute',
} as const satisfies Record<string, IconName>;

export const STATUS_ICONS = {
  connected: 'wifi',
  disconnected: 'wifi-outline',
  error: 'alert-circle',
  loading: 'hourglass',
  success: 'checkmark-circle',
  warning: 'warning',
  info: 'information-circle',
} as const satisfies Record<string, IconName>;
