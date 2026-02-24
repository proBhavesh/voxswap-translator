import { useCallback, useEffect, useRef } from 'react';
import { useConnectionStore } from '@/stores/connection-store';
import {
  connect as tcpConnect,
  disconnect as tcpDisconnect,
  setTargetLanguages,
  onStatus,
  onRegistered,
  onSession,
  getIsConnected,
} from '@/services/network-client';
import { initStreamSender, closeStreamSender } from '@/services/stream-sender';

export function useConnection() {
  const { setStatus, setSpeaker, setSession, reset } = useConnectionStore();
  const isSetup = useRef(false);

  useEffect(() => {
    if (isSetup.current) return;
    isSetup.current = true;

    const unsubStatus = onStatus(setStatus);
    const unsubSpeaker = onRegistered((speaker) => {
      setSpeaker(speaker);
      initStreamSender(speaker.speakerId).catch((err) =>
        console.error('[Connection] UDP init failed:', err),
      );
    });
    const unsubSession = onSession(setSession);

    return () => {
      unsubStatus();
      unsubSpeaker();
      unsubSession();
    };
  }, [setStatus, setSpeaker, setSession]);

  const connect = useCallback(
    (speakerName: string, sourceLanguage: string) => {
      tcpConnect(speakerName, sourceLanguage);
    },
    [],
  );

  const disconnect = useCallback(() => {
    tcpDisconnect();
    closeStreamSender();
    reset();
  }, [reset]);

  return {
    connect,
    disconnect,
    setTargetLanguages,
    isConnected: getIsConnected,
  };
}
