declare module '@fugood/react-native-audio-pcm-stream' {
  interface Options {
    sampleRate: number;
    channels: number;
    bitsPerSample: number;
    audioSource?: number;
    wavFile: string;
    bufferSize?: number;
  }

  interface AudioRecord {
    init: (options: Options) => void;
    start: () => void;
    stop: () => Promise<string>;
    on: (event: 'data', callback: (data: string) => void) => void;
  }

  const audioRecord: AudioRecord;
  export default audioRecord;
}
