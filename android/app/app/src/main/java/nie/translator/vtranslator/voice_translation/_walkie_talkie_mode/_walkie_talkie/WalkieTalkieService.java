package nie.translator.vtranslator.voice_translation._walkie_talkie_mode._walkie_talkie;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Objects;

import nie.translator.vtranslator.Global;
import nie.translator.vtranslator.tools.AudioUtils;
import nie.translator.vtranslator.tools.CustomLocale;
import nie.translator.vtranslator.tools.Tools;
import nie.translator.vtranslator.tools.gui.messages.GuiMessage;
import nie.translator.vtranslator.voice_translation.VoiceTranslationService;
import nie.translator.vtranslator.tools.Message;
import nie.translator.vtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.vtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.vtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.vtranslator.voice_translation.neural_networks.voice.Recorder;
import nie.translator.vtranslator.voice_translation.networking.AudioStreamer;
import nie.translator.vtranslator.voice_translation.networking.BoxConnection;
import nie.translator.vtranslator.voice_translation.networking.WifiNetworkBinder;


public class WalkieTalkieService extends VoiceTranslationService {
    public static final int SPEECH_BEAM_SIZE = 1;
    public static final int TRANSLATOR_BEAM_SIZE = 1;

    /* Box networking — use Pi's WiFi IP for dev testing, 10.0.0.1 for production hotspot */
    private static final String BOX_HOST = "192.168.31.133";
    private static final int BOX_TCP_PORT = 7700;

    /* Commands */
    public static final int GET_FIRST_LANGUAGE = 24;
    public static final int GET_SECOND_LANGUAGE = 25;
    public static final int RETRY_CONNECTION = 26;

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

    /* Pipeline profiling */
    private volatile long pipelineStartTime;

    /* Short fragment merging — holds STT results under MIN_WORDS_TO_TRANSLATE
     * and prepends them to the next chunk to avoid translating broken fragments */
    private static final int MIN_WORDS_TO_TRANSLATE = 5;
    private volatile String heldFragment = null;

    /* Low-latency passthrough state — computed when languages change */
    private volatile boolean passthroughTarget1 = false;
    private volatile boolean passthroughTarget2 = false;
    private volatile boolean isFullPassthrough = false;
    private volatile AudioTrack passthroughAudioTrack;
    private final Object passthroughLock = new Object(); /* guards init/release only, not hot-path writes */

