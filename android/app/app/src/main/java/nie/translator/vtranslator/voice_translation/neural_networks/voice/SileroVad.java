package nie.translator.vtranslator.voice_translation.neural_networks.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import nie.translator.vtranslator.tools.FileTools;
import nie.translator.vtranslator.voice_translation.neural_networks.NeuralNetworkApi;

/**
 * Silero VAD ONNX wrapper for 16kHz single-stream voice activity detection.
 * Loads asynchronously on a background thread following the same pattern as
 * Recognizer and Translator.
 *
 * ONNX inputs: "input" float[1][576], "state" float[2][1][128], "sr" long[1]
 * ONNX outputs: float[1][1] (probability), float[2][1][128] (updated state)
 *
 * Not thread-safe — designed to be called from a single audio processing thread.
 *
 * @see <a href="https://github.com/snakers4/silero-vad">Silero VAD</a>
 */
public class SileroVad {
    private static final String TAG = "SileroVad";
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SIZE = 512;
    private static final int CONTEXT_SIZE = 64;
    private static final String MODEL_FILENAME = "silero_vad_16k_op15.onnx";

    private OrtSession session;
    private OrtEnvironment env;
    private float[][][] state;
    private float[] context;
    private float[][] inputBuffer;
    private OnnxTensor srTensor;
    private volatile boolean initialized;

    /**
     * Load Silero VAD model asynchronously. Copies model from assets to internal
     * storage if needed, then creates ONNX session on a background thread.
     * Calls initListener when done.
     */
    public SileroVad(Context appContext, NeuralNetworkApi.InitListener initListener) {
        initialized = false;
        resetStates();

        new Thread("silero-vad-init") {
            @Override
            public void run() {
                File modelFile = new File(appContext.getFilesDir(), MODEL_FILENAME);
                if (!modelFile.exists()) {
                    FileTools.copyAssetToInternalMemory(appContext, MODEL_FILENAME);
                }

                try {
                    env = OrtEnvironment.getEnvironment();
                    OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                    opts.setInterOpNumThreads(1);
                    opts.setIntraOpNumThreads(1);
                    session = env.createSession(modelFile.getAbsolutePath(), opts);
                    opts.close();
                    inputBuffer = new float[1][CONTEXT_SIZE + FRAME_SIZE];
                    srTensor = OnnxTensor.createTensor(env, new long[]{SAMPLE_RATE});
                    initialized = true;
                    Log.i(TAG, "Silero VAD model loaded");
                    initListener.onInitializationFinished();
                } catch (OrtException e) {
                    Log.e(TAG, "Failed to load Silero VAD model: " + e.getMessage());
                    initListener.onError(new int[]{}, 0);
                }
            }
        }.start();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void resetStates() {
        state = new float[2][1][128];
        context = new float[CONTEXT_SIZE];
    }

    /**
     * Run VAD on a 512-sample audio frame.
     *
     * @param audioFrame exactly 512 float samples (16kHz, mono, normalized -1..1)
     * @return speech probability 0.0-1.0
     */
    public float predict(float[] audioFrame) {
        if (!initialized || audioFrame.length < FRAME_SIZE) {
            return 0f;
        }

        System.arraycopy(context, 0, inputBuffer[0], 0, CONTEXT_SIZE);
        System.arraycopy(audioFrame, 0, inputBuffer[0], CONTEXT_SIZE, FRAME_SIZE);

        OnnxTensor inputTensor = null;
        OnnxTensor stateTensor = null;
        OrtSession.Result result = null;

        try {
            inputTensor = OnnxTensor.createTensor(env, inputBuffer);
            stateTensor = OnnxTensor.createTensor(env, state);

            Map<String, OnnxTensor> inputs = new HashMap<>(3);
            inputs.put("input", inputTensor);
            inputs.put("state", stateTensor);
            inputs.put("sr", srTensor);

            result = session.run(inputs);

            float[][] output = (float[][]) result.get(0).getValue();
            float[][][] newState = (float[][][]) result.get(1).getValue();
            for (int i = 0; i < 2; i++) {
                System.arraycopy(newState[i][0], 0, state[i][0], 0, 128);
            }

            System.arraycopy(audioFrame, FRAME_SIZE - CONTEXT_SIZE, context, 0, CONTEXT_SIZE);

            return output[0][0];
        } catch (OrtException e) {
            Log.e(TAG, "Silero VAD inference failed: " + e.getMessage());
            return 0f;
        } finally {
            try {
                if (inputTensor != null) inputTensor.close();
                if (stateTensor != null) stateTensor.close();
                if (result != null) result.close();
            } catch (Exception ignored) {}
        }
    }

    public void close() {
        try {
            if (srTensor != null) srTensor.close();
            if (session != null) session.close();
        } catch (OrtException e) {
            Log.e(TAG, "Error closing Silero VAD: " + e.getMessage());
        }
        initialized = false;
    }

    public static int getFrameSize() {
        return FRAME_SIZE;
    }
}
