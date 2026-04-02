package nie.translator.vtranslator.voice_translation.neural_networks;

import java.io.Serializable;
import nie.translator.vtranslator.tools.CustomLocale;

public class NeuralNetworkApiText implements Serializable {
    private String text;
    private CustomLocale language;

    public NeuralNetworkApiText(String text, CustomLocale language){
        this.text=text;
        this.language=language;
    }

    public NeuralNetworkApiText(String text){
        this.text=text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public CustomLocale getLanguage() {
        return language;
    }

    public void setLanguage(CustomLocale language) {
        this.language = language;
    }
}
