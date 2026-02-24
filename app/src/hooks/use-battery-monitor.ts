import { useEffect, useState, useRef, useCallback } from 'react';
import * as Battery from 'expo-battery';

const LOW_BATTERY_THRESHOLD = 0.2;
const POLL_INTERVAL_MS = 30_000;

interface BatteryState {
  level: number;
  isLow: boolean;
  isCharging: boolean;
}

export function useBatteryMonitor(): BatteryState {
  const [level, setLevel] = useState(1);
  const [isCharging, setIsCharging] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const poll = useCallback(async () => {
    try {
      const [battLevel, battState] = await Promise.all([
        Battery.getBatteryLevelAsync(),
        Battery.getBatteryStateAsync(),
      ]);
      setLevel(battLevel);
      setIsCharging(
        battState === Battery.BatteryState.CHARGING ||
        battState === Battery.BatteryState.FULL,
      );
    } catch {
      /* Battery API may not be available on all devices */
    }
  }, []);

  useEffect(() => {
    poll();
    timerRef.current = setInterval(poll, POLL_INTERVAL_MS);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [poll]);

  return {
    level,
    isLow: level <= LOW_BATTERY_THRESHOLD && !isCharging,
    isCharging,
  };
}
