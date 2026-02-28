package nie.translator.rtranslator.voice_translation.networking;

/**
 * Resamples 16-bit little-endian PCM audio to 16000 Hz using linear interpolation.
 * Android TTS outputs at 22050 or 24000 Hz; the box expects 16kHz.
 * Linear interpolation is adequate for speech — energy is concentrated below 4kHz.
 */
public final class PcmResampler {

    private static final int TARGET_SAMPLE_RATE = 16000;

    private PcmResampler() {}

    /**
     * Resample 16-bit LE PCM from sourceSampleRate to 16000 Hz.
     * @param input byte array of 16-bit little-endian samples
     * @param sourceSampleRate the source sample rate (e.g. 22050, 24000)
     * @return resampled byte array at 16000 Hz. Returns input unchanged if already 16000 Hz.
     */
    public static byte[] resampleTo16kHz(byte[] input, int sourceSampleRate) {
        if (input == null || input.length < 2) return input;
        if (sourceSampleRate <= 0) return input;
        if (sourceSampleRate == TARGET_SAMPLE_RATE) return input;

        int inputSamples = input.length / 2;
        int outputSamples = (int) ((long) inputSamples * TARGET_SAMPLE_RATE / sourceSampleRate);
        byte[] output = new byte[outputSamples * 2];

        double ratio = (double) sourceSampleRate / TARGET_SAMPLE_RATE;
        for (int i = 0; i < outputSamples; i++) {
            double srcPos = i * ratio;
            int srcIndex = (int) srcPos;
            double frac = srcPos - srcIndex;

            short s0 = readSample(input, srcIndex);
            short s1 = (srcIndex + 1 < inputSamples) ? readSample(input, srcIndex + 1) : s0;
            short interpolated = (short) (s0 + frac * (s1 - s0));

            writeSample(output, i, interpolated);
        }
        return output;
    }

    private static short readSample(byte[] buf, int sampleIndex) {
        int byteIndex = sampleIndex * 2;
        return (short) ((buf[byteIndex] & 0xFF) | (buf[byteIndex + 1] << 8));
    }

    private static void writeSample(byte[] buf, int sampleIndex, short value) {
        int byteIndex = sampleIndex * 2;
        buf[byteIndex] = (byte) (value & 0xFF);
        buf[byteIndex + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
