package nie.translator.vtranslator.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Shared audio conversion utilities used by both the passthrough pipeline
 * (WalkieTalkieService) and Piper TTS engine.
 */
public final class AudioUtils {
    private AudioUtils() {}

    /** Convert a range of short PCM16 samples to little-endian byte array.
     * Uses ByteBuffer bulk put — compiles to memcpy on ARM (little-endian). */
    public static byte[] shortToPcmBytes(short[] samples, int offset, int length) {
        byte[] pcm = new byte[length * 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().put(samples, offset, length);
        return pcm;
    }

    /** Convert float samples [-1.0, 1.0] to 16-bit signed PCM bytes (little-endian). */
    public static byte[] floatToPcm16(float[] samples, int length) {
        byte[] pcm = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            float clamped = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            short val = (short) (clamped * 32767);
            pcm[i * 2] = (byte) (val & 0xFF);
            pcm[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }
        return pcm;
    }
}
