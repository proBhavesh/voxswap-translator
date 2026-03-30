package nie.translator.vtranslator.voice_translation.neural_networks;

public interface NeuralNetworkApiListener {
    void onError(int[] reasons, long value);
}
