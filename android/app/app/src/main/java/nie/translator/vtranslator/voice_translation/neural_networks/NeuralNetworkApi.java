package nie.translator.vtranslator.voice_translation.neural_networks;

import androidx.annotation.NonNull;

import java.util.ArrayList;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.extensions.OrtxPackage;
import nie.translator.vtranslator.DebugConfig;
import nie.translator.vtranslator.Global;
import nie.translator.vtranslator.tools.ErrorCodes;

public class NeuralNetworkApi {
    protected Global global;
    private ArrayList<Thread> pendingThreads= new ArrayList<>();
    public static boolean isVerifying = false;

    protected void addPendingThread(Thread thread){
        pendingThreads.add(thread);
    }

    protected Thread takePendingThread(){
        if(pendingThreads.size()>0) {
            return pendingThreads.remove(0);
        }else{
            return null;
        }
    }

    public static void testModelIntegrity(@NonNull String testModelPath, InitListener initListener){
        /* Only test NLLB and Whisper models via ONNX Runtime Java API.
         * Piper TTS .onnx files are loaded by sherpa-onnx native engine — skip them. */
        boolean isOrtModel = testModelPath.contains("NLLB_") || testModelPath.contains("base-");
        if (!testModelPath.endsWith(".onnx") || !isOrtModel) {
            initListener.onInitializationFinished();
            return;
        }
        //we try to load the model in testModelPath, if we don't have an exception the model file is perfect, else we have an integrity problem
        try {
            isVerifying = true;
            OrtEnvironment onnxEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions testOptions = new OrtSession.SessionOptions();
            testOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
            testOptions.setMemoryPatternOptimization(false);
            testOptions.setCPUArenaAllocator(false);
            if(!testModelPath.contains("detokenizer.onnx")) {   //for Whisper_detokenizer.onnx we test with OnnxRuntime optimization because we it that way in the Recognizer
                testOptions.setOptimizationLevel(DebugConfig.ONNX_OPT_LEVEL);
            }
            OrtSession testSession = onnxEnv.createSession(testModelPath, testOptions);
            testSession.close();
            isVerifying = false;
            initListener.onInitializationFinished();
        } catch (OrtException e) {
            e.printStackTrace();
            isVerifying = false;
            initListener.onError(new int[]{ErrorCodes.ERROR_LOADING_MODEL},0);
        }
    }

    public interface InitListener{
        void onInitializationFinished();
        void onError(int[] reasons, long value);
    }
}
