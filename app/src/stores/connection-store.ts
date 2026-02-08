import { create } from 'zustand';
import type { ConnectionStatus, SpeakerRegistration, SessionConfig } from '@/types';

interface ConnectionState {
  status: ConnectionStatus;
  speaker: SpeakerRegistration | null;
  session: SessionConfig | null;

  setStatus: (status: ConnectionStatus) => void;
  setSpeaker: (speaker: SpeakerRegistration | null) => void;
  setSession: (session: SessionConfig | null) => void;
  reset: () => void;
}

export const useConnectionStore = create<ConnectionState>((set) => ({
  status: 'disconnected',
  speaker: null,
  session: null,

  setStatus: (status) => set({ status }),
  setSpeaker: (speaker) => set({ speaker }),
  setSession: (session) => set({ session }),
  reset: () => set({ status: 'disconnected', speaker: null, session: null }),
}));
