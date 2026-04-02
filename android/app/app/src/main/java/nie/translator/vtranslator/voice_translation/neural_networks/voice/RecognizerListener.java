package nie.translator.vtranslator.voice_translation.neural_networks.voice;

import nie.translator.vtranslator.voice_translation.neural_networks.NeuralNetworkApiListener;


public interface RecognizerListener extends NeuralNetworkApiListener {
    void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal);
}
