import TcpSocket from 'react-native-tcp-socket';
import type { ConnectionStatus, SpeakerRegistration, SessionConfig } from '@/types';
import { BOX_IP, BOX_CONTROL_PORT, HEARTBEAT_INTERVAL_MS, HEARTBEAT_TIMEOUT_MS } from '@/constants';

/* Binary message types (phone → box) */
const MSG_REGISTER = 0x01;
const MSG_HEARTBEAT = 0x02;
const MSG_SET_LANGUAGES = 0x03;

/* Binary message types (box → phone) */
const MSG_REGISTER_ACK = 0x81;
const MSG_SESSION_CONFIG = 0x82;

type StatusCallback = (status: ConnectionStatus) => void;
type SpeakerCallback = (speaker: SpeakerRegistration) => void;
type SessionCallback = (session: SessionConfig) => void;

let socket: ReturnType<typeof TcpSocket.createConnection> | null = null;
let heartbeatTimer: ReturnType<typeof setInterval> | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let reconnectAttempts = 0;

let onStatusChange: StatusCallback | null = null;
let onSpeakerRegistered: SpeakerCallback | null = null;
let onSessionUpdate: SessionCallback | null = null;

let isConnected = false;
let receiveBuffer = Buffer.alloc(0);

function encodeString(str: string): Buffer {
  const encoded = Buffer.from(str, 'utf8');
  const lenBuf = Buffer.alloc(2);
  lenBuf.writeUInt16BE(encoded.length, 0);
  return Buffer.concat([lenBuf, encoded]);
}

function decodeString(buf: Buffer, offset: number): { value: string; bytesRead: number } {
  const len = buf.readUInt16BE(offset);
  const value = buf.toString('utf8', offset + 2, offset + 2 + len);
  return { value, bytesRead: 2 + len };
}

function buildRegisterMessage(speakerName: string, sourceLanguage: string): Buffer {
  const nameBuf = encodeString(speakerName);
  const langBuf = encodeString(sourceLanguage);
  const header = Buffer.alloc(1);
  header[0] = MSG_REGISTER;
  return Buffer.concat([header, nameBuf, langBuf]);
}

function buildHeartbeatMessage(): Buffer {
  return Buffer.from([MSG_HEARTBEAT]);
}

function buildSetLanguagesMessage(lang1: string, lang2: string): Buffer {
  const header = Buffer.alloc(1);
  header[0] = MSG_SET_LANGUAGES;
  return Buffer.concat([header, encodeString(lang1), encodeString(lang2)]);
}

function parseMessage(data: Buffer): void {
  const type = data[0];

  if (type === MSG_REGISTER_ACK && data.length >= 2) {
    const speakerId = data[1];
    const isAdmin = data[2] === 1;
    let offset = 3;
    const name = decodeString(data, offset);
    offset += name.bytesRead;
    const srcLang = decodeString(data, offset);

    const speaker: SpeakerRegistration = {
      speakerId,
      speakerName: name.value,
      sourceLanguage: srcLang.value,
      isAdmin,
    };
    onSpeakerRegistered?.(speaker);
  }

  if (type === MSG_SESSION_CONFIG && data.length >= 2) {
    let offset = 1;
    const lang1 = decodeString(data, offset);
    offset += lang1.bytesRead;
    const lang2 = decodeString(data, offset);
    offset += lang2.bytesRead;
    const password = decodeString(data, offset);

    const session: SessionConfig = {
      targetLanguage1: lang1.value,
      targetLanguage2: lang2.value,
      wifiPassword: password.value,
    };
    onSessionUpdate?.(session);
  }
}

function handleData(data: Buffer): void {
  receiveBuffer = Buffer.concat([receiveBuffer, data]);

  /* Process complete messages (length-prefixed: 4-byte length header) */
  while (receiveBuffer.length >= 4) {
    const msgLen = receiveBuffer.readUInt32BE(0);
    if (receiveBuffer.length < 4 + msgLen) break;

    const msg = receiveBuffer.subarray(4, 4 + msgLen);
    receiveBuffer = receiveBuffer.subarray(4 + msgLen);
    parseMessage(msg);
  }
}

function startHeartbeat(): void {
  stopHeartbeat();
  heartbeatTimer = setInterval(() => {
    if (socket && isConnected) {
      const msg = buildHeartbeatMessage();
      const lenBuf = Buffer.alloc(4);
      lenBuf.writeUInt32BE(msg.length, 0);
      socket.write(Buffer.concat([lenBuf, msg]));
    }
  }, HEARTBEAT_INTERVAL_MS);
}

function stopHeartbeat(): void {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
    heartbeatTimer = null;
  }
}

function scheduleReconnect(): void {
  if (reconnectTimer) return;

  const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000);
  reconnectAttempts++;

  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    /* The caller must re-invoke connect() */
    onStatusChange?.('connecting');
  }, delay);
}

export function connect(speakerName: string, sourceLanguage: string): void {
  if (isConnected) return;

  onStatusChange?.('connecting');

  socket = TcpSocket.createConnection(
    { host: BOX_IP, port: BOX_CONTROL_PORT },
    () => {
      isConnected = true;
      reconnectAttempts = 0;
      onStatusChange?.('connected');

      /* Send REGISTER */
      const msg = buildRegisterMessage(speakerName, sourceLanguage);
      const lenBuf = Buffer.alloc(4);
      lenBuf.writeUInt32BE(msg.length, 0);
      socket!.write(Buffer.concat([lenBuf, msg]));

      startHeartbeat();
    },
  );

  socket.on('data', (data) => {
    handleData(Buffer.from(data));
  });

  socket.on('error', (error) => {
    console.error('[Network] TCP error:', error);
    onStatusChange?.('error');
  });

  socket.on('close', () => {
    isConnected = false;
    stopHeartbeat();
    onStatusChange?.('disconnected');
    scheduleReconnect();
  });
}

export function disconnect(): void {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  stopHeartbeat();

  if (socket) {
    socket.destroy();
    socket = null;
  }
  isConnected = false;
  receiveBuffer = Buffer.alloc(0);
  reconnectAttempts = 0;
}

export function setTargetLanguages(lang1: string, lang2: string): void {
  if (!socket || !isConnected) return;

  const msg = buildSetLanguagesMessage(lang1, lang2);
  const lenBuf = Buffer.alloc(4);
  lenBuf.writeUInt32BE(msg.length, 0);
  socket.write(Buffer.concat([lenBuf, msg]));
}

export function onStatus(cb: StatusCallback): () => void {
  onStatusChange = cb;
  return () => { onStatusChange = null; };
}

export function onRegistered(cb: SpeakerCallback): () => void {
  onSpeakerRegistered = cb;
  return () => { onSpeakerRegistered = null; };
}

export function onSession(cb: SessionCallback): () => void {
  onSessionUpdate = cb;
  return () => { onSessionUpdate = null; };
}

export function getIsConnected(): boolean {
  return isConnected;
}
