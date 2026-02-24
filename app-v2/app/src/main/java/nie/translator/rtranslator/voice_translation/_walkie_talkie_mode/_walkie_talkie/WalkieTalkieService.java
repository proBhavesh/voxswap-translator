/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import java.util.ArrayList;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;
import nie.translator.rtranslator.tools.Message;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;


public class WalkieTalkieService extends VoiceTranslationService {
    public static final int SPEECH_BEAM_SIZE = 1;
    public static final int TRANSLATOR_BEAM_SIZE = 1;

    /* Commands */
    public static final int GET_FIRST_LANGUAGE = 24;
    public static final int GET_SECOND_LANGUAGE = 25;

    /* Callbacks */
    public static final int ON_FIRST_LANGUAGE = 22;
    public static final int ON_SECOND_LANGUAGE = 23;

    /* Objects */
    private Translator translator;
    private Recognizer speechRecognizer;
    private CustomLocale sourceLanguage;
    private CustomLocale targetLanguage1;
    private CustomLocale targetLanguage2;
    private RecognizerListener speechRecognizerCallback;
    private Translator.TranslateListener target1TranslateListener;
    private Translator.TranslateListener target2TranslateListener;


    @Override
    public void onCreate() {
        super.onCreate();
        translator = ((Global) getApplication()).getTranslator();
        speechRecognizer = ((Global) getApplication()).getSpeechRecognizer();

        clientHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull android.os.Message message) {
                int command = message.getData().getInt("command", -1);
                if (command != -1) {
                    if (!WalkieTalkieService.super.executeCommand(command, message.getData())) {
                        switch (command) {
                            case GET_FIRST_LANGUAGE: {
                                Bundle bundle = new Bundle();
                                bundle.putInt("callback", ON_FIRST_LANGUAGE);
                                bundle.putSerializable("language", targetLanguage1);
                                WalkieTalkieService.super.notifyToClient(bundle);
                                break;
                            }
                            case GET_SECOND_LANGUAGE: {
                                Bundle bundle = new Bundle();
                                bundle.putInt("callback", ON_SECOND_LANGUAGE);
                                bundle.putSerializable("language", targetLanguage2);
                                WalkieTalkieService.super.notifyToClient(bundle);
                                break;
                            }
                            case RECEIVE_TEXT: {
                                String text = message.getData().getString("text", null);
                                if (text != null) {
                                    translateToTarget1(text);
                                }
                                break;
                            }
                        }
                    }
                }
                return false;
            }
        });

        /* Voice recorder callback — always uses sourceLanguage for single-language recognition */
        mVoiceCallback = new Recorder.SimpleCallback() {
            @Override
            public void onVoiceStart() {
                super.onVoiceStart();
                WalkieTalkieService.super.notifyVoiceStart();
            }

            @Override
            public void onVoice(@NonNull float[] data, int size) {
                super.onVoice(data, size);
                if (sourceLanguage == null) {
                    return;
                }
                /* Continuous mode: recorder keeps running, STT processes async */
                speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, sourceLanguage.getCode());
            }

            @Override
            public void onVoiceEnd() {
                super.onVoiceEnd();
                WalkieTalkieService.super.notifyVoiceEnd();
            }

            @Override
            public void onVolumeLevel(float volumeLevel) {
                super.onVolumeLevel(volumeLevel);
                WalkieTalkieService.super.notifyVolumeLevel(volumeLevel);
            }
        };

        /* Single-language STT callback — after recognition, translate to both targets */
        speechRecognizerCallback = new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                if (isFinal) {
                    translateToTarget1(text);
                }
            }

            @Override
            public void onError(int[] reasons, long value) {
                /* Continuous mode: recorder is already running, just log the error */
            }
        };

        /* Translation listener for target language 1 */
        target1TranslateListener = new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String text, long resultID, boolean isFinal, CustomLocale languageOfText) {
                ((Global) getApplication()).getTTSLanguages(true, new Global.GetLocalesListListener() {
                    @Override
                    public void onSuccess(ArrayList<CustomLocale> ttsLanguages) {
                        if (isFinal && CustomLocale.containsLanguage(ttsLanguages, languageOfText)) {
                            speak(text, languageOfText);
                        }
                        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, text), resultID, true, isFinal);
                        WalkieTalkieService.super.notifyMessage(message);
                        WalkieTalkieService.super.addOrUpdateMessage(message);

                        /* After target1 is done, start translating to target2 */
                        if (isFinal && targetLanguage2 != null) {
                            translateToTarget2(textToTranslate);
                        }
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, text), resultID, true, isFinal);
                        WalkieTalkieService.super.notifyMessage(message);
                        WalkieTalkieService.super.addOrUpdateMessage(message);
                        if (isFinal && targetLanguage2 != null) {
                            translateToTarget2(textToTranslate);
                        }
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieService.super.notifyError(reasons, value);
            }
        };

        /* Translation listener for target language 2 */
        target2TranslateListener = new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String text, long resultID, boolean isFinal, CustomLocale languageOfText) {
                ((Global) getApplication()).getTTSLanguages(true, new Global.GetLocalesListListener() {
                    @Override
                    public void onSuccess(ArrayList<CustomLocale> ttsLanguages) {
                        if (isFinal && CustomLocale.containsLanguage(ttsLanguages, languageOfText)) {
                            speak(text, languageOfText);
                        }
                        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, text), resultID, false, isFinal);
                        WalkieTalkieService.super.notifyMessage(message);
                        WalkieTalkieService.super.addOrUpdateMessage(message);
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, text), resultID, false, isFinal);
                        WalkieTalkieService.super.notifyMessage(message);
                        WalkieTalkieService.super.addOrUpdateMessage(message);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieService.super.notifyError(reasons, value);
            }
        };

        initializeVoiceRecorder();
    }

    public void initializeVoiceRecorder() {
        if (Tools.hasPermissions(this, REQUIRED_PERMISSIONS)) {
            super.mVoiceRecorder = new Recorder((Global) getApplication(), false, mVoiceCallback, null);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean isFirstStart = (this.sourceLanguage == null);

        /* Read all 3 languages from intent */
        sourceLanguage = (CustomLocale) intent.getSerializableExtra("sourceLanguage");
        targetLanguage1 = (CustomLocale) intent.getSerializableExtra("firstLanguage");
        targetLanguage2 = (CustomLocale) intent.getSerializableExtra("secondLanguage");

        Log.d("VoxSwap", "onStartCommand: source=" + (sourceLanguage != null ? sourceLanguage.getCode() : "null")
                + " target1=" + (targetLanguage1 != null ? targetLanguage1.getCode() : "null")
                + " target2=" + (targetLanguage2 != null ? targetLanguage2.getCode() : "null")
                + " isFirstStart=" + isFirstStart);

        if (isFirstStart) {
            speechRecognizer.addCallback(speechRecognizerCallback);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected boolean shouldDeactivateMicDuringTTS() {
        /* Continuous mode: keep listening while TTS speaks */
        return false;
    }

    @Override
    public void onDestroy() {
        speechRecognizer.removeCallback(speechRecognizerCallback);
        super.onDestroy();
    }

    private void translateToTarget1(String text) {
        if (!isMetaText(text)) {
            translator.translate(text, sourceLanguage, targetLanguage1, TRANSLATOR_BEAM_SIZE, false, target1TranslateListener);
        }
    }

    private void translateToTarget2(String text) {
        if (!isMetaText(text)) {
            translator.translate(text, sourceLanguage, targetLanguage2, TRANSLATOR_BEAM_SIZE, false, target2TranslateListener);
        }
    }


    public static class WalkieTalkieServiceCommunicator extends VoiceTranslationServiceCommunicator {
        private ArrayList<LanguageListener> firstLanguageListeners = new ArrayList<>();
        private ArrayList<LanguageListener> secondLanguageListeners = new ArrayList<>();

        public WalkieTalkieServiceCommunicator(int id) {
            super(id);
            super.serviceHandler = new Handler(new Handler.Callback() {
                @Override
                public boolean handleMessage(@NonNull android.os.Message msg) {
                    msg.getData().setClassLoader(GuiMessage.class.getClassLoader());
                    int callbackMessage = msg.getData().getInt("callback", -1);
                    Bundle data = msg.getData();
                    if (!executeCallback(callbackMessage, data)) {
                        switch (callbackMessage) {
                            case ON_FIRST_LANGUAGE: {
                                CustomLocale language = (CustomLocale) data.getSerializable("language");
                                while (!firstLanguageListeners.isEmpty()) {
                                    firstLanguageListeners.remove(0).onLanguage(language);
                                }
                                break;
                            }
                            case ON_SECOND_LANGUAGE: {
                                CustomLocale language = (CustomLocale) data.getSerializable("language");
                                while (!secondLanguageListeners.isEmpty()) {
                                    secondLanguageListeners.remove(0).onLanguage(language);
                                }
                                break;
                            }
                        }
                    }
                    return true;
                }
            });
        }

        public void getFirstLanguage(LanguageListener responseListener) {
            firstLanguageListeners.add(responseListener);
            if (firstLanguageListeners.size() == 1) {
                Bundle bundle = new Bundle();
                bundle.putInt("command", GET_FIRST_LANGUAGE);
                super.sendToService(bundle);
            }
        }

        public void getSecondLanguage(LanguageListener responseListener) {
            secondLanguageListeners.add(responseListener);
            if (secondLanguageListeners.size() == 1) {
                Bundle bundle = new Bundle();
                bundle.putInt("command", GET_SECOND_LANGUAGE);
                super.sendToService(bundle);
            }
        }
    }

    public interface LanguageListener {
        void onLanguage(CustomLocale language);
    }
}
