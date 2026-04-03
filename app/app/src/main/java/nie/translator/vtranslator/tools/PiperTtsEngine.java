package nie.translator.vtranslator.tools;

import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import kotlin.jvm.functions.Function1;

/**
 * Wrapper around sherpa-onnx OfflineTts for Piper VITS neural TTS.
 * Produces natural-sounding speech from text, returning raw PCM samples
 * directly in memory (no file I/O).
 *
 * Supports streaming via generateWithCallback — audio chunks are delivered
 * as they're generated, so the listener hears the first words within ~200ms
 * while the rest is still being synthesized.
 *
 * Each instance loads one voice model (~30-60MB fp16). Create one per target
 * language and reuse across calls. Call release() when done.
 */
public class PiperTtsEngine {
    private static final String TAG = "PiperTtsEngine";
    private OfflineTts tts;
    private int sampleRate;

    /**
     * @param modelPath    path to the Piper .onnx model file (fp16 recommended)
     * @param tokensPath   path to the piper-tokens.txt file
     * @param espeakDataDir path to the espeak-ng-data/ directory
     */
    public PiperTtsEngine(String modelPath, String tokensPath, String espeakDataDir) {
        long startTime = System.currentTimeMillis();

        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig(
                modelPath,
                "",             /* lexicon — not used for Piper */
                tokensPath,
                espeakDataDir,
                "",             /* dictDir — not used */
                0.667f,         /* noiseScale — controls voice variability */
                0.8f,           /* noiseScaleW — controls phoneme duration variability */
                1.0f            /* lengthScale — 1.0 = normal speed */
        );

        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.setVits(vits);
        modelConfig.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        modelConfig.setDebug(false);

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(modelConfig);

        tts = new OfflineTts(null, config);
        sampleRate = tts.sampleRate();

        Log.i(TAG, "Piper TTS loaded in " + (System.currentTimeMillis() - startTime)
                + "ms, sampleRate=" + sampleRate);
    }

    /**
     * Synthesize text with streaming — delivers audio chunks as they're generated.
     * The first chunk arrives within ~200ms, so the listener hears audio while
     * the rest of the sentence is still being synthesized.
     *
     * Blocks the calling thread until synthesis is complete.
     */
    public void synthesizeStreaming(String text, StreamingCallback callback) {
        long startTime = System.currentTimeMillis();
        final long[] firstChunkTime = {0};

        tts.generateWithCallback(text, 0, 1.0f, new Function1<float[], Integer>() {
            @Override
            public Integer invoke(float[] samples) {
                if (firstChunkTime[0] == 0) {
                    firstChunkTime[0] = System.currentTimeMillis() - startTime;
                    Log.i("performance", "PIPELINE: first TTS chunk in " + firstChunkTime[0] + "ms");
                }
                byte[] pcmChunk = floatToPcm16(samples);
                callback.onChunk(pcmChunk, pcmChunk.length, sampleRate);
                return 1;
            }
        });

        Log.i("performance", "PIPELINE: Piper TTS total synthesis took "
                + (System.currentTimeMillis() - startTime) + "ms");
        callback.onComplete();
    }

    /**
     * Synthesize text to 16-bit PCM audio (non-streaming fallback).
     * Blocks until the entire sentence is synthesized.
     */
    public PcmResult synthesize(String text) {
        long startTime = System.currentTimeMillis();

        GeneratedAudio audio = tts.generate(text, 0, 1.0f);
        float[] samples = audio.getSamples();
        byte[] pcmBytes = floatToPcm16(samples);

        Log.i("performance", "PIPELINE: Piper TTS synthesis took "
                + (System.currentTimeMillis() - startTime) + "ms, "
                + pcmBytes.length + " bytes");

        return new PcmResult(pcmBytes, sampleRate);
    }

    private byte[] floatToPcm16(float[] samples) {
        return AudioUtils.floatToPcm16(samples, samples.length);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void release() {
        if (tts != null) {
            tts.release();
            tts = null;
            Log.i(TAG, "Piper TTS released");
        }
    }

    public interface StreamingCallback {
        /** Called for each audio chunk. pcmBytes may be a reusable buffer — consume before returning. */
        void onChunk(byte[] pcmBytes, int byteCount, int sampleRate);
        void onComplete();
    }

    public static class PcmResult {
        public final byte[] pcmBytes;
        public final int sampleRate;

        public PcmResult(byte[] pcmBytes, int sampleRate) {
            this.pcmBytes = pcmBytes;
            this.sampleRate = sampleRate;
        }
    }
}
