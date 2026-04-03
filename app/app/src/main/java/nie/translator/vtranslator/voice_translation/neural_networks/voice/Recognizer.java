/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.vtranslator.voice_translation.neural_networks.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;

import nie.translator.vtranslator.Global;
import nie.translator.vtranslator.R;
import nie.translator.vtranslator.tools.CustomLocale;
import nie.translator.vtranslator.tools.ErrorCodes;
import nie.translator.vtranslator.voice_translation.neural_networks.NeuralNetworkApi;


public class Recognizer extends NeuralNetworkApi {
    public static final String UNDEFINED_TEXT = "[(und)]";
    private ArrayList<RecognizerListener> callbacks = new ArrayList<>();
    private ArrayList<RecognizerMultiListener> multiCallbacks = new ArrayList<>();
    private boolean recognizing = false;
    private ArrayDeque<DataContainer> dataToRecognize = new ArrayDeque<>();
    private static final int MAX_QUEUE_SIZE = 3;
    private final Object lock = new Object();

    private static final String[] LANGUAGES = {
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
            "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
            "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
            "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
            "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
            "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
            "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
            "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
            "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
            "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue"
    };

    /* sherpa-onnx offline recognizer — replaces 6 ONNX sessions with optimized C++ engine */
    private OfflineRecognizer offlineRecognizer;
    private String currentLanguage;


    public Recognizer(Global global, final boolean returnResultOnlyAtTheEnd, final NeuralNetworkApi.InitListener initListener) {
        this.global = global;

        String filesDir = global.getFilesDir().getPath();
        String encoderPath = filesDir + "/small-encoder.int8.onnx";
        String decoderPath = filesDir + "/small-decoder.int8.onnx";
        String tokensPath = filesDir + "/small-tokens.txt";

        new Thread("recognizer-init") {
            @Override
            public void run() {
                try {
                    long startTime = System.currentTimeMillis();

                    offlineRecognizer = createRecognizer(encoderPath, decoderPath, tokensPath, "en");
                    currentLanguage = "en";

                    Log.i("performance", "sherpa-onnx Whisper Small loaded in " +
                            (System.currentTimeMillis() - startTime) + "ms");

                    initListener.onInitializationFinished();
                } catch (Exception e) {
                    e.printStackTrace();
                    initListener.onError(new int[]{ErrorCodes.ERROR_LOADING_MODEL}, 0);
                }
            }
        }.start();
    }

    /**
     * Recognizes the speech audio using sherpa-onnx Whisper Small.
     */
    public void recognize(final float[] data, int beamSize, final String languageCode) {
        new Thread("recognizer"){
            @Override
            public void run() {
                super.run();
                synchronized (lock) {
                    Log.e("recognizer","recognizingCalled");
                    if (data != null) {
                        dataToRecognize.addLast(new DataContainer(data, beamSize, languageCode));
                        while (dataToRecognize.size() > MAX_QUEUE_SIZE) {
                            dataToRecognize.pollFirst();
                            Log.w("Recognizer", "STT queue overflow, dropping oldest segment");
                        }
                        if (dataToRecognize.size() >= 1 && !recognizing) {
                            recognize();
                        }
                    }
                }
            }
        }.start();
    }

    public void recognize(final float[] data, int beamSize, final String languageCode1, final String languageCode2) {
        new Thread("recognizer"){
            @Override
            public void run() {
                super.run();
                synchronized (lock) {
                    Log.e("recognizer","recognizingCalled");
                    if (data != null) {
                        dataToRecognize.addLast(new DataContainer(data, beamSize, languageCode1, languageCode2));
                        while (dataToRecognize.size() > MAX_QUEUE_SIZE) {
                            dataToRecognize.pollFirst();
                            Log.w("Recognizer", "STT queue overflow, dropping oldest segment");
                        }
                        if (dataToRecognize.size() >= 1 && !recognizing) {
                            recognize();
                        }
                    }
                }
            }
        }.start();
    }