    /* Networking — box connection + audio streaming */
    private BoxConnection boxConnection;
    private AudioStreamer audioStreamer;
    private WifiManager.WifiLock wifiLock;
    private WifiNetworkBinder wifiNetworkBinder;


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
                            case RETRY_CONNECTION: {
                                retryBoxConnection();
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
            public void onRawAudioChunk(@NonNull short[] shortData, int offset, int size) {
                if (!passthroughTarget1 && !passthroughTarget2) return;
                if (sourceLanguage == null) return;

                byte[] pcmBytes = AudioUtils.shortToPcmBytes(shortData, offset, size);

                boolean alreadyPlayed = false;
                if (passthroughTarget1) {
                    PcmAudioData pcm = new PcmAudioData(pcmBytes, 16000, 1);
                    pcm.language = targetLanguage1;
                    pcm.streamType = 1;
                    onPcmGenerated(pcm);
                    enqueuePassthroughPlayback(pcm);
                    alreadyPlayed = true;
                }
                if (passthroughTarget2) {
                    PcmAudioData pcm = new PcmAudioData(pcmBytes, 16000, 1);
                    pcm.language = targetLanguage2;
                    pcm.streamType = 2;
                    onPcmGenerated(pcm);
                    if (!alreadyPlayed) {
                        enqueuePassthroughPlayback(pcm);
                    }
                }
            }

            @Override
            public void onVoice(@NonNull float[] data, int size) {
                super.onVoice(data, size);
                if (sourceLanguage == null) return;

                /* If every target is passthrough, skip STT entirely */
                if (isFullPassthrough) return;

                pipelineStartTime = System.currentTimeMillis();
                float durationSec = (float) size / 16000f;
                Log.i("performance", "PIPELINE: voice chunk " + String.format("%.1f", durationSec) + "s of audio");

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

        /* Single-language STT callback — after recognition, translate to both targets.
         * Short fragments (< MIN_WORDS_TO_TRANSLATE words) are held and prepended to
         * the next chunk to avoid translating broken sentence fragments. */
        speechRecognizerCallback = new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                if (isFinal) {
                    long sttTime = System.currentTimeMillis() - pipelineStartTime;
                    Log.i("performance", "PIPELINE: STT done in " + sttTime + "ms, starting translation");

                    /* Prepend any held fragment from previous short chunk */
                    String fullText = text;
                    if (heldFragment != null) {
                        fullText = heldFragment + " " + text;
                        Log.i("performance", "PIPELINE: merged held fragment: \"" + heldFragment + "\" + \"" + text + "\"");
                        heldFragment = null;
                    }

                    /* If result is too short, hold it for next chunk */
                    int wordCount = countWords(fullText);
                    if (wordCount < MIN_WORDS_TO_TRANSLATE && !isMetaText(fullText)) {
                        heldFragment = fullText.trim();
                        Log.i("performance", "PIPELINE: holding short fragment (" + wordCount + " words): \"" + heldFragment + "\"");
                        return;
                    }

                    translateToTarget1(fullText);
                    if (targetLanguage2 != null) {
                        translateToTarget2(fullText);
                    }
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
                if (isFinal) {
                    long t1Time = System.currentTimeMillis() - pipelineStartTime;
                    Log.i("performance", "PIPELINE: target1 translated in " + t1Time + "ms (since voice end)");
                    Log.i("translation", "IN:  " + textToTranslate);
                    Log.i("translation", "OUT: " + text);
                    speak(text, languageOfText);
                }
                GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, text), resultID, true, isFinal);
                WalkieTalkieService.super.notifyMessage(message);
                WalkieTalkieService.super.addOrUpdateMessage(message);
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
                if (isFinal) {
                    long t2Time = System.currentTimeMillis() - pipelineStartTime;
                    Log.i("performance", "PIPELINE: target2 translated in " + t2Time + "ms (since voice end)");
                    speak(text, languageOfText);
                }
                GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, text), resultID, false, isFinal);
                WalkieTalkieService.super.notifyMessage(message);
                WalkieTalkieService.super.addOrUpdateMessage(message);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieService.super.notifyError(reasons, value);
            }
        };

        /* Initialize networking — box connection + audio streamer */
        audioStreamer = new AudioStreamer();
        boxConnection = new BoxConnection(new BoxConnection.ConnectionListener() {
            @Override
            public void onConnected(int speakerId) {
                audioStreamer.start(BOX_HOST, speakerId);
                notifyConnectionStatus(true);
                Log.d("VoxSwap", "Connected to box, speaker_id=" + speakerId);
            }

            @Override
            public void onDisconnected() {
                audioStreamer.stop();
                notifyConnectionStatus(false);
                Log.d("VoxSwap", "Disconnected from box");
            }

            @Override
            public void onConnectionError(String reason) {
                Log.w("VoxSwap", "Box connection error: " + reason);
            }
        });

        /* Keep WiFi radio active when screen is off — prevents silent UDP stream drops */
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoxSwap:wifiLock");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();

        /* Request WiFi network — tells Android to keep the no-internet WiFi connection
         * alive and gives us a Network handle to bind sockets to the WiFi interface.
         * Without this, Android detects no internet on the box's hotspot and routes
         * all traffic through mobile data, making 10.0.0.1 unreachable. */
        wifiNetworkBinder = new WifiNetworkBinder(this, new WifiNetworkBinder.Listener() {
            @Override
            public void onWifiNetworkAvailable(Network network) {
                boxConnection.setWifiNetwork(network);
                audioStreamer.setWifiNetwork(network);
                Log.d("VoxSwap", "WiFi network acquired, sockets will bind to WiFi");
            }

            @Override
            public void onWifiNetworkLost() {
                boxConnection.setWifiNetwork(null);
                audioStreamer.setWifiNetwork(null);
                Log.d("VoxSwap", "WiFi network lost");
            }
        });
        wifiNetworkBinder.request();

        initializeVoiceRecorder();

        /* Pre-warm TTS language cache so first translation doesn't wait for it */
        ((Global) getApplication()).getTTSLanguages(true, new Global.GetLocalesListListener() {
            @Override
            public void onSuccess(ArrayList<CustomLocale> ttsLanguages) {
                Log.d("VoxSwap", "TTS language cache warmed: " + ttsLanguages.size() + " languages");
            }
            @Override
            public void onFailure(int[] reasons, long value) {
                Log.w("VoxSwap", "TTS language cache warm failed");
            }
        });
    }

    public void initializeVoiceRecorder() {
        if (Tools.hasPermissions(this, REQUIRED_PERMISSIONS)) {
            super.mVoiceRecorder = new Recorder((Global) getApplication(), false, mVoiceCallback, null);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        /* intent can be null if system restarts the service after process death */
        if (intent == null) return super.onStartCommand(null, flags, startId);

        boolean isFirstStart = (this.sourceLanguage == null);

        /* Read all 3 languages from intent */
        CustomLocale prevTarget1 = targetLanguage1;
        CustomLocale prevTarget2 = targetLanguage2;
        sourceLanguage = (CustomLocale) intent.getSerializableExtra("sourceLanguage");
        targetLanguage1 = (CustomLocale) intent.getSerializableExtra("firstLanguage");
        targetLanguage2 = (CustomLocale) intent.getSerializableExtra("secondLanguage");

        Log.d("VoxSwap", "onStartCommand: source=" + (sourceLanguage != null ? sourceLanguage.getCode() : "null")
                + " target1=" + (targetLanguage1 != null ? targetLanguage1.getCode() : "null")
                + " target2=" + (targetLanguage2 != null ? targetLanguage2.getCode() : "null")
                + " isFirstStart=" + isFirstStart);

        /* Only invalidate cache and reload engines when target languages actually changed */
        boolean languagesChanged = isFirstStart
                || !Objects.equals(prevTarget1, targetLanguage1)
                || !Objects.equals(prevTarget2, targetLanguage2);
        if (languagesChanged) {
            invalidateVoiceCache();
            preloadPiperEngines(targetLanguage1, targetLanguage2);
        }

        /* Recompute passthrough flags whenever languages change */
        passthroughTarget1 = targetLanguage1 != null && sourceLanguage != null
                && sourceLanguage.equalsLanguage(targetLanguage1);
        passthroughTarget2 = targetLanguage2 != null && sourceLanguage != null
                && sourceLanguage.equalsLanguage(targetLanguage2);
        boolean target1NeedsTranslation = targetLanguage1 != null && !passthroughTarget1;
        boolean target2NeedsTranslation = targetLanguage2 != null && !passthroughTarget2;
        isFullPassthrough = !target1NeedsTranslation && !target2NeedsTranslation;

        Log.d("VoxSwap", "Passthrough: t1=" + passthroughTarget1 + " t2=" + passthroughTarget2
                + " fullPassthrough=" + isFullPassthrough);

        if (passthroughTarget1 || passthroughTarget2) {
            initPassthroughAudioTrack();
        } else {
            releasePassthroughAudioTrack();
        }

        if (mVoiceRecorder != null) {
            mVoiceRecorder.setSkipVad(isFullPassthrough);
        }

        if (isFirstStart) {
            speechRecognizer.addCallback(speechRecognizerCallback);

            /* Connect to box — runs on background thread, doesn't block */
            if (sourceLanguage != null) {
                connectToBox();
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected boolean isBoxConnected() {
        return boxConnection != null && boxConnection.isConnected();
    }

    @Override
    protected boolean shouldDeactivateMicDuringTTS() {
        /* Continuous mode: keep listening while TTS speaks */
        return false;
    }

    @Override
    protected void onPcmGenerated(PcmAudioData pcmData) {
        if (audioStreamer == null || !audioStreamer.isActive()) return;

        /* Use explicit stream type if set (passthrough sets it directly to avoid
         * ambiguity when both targets are the same language). Otherwise infer from language. */
        int streamType;
        if (pcmData.streamType > 0) {
            streamType = pcmData.streamType;
        } else if (pcmData.language != null && pcmData.language.equalsLanguage(targetLanguage1)) {
            streamType = 1;
        } else if (pcmData.language != null && pcmData.language.equalsLanguage(targetLanguage2)) {
            streamType = 2;
        } else {
            Log.w("VoxSwap", "PCM generated for unknown language, skipping stream");
            return;
        }

        long e2eTime = System.currentTimeMillis() - pipelineStartTime;
        Log.i("performance", "PIPELINE: streaming " + pcmData.pcmBytes.length + " bytes as stream_type=" + streamType + ", e2e=" + e2eTime + "ms");
        audioStreamer.streamTtsAudio(pcmData.pcmBytes, pcmData.sampleRate, streamType);
    }

    @Override
    public void onDestroy() {
        releasePassthroughAudioTrack();
        if (boxConnection != null) boxConnection.disconnect();
        if (audioStreamer != null) audioStreamer.stop();
        if (wifiNetworkBinder != null) wifiNetworkBinder.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        speechRecognizer.removeCallback(speechRecognizerCallback);
        super.onDestroy();
    }

    private void notifyConnectionStatus(boolean connected) {
        Bundle bundle = new Bundle();
        bundle.putInt("callback", connected ? ON_BOX_CONNECTED : ON_BOX_DISCONNECTED);
        WalkieTalkieService.super.notifyToClient(bundle);
    }

    private void connectToBox() {
        String speakerName = Build.MODEL;
        boxConnection.connect(BOX_HOST, BOX_TCP_PORT, speakerName, sourceLanguage.getCode(),
                targetLanguage1 != null ? targetLanguage1.getCode() : "",
                targetLanguage2 != null ? targetLanguage2.getCode() : "");
    }

    private void retryBoxConnection() {
        if (boxConnection != null && !boxConnection.isConnected() && sourceLanguage != null) {
            Log.d("VoxSwap", "Manual retry connection to box");
            boxConnection.disconnect();
            connectToBox();
        }
    }

    private void initPassthroughAudioTrack() {
        synchronized (passthroughLock) {
            if (passthroughAudioTrack != null) return;

            try {
                int minBufSize = AudioTrack.getMinBufferSize(
                        16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(16000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(minBufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();

                /* Output device is set via setPreferredOutputDevice() override */
                track.play();
                passthroughAudioTrack = track;
                Log.d("VoxSwap", "Passthrough AudioTrack created (LOW_LATENCY, buf=" + minBufSize + ")");
            } catch (Exception e) {
                Log.e("VoxSwap", "Failed to create passthrough AudioTrack", e);
            }
        }
    }

    private void releasePassthroughAudioTrack() {
        synchronized (passthroughLock) {
            if (passthroughAudioTrack != null) {
                passthroughAudioTrack.stop();
                passthroughAudioTrack.release();
                passthroughAudioTrack = null;
            }
        }
    }

    private void enqueuePassthroughPlayback(PcmAudioData pcmData) {
        if (isBoxConnected()) return;

        /* Lock-free hot path — volatile read + local capture avoids blocking the audio thread */
        AudioTrack track = passthroughAudioTrack;
        if (track != null) {
            track.write(pcmData.pcmBytes, 0, pcmData.pcmBytes.length, AudioTrack.WRITE_NON_BLOCKING);
        }
    }

    @Override
    public void setPreferredOutputDevice(@Nullable AudioDeviceInfo device) {
        super.setPreferredOutputDevice(device);
        synchronized (passthroughLock) {
            if (passthroughAudioTrack != null) {
                passthroughAudioTrack.setPreferredDevice(device);
            }
        }
    }

    private int countWords(String text) {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                count++;
                inWord = true;
            }
        }
        return count;
    }

    private void translateToTarget1(String text) {
        /* Skip translation if source == target — audio passthrough handles this channel */
        if (sourceLanguage.equalsLanguage(targetLanguage1)) return;
        if (!isMetaText(text)) {
            translator.translate(text, sourceLanguage, targetLanguage1, TRANSLATOR_BEAM_SIZE, false, target1TranslateListener);
        }
    }

    private void translateToTarget2(String text) {
        if (sourceLanguage.equalsLanguage(targetLanguage2)) return;
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

        public void retryConnection() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", RETRY_CONNECTION);
            super.sendToService(bundle);
        }
    }

    public interface LanguageListener {
        void onLanguage(CustomLocale language);
    }
}
