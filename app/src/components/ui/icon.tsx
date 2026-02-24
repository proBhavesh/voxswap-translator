/**
 * Type-safe icon wrapper around Ionicons.
 * Uses design system size and color constants.
 *
 * @example
 * <Icon name="mic" size="lg" color="brand" />
 * <Icon name="wifi" size="md" color="success" />
 */

import { Ionicons } from '@expo/vector-icons';

import { ICON_SIZES, ICON_COLORS } from '@/constants/icons';
import type { IconName, IconSize, IconColor } from '@/constants/icons';

interface IconProps {
  name: IconName;
  size?: IconSize | number;
  color?: IconColor | string;
}

export function Icon({ name, size = 'md', color = 'primary' }: IconProps) {
  const resolvedSize = typeof size === 'number' ? size : ICON_SIZES[size];
  const resolvedColor =
    typeof color === 'string' && color in ICON_COLORS
      ? ICON_COLORS[color as IconColor]
      : color;

  return <Ionicons name={name} size={resolvedSize} color={resolvedColor} />;
}