    private void recognize() {
        recognizing = true;
        DataContainer data = dataToRecognize.pollFirst();
        if (offlineRecognizer != null && data != null) {
            try {
                long startTimeInMs = SystemClock.elapsedRealtime();

                if (data.languageCode2 != null) {
                    /* Dual-language: run two sequential recognitions */
                    String text1 = transcribe(data.data, data.languageCode);
                    String text2 = transcribe(data.data, data.languageCode2);

                    android.util.Log.i("result", "result 1: " + text1);
                    android.util.Log.i("result", "result 2: " + text2);
                    android.util.Log.i("performance", "SPEECH RECOGNITION DONE IN: " +
                            (SystemClock.elapsedRealtime() - startTimeInMs) + "ms");

                    notifyMultiResult(text1, data.languageCode, 0, text2, data.languageCode2, 0);
                } else {
                    /* Single-language recognition */
                    String text = transcribe(data.data, data.languageCode);

                    android.util.Log.i("result", "result: " + text);
                    android.util.Log.i("performance", "SPEECH RECOGNITION DONE IN: " +
                            (SystemClock.elapsedRealtime() - startTimeInMs) + "ms");

                    notifyResult(text, data.languageCode, 0, true);
                }
            } catch (Exception e) {
                e.printStackTrace();
                notifyError(new int[]{ErrorCodes.ERROR_EXECUTING_MODEL}, 0);
            }
        }
        if (!dataToRecognize.isEmpty()){
            recognize();
        }else {
            recognizing = false;
        }
    }

    /**
     * Run sherpa-onnx Whisper inference on audio samples.
     * If the language changed since last call, recreates the recognizer.
     */
    private String transcribe(float[] samples, String languageCode) {
        /* Recreate recognizer if language changed */
        if (!languageCode.equals(currentLanguage)) {
            recreateRecognizer(languageCode);
        }

        long time = System.currentTimeMillis();

        OfflineStream stream = offlineRecognizer.createStream();
        stream.acceptWaveform(samples, Recorder.SAMPLE_RATE_CANDIDATES[0]);
        offlineRecognizer.decode(stream);

        OfflineRecognizerResult result = offlineRecognizer.getResult(stream);
        String text = result.getText();
        stream.release();

        android.util.Log.i("performance", "PIPELINE: STT done in " +
                (System.currentTimeMillis() - time) + "ms, starting translation");

        return correctText(text);
    }

    /**
     * Create a sherpa-onnx OfflineRecognizer for the given language.
     */
    private OfflineRecognizer createRecognizer(String encoderPath, String decoderPath, String tokensPath, String language) {
        OfflineWhisperModelConfig whisper = new OfflineWhisperModelConfig(
                encoderPath, decoderPath, language, "transcribe",
                1000, false, false
        );

        OfflineModelConfig modelConfig = new OfflineModelConfig();
        modelConfig.setWhisper(whisper);
        modelConfig.setTokens(tokensPath);
        modelConfig.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        modelConfig.setDebug(false);

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(modelConfig);
        config.setDecodingMethod("greedy_search");

        return new OfflineRecognizer(null, config);
    }

    /**
     * Recreate the recognizer with a different source language.
     */
    private void recreateRecognizer(String languageCode) {
        String filesDir = global.getFilesDir().getPath();
        if (offlineRecognizer != null) {
            offlineRecognizer.release();
        }
        offlineRecognizer = createRecognizer(
                filesDir + "/small-encoder.int8.onnx",
                filesDir + "/small-decoder.int8.onnx",
                filesDir + "/small-tokens.txt",
                languageCode
        );
        currentLanguage = languageCode;
        Log.i("Recognizer", "Recreated recognizer for language: " + languageCode);
    }

    private String correctText(String text){
        String correctedText = text;

        /* Remove Whisper timestamp tokens like <|0.00|> */
        String regex = "<\\|[^>]*\\|> ";
        correctedText = correctedText.replaceAll(regex, "");

        correctedText = correctedText.trim();

        /* Collapse repeated phrases — Whisper hallucinates "X. X. X." on noisy input */
        correctedText = deduplicateRepeats(correctedText);

        if(correctedText.length() >= 2) {
            char firstChar = correctedText.charAt(0);
            if (Character.isLowerCase(firstChar)) {
                StringBuilder sb = new StringBuilder(correctedText);
                sb.setCharAt(0, Character.toUpperCase(firstChar));
                correctedText = sb.toString();
            }
            correctedText = correctedText.replace("...", "");
        }
        return correctedText;
    }

    /**
     * Detect and collapse repeated phrases in STT output.
     * Checks phrase lengths from 1 to 10 words. If the same phrase
     * repeats 3+ times consecutively, collapses to a single occurrence.
     */
    private String deduplicateRepeats(String text) {
        if (text == null || text.isEmpty()) return text;

        String[] words = text.split("\\s+");
        if (words.length < 3) return text;

        /* Try phrase lengths from 1 word up to 10 words */
        for (int phraseLen = 1; phraseLen <= Math.min(10, words.length / 3); phraseLen++) {
            int i = 0;
            StringBuilder result = new StringBuilder();
            boolean foundRepeat = false;

            while (i < words.length) {
                /* Build candidate phrase of phraseLen words starting at i */
                if (i + phraseLen > words.length) {
                    for (int j = i; j < words.length; j++) {
                        if (result.length() > 0) result.append(" ");
                        result.append(words[j]);
                    }
                    break;
                }

                StringBuilder phrase = new StringBuilder();
                for (int j = i; j < i + phraseLen; j++) {
                    if (phrase.length() > 0) phrase.append(" ");
                    phrase.append(words[j]);
                }
                String phraseStr = phrase.toString();

                /* Count consecutive repetitions */
                int repeatCount = 1;
                int nextStart = i + phraseLen;
                while (nextStart + phraseLen <= words.length) {
                    StringBuilder nextPhrase = new StringBuilder();
                    for (int j = nextStart; j < nextStart + phraseLen; j++) {
                        if (nextPhrase.length() > 0) nextPhrase.append(" ");
                        nextPhrase.append(words[j]);
                    }
                    if (nextPhrase.toString().equalsIgnoreCase(phraseStr)) {
                        repeatCount++;
                        nextStart += phraseLen;
                    } else {
                        break;
                    }
                }

                if (repeatCount >= 3) {
                    /* Collapse to single occurrence */
                    if (result.length() > 0) result.append(" ");
                    result.append(phraseStr);
                    i = nextStart;
                    foundRepeat = true;
                } else {
                    if (result.length() > 0) result.append(" ");
                    result.append(words[i]);
                    i++;
                }
            }

            if (foundRepeat) {
                Log.i("Recognizer", "Dedup collapsed repeated phrase (len=" + phraseLen + "): " + text + " → " + result);
                return result.toString();
            }
        }

        return text;
    }

    /* Whisper supports all 99 languages — filter by quality threshold */
    public static ArrayList<CustomLocale> getSupportedLanguages(Context context) {
        ArrayList<CustomLocale> languages = new ArrayList<>();
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        boolean qualityLow = sharedPreferences.getBoolean("languagesNNQualityLow", false);
        if(!qualityLow) {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            try {
                DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
                Document document = documentBuilder.parse(context.getResources().openRawResource(R.raw.whisper_supported_languages));
                NodeList list = document.getElementsByTagName("code");
                for (int i = 0; i < list.getLength(); i++) {
                    languages.add(CustomLocale.getInstance(list.item(i).getTextContent()));
                }
            } catch (IOException | SAXException | ParserConfigurationException e) {
                e.printStackTrace();
            }
        }else{
            for (String language : LANGUAGES) {
                languages.add(CustomLocale.getInstance(language));
            }
        }
        return languages;
    }

    public void resetContext() {
        /* No-op for sherpa-onnx — context passing handled internally by the engine */
    }

    public void destroy() {
        if (offlineRecognizer != null) {
            offlineRecognizer.release();
            offlineRecognizer = null;
        }
    }

    public void addCallback(final RecognizerListener callback) {
        callbacks.add(callback);
    }

    public void removeCallback(RecognizerListener callback) {
        callbacks.remove(callback);
    }

    public void addMultiCallback(final RecognizerMultiListener callback) {
        multiCallbacks.add(callback);
    }

    public void removeMultiCallback(RecognizerMultiListener callback) {
        multiCallbacks.remove(callback);
    }

    private void notifyResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onSpeechRecognizedResult(text, languageCode, confidenceScore, isFinal);
        }
    }

    private void notifyMultiResult(String text1, String languageCode1, double confidenceScore1, String text2, String languageCode2, double confidenceScore2) {
        for (int i = 0; i < multiCallbacks.size(); i++) {
            multiCallbacks.get(i).onSpeechRecognizedResult(text1, languageCode1, confidenceScore1, text2, languageCode2, confidenceScore2);
        }
    }

    private void notifyError(int[] reasons, long value) {
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onError(reasons, value);
        }
        for (int i = 0; i < multiCallbacks.size(); i++) {
            multiCallbacks.get(i).onError(reasons, value);
        }
    }


    private static class DataContainer{
        private float[] data;
        private String languageCode;
        private String languageCode2;
        private int beamSize;

        private DataContainer(float[] data, int beamSize, String languageCode){
            this.data = data;
            this.beamSize = beamSize;
            this.languageCode = languageCode;
        }

        private DataContainer(float[] data, int beamSize, String languageCode, String languageCode2){
            this.data = data;
            this.beamSize = beamSize;
            this.languageCode = languageCode;
            this.languageCode2 = languageCode2;
        }
    }
}
