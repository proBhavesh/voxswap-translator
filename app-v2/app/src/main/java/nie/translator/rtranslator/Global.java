package nie.translator.rtranslator;

import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.util.ArrayList;

import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.TTS;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;


public class Global extends Application implements DefaultLifecycleObserver {
    public static final int DEFAULT_BEAM_SIZE = 1;
    private ArrayList<CustomLocale> languages = new ArrayList<>();
    private ArrayList<CustomLocale> translatorLanguages = new ArrayList<>();
    private ArrayList<CustomLocale> ttsLanguages = new ArrayList<>();
    private CustomLocale language;
    private CustomLocale sourceLanguage;
    private CustomLocale firstLanguage;
    private CustomLocale secondLanguage;
    private CustomLocale firstTextLanguage;
    private CustomLocale secondTextLanguage;
    private Translator translator;
    private Recognizer speechRecognizer;
    private String name = "";
    private int micSensitivity = -1;
    private int speechTimeout = -1;
    private int prevVoiceDuration = -1;
    private int beamSize = -1;
    private int amplitudeThreshold = Recorder.DEFAULT_AMPLITUDE_THRESHOLD;
    private boolean isForeground = false;
    @Nullable
    private AccessActivity accessActivity;
    private Handler mainHandler;
    private static Handler mHandler = new Handler();
    private final Object lock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        getMicSensitivity();
        createNotificationChannel();
    }

    public void initializeTranslator(NeuralNetworkApi.InitListener initListener){
        if(translator == null) {
            translator = new Translator(this, Translator.NLLB_CACHE, initListener);
        }else{
            initListener.onInitializationFinished();
        }
    }

    public void initializeSpeechRecognizer(NeuralNetworkApi.InitListener initListener){
        if(speechRecognizer == null) {
            speechRecognizer = new Recognizer(this, true, initListener);
        }else{
            initListener.onInitializationFinished();
        }
    }

    public void getLanguages(final boolean recycleResult, boolean ignoreTTSError, final GetLocalesListListener responseListener) {
        if (recycleResult && !languages.isEmpty()) {
            responseListener.onSuccess(languages);
        } else {
            TTS.getSupportedLanguages(this, new TTS.SupportedLanguagesListener() {
                @Override
                public void onLanguagesListAvailable(ArrayList<CustomLocale> ttsLanguages) {
                    getTranslatorLanguages(recycleResult, new GetLocalesListListener() {
                        @Override
                        public void onSuccess(ArrayList<CustomLocale> translatorLanguages) {
                            ArrayList<CustomLocale> speechRecognizerLanguages = Recognizer.getSupportedLanguages(Global.this);
                            final ArrayList<CustomLocale> compatibleLanguages = new ArrayList<>();
                            for (CustomLocale translatorLanguage : translatorLanguages) {
                                if (CustomLocale.containsLanguage(speechRecognizerLanguages, translatorLanguage)) {
                                    compatibleLanguages.add(translatorLanguage);
                                }
                            }
                            languages = compatibleLanguages;
                            responseListener.onSuccess(compatibleLanguages);
                        }

                        @Override
                        public void onFailure(int[] reasons, long value) {
                            responseListener.onFailure(reasons, 0);
                        }
                    });
                }

                @Override
                public void onError(int reason) {
                    if(ignoreTTSError) {
                        getTranslatorLanguages(recycleResult, new GetLocalesListListener() {
                            @Override
                            public void onSuccess(ArrayList<CustomLocale> translatorLanguages) {
                                ArrayList<CustomLocale> speechRecognizerLanguages = Recognizer.getSupportedLanguages(Global.this);
                                final ArrayList<CustomLocale> compatibleLanguages = new ArrayList<>();
                                for (CustomLocale translatorLanguage : translatorLanguages) {
                                    if (CustomLocale.containsLanguage(speechRecognizerLanguages, translatorLanguage)) {
                                        compatibleLanguages.add(translatorLanguage);
                                    }
                                }
                                languages = compatibleLanguages;
                                responseListener.onSuccess(compatibleLanguages);
                            }

                            @Override
                            public void onFailure(int[] reasons, long value) {
                                responseListener.onFailure(reasons, 0);
                            }
                        });
                    }else{
                        responseListener.onFailure(new int[]{reason}, 0);
                    }
                }
            });
        }
    }

    public void getTranslatorLanguages(final boolean recycleResult, final GetLocalesListListener responseListener) {
        if (recycleResult && !translatorLanguages.isEmpty()) {
            responseListener.onSuccess(translatorLanguages);
        } else {
            ArrayList<CustomLocale> languages = Translator.getSupportedLanguages(Global.this, Translator.NLLB);
            translatorLanguages = languages;
            responseListener.onSuccess(languages);
        }
    }

    public void getTTSLanguages(final boolean recycleResult, final GetLocalesListListener responseListener){
        if(recycleResult && !ttsLanguages.isEmpty()){
            responseListener.onSuccess(ttsLanguages);
        }else{
            TTS.getSupportedLanguages(this, new TTS.SupportedLanguagesListener() {
                @Override
                public void onLanguagesListAvailable(ArrayList<CustomLocale> ttsLanguages) {
                    Global.this.ttsLanguages = ttsLanguages;
                    responseListener.onSuccess(ttsLanguages);
                }

                @Override
                public void onError(int reason) {
                    responseListener.onSuccess(new ArrayList<>());
                }
            });
        }
    }

    public Translator getTranslator() {
        return translator;
    }

    public void deleteTranslator(){
        translator = null;
    }

    public Recognizer getSpeechRecognizer() {
        return speechRecognizer;
    }

    public void deleteSpeechRecognizer(){
        speechRecognizer = null;
    }

    public boolean isForeground() {
        return isForeground;
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onStop(owner);
        isForeground = false;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onStart(owner);
        isForeground = true;
    }

    @Nullable
    public AccessActivity getRunningAccessActivity() {
        return accessActivity;
    }

    public void setAccessActivity(@Nullable AccessActivity accessActivity) {
        this.accessActivity = accessActivity;
    }

    public interface GetLocalesListListener {
        void onSuccess(ArrayList<CustomLocale> result);
        void onFailure(int[] reasons, long value);
    }

    public void getLanguage(final boolean recycleResult, final GetLocaleListener responseListener) {
        getLanguages(true, true, new GetLocalesListListener() {
            @Override
            public void onSuccess(ArrayList<CustomLocale> languages) {
                CustomLocale predefinedLanguage = CustomLocale.getDefault();
                CustomLocale language = null;
                if (recycleResult && Global.this.language != null) {
                    language = Global.this.language;
                } else {
                    SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                    String code = sharedPreferences.getString("language", predefinedLanguage.getCode());
                    if (code != null) {
                        language = CustomLocale.getInstance(code);
                    }
                }

                int index = CustomLocale.search(languages, language);
                if (index != -1) {
                    language = languages.get(index);
                } else {
                    int index2 = CustomLocale.search(languages, predefinedLanguage);
                    if (index2 != -1) {
                        language = predefinedLanguage;
                    } else {
                        language = new CustomLocale("en");
                    }
                }

                Global.this.language = language;
                responseListener.onSuccess(language);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public void getFirstLanguage(final boolean recycleResult, final GetLocaleListener responseListener) {
        getLanguages(true, true, new GetLocalesListListener() {
            @Override
            public void onSuccess(final ArrayList<CustomLocale> languages) {
                getLanguage(true, new GetLocaleListener() {
                    @Override
                    public void onSuccess(CustomLocale predefinedLanguage) {
                        CustomLocale language = null;
                        if (recycleResult && Global.this.firstLanguage != null) {
                            language = Global.this.firstLanguage;
                        } else {
                            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                            String code = sharedPreferences.getString("firstLanguage", predefinedLanguage.getCode());
                            if (code != null) {
                                language = CustomLocale.getInstance(code);
                            }
                        }

                        int index = CustomLocale.search(languages, language);
                        if (index != -1) {
                            language = languages.get(index);
                        } else {
                            int index2 = CustomLocale.search(languages, predefinedLanguage);
                            if (index2 != -1) {
                                language = predefinedLanguage;
                            } else {
                                language = new CustomLocale("en");
                            }
                        }

                        Global.this.firstLanguage = language;
                        responseListener.onSuccess(language);
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        responseListener.onFailure(reasons, value);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public void getSecondLanguage(final boolean recycleResult, final GetLocaleListener responseListener) {
        getLanguages(true, true, new GetLocalesListListener() {
            @Override
            public void onSuccess(ArrayList<CustomLocale> languages) {
                CustomLocale predefinedLanguage = CustomLocale.getDefault();
                CustomLocale language = null;
                if (recycleResult && Global.this.secondLanguage != null) {
                    language = Global.this.secondLanguage;
                } else {
                    SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                    String code = sharedPreferences.getString("secondLanguage", null);
                    if (code != null) {
                        language = CustomLocale.getInstance(code);
                    }
                }

                if (language == null) {
                    Global.this.secondLanguage = null;
                    responseListener.onFailure(new int[]{}, 0);
                    return;
                }

                int index = CustomLocale.search(languages, language);
                if (index != -1) {
                    language = languages.get(index);
                } else {
                    language = new CustomLocale("en");
                }

                Global.this.secondLanguage = language;
                responseListener.onSuccess(language);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public void getFirstAndSecondLanguages(final boolean recycleResult, final GetTwoLocaleListener responseListener){
        getFirstLanguage(recycleResult, new GetLocaleListener() {
            @Override
            public void onSuccess(CustomLocale result1) {
                getSecondLanguage(recycleResult, new GetLocaleListener() {
                    @Override
                    public void onSuccess(CustomLocale result2) {
                        responseListener.onSuccess(result1, result2);
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        responseListener.onFailure(reasons, value);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public void getFirstTextLanguage(final boolean recycleResult, final GetLocaleListener responseListener) {
        getTranslatorLanguages(true, new GetLocalesListListener() {
            @Override
            public void onSuccess(final ArrayList<CustomLocale> languages) {
                getLanguage(true, new GetLocaleListener() {
                    @Override
                    public void onSuccess(CustomLocale predefinedLanguage) {
                        CustomLocale language = null;
                        if (recycleResult && Global.this.firstTextLanguage != null) {
                            language = Global.this.firstTextLanguage;
                        } else {
                            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                            String code = sharedPreferences.getString("firstTextLanguage", predefinedLanguage.getCode());
                            if (code != null) {
                                language = CustomLocale.getInstance(code);
                            }
                        }

                        int index = CustomLocale.search(languages, language);
                        if (index != -1) {
                            language = languages.get(index);
                        } else {
                            int index2 = CustomLocale.search(languages, predefinedLanguage);
                            if (index2 != -1) {
                                language = predefinedLanguage;
                            } else {
                                language = new CustomLocale("en");
                            }
                        }

                        Global.this.firstTextLanguage = language;
                        responseListener.onSuccess(language);
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        responseListener.onFailure(reasons, value);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public void getSecondTextLanguage(final boolean recycleResult, final GetLocaleListener responseListener) {
        getTranslatorLanguages(true, new GetLocalesListListener() {
            @Override
            public void onSuccess(ArrayList<CustomLocale> languages) {
                CustomLocale predefinedLanguage = CustomLocale.getDefault();
                CustomLocale language = null;
                if (recycleResult && Global.this.secondTextLanguage != null) {
                    language = Global.this.secondTextLanguage;
                } else {
                    SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                    String code = sharedPreferences.getString("secondTextLanguage", null);
                    if (code != null) {
                        language = CustomLocale.getInstance(code);
                    }
                }

                int index = CustomLocale.search(languages, language);
                if (index != -1) {
                    language = languages.get(index);
                } else {
                    language = new CustomLocale("en");
                }

                Global.this.secondTextLanguage = language;
                responseListener.onSuccess(language);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public void getFirstAndSecondTextLanguages(final boolean recycleResult, final GetTwoLocaleListener responseListener){
        getFirstTextLanguage(recycleResult, new GetLocaleListener() {
            @Override
            public void onSuccess(CustomLocale result1) {
                getSecondTextLanguage(recycleResult, new GetLocaleListener() {
                    @Override
                    public void onSuccess(CustomLocale result2) {
                        responseListener.onSuccess(result1, result2);
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        responseListener.onFailure(reasons, value);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public interface GetLocaleListener {
        void onSuccess(CustomLocale result);
        void onFailure(int[] reasons, long value);
    }

    public interface GetTwoLocaleListener {
        void onSuccess(CustomLocale language1, CustomLocale language2);
        void onFailure(int[] reasons, long value);
    }

    public void setLanguage(CustomLocale language) {
        this.language = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("language", language.getCode());
        editor.apply();
    }

    public void setFirstLanguage(CustomLocale language) {
        this.firstLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("firstLanguage", language.getCode());
        editor.apply();
    }

    public void setSecondLanguage(CustomLocale language) {
        this.secondLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("secondLanguage", language.getCode());
        editor.apply();
    }

    public void clearSecondLanguage() {
        this.secondLanguage = null;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        sharedPreferences.edit().remove("secondLanguage").apply();
    }

    /* VoxSwap 3-language model: sourceLanguage (what user speaks) + targetLanguage1 + targetLanguage2.
       sourceLanguage is new; targetLanguage1 delegates to firstLanguage, targetLanguage2 to secondLanguage. */

    public void getSourceLanguage(final boolean recycleResult, final GetLocaleListener responseListener) {
        new Thread() {
            @Override
            public void run() {
                CustomLocale language;
                if (recycleResult && Global.this.sourceLanguage != null) {
                    language = Global.this.sourceLanguage;
                } else {
                    SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                    String code = sharedPreferences.getString("sourceLanguage", CustomLocale.getDefault().getCode());
                    language = new CustomLocale(code);
                }
                Global.this.sourceLanguage = language;
                mainHandler.post(() -> responseListener.onSuccess(language));
            }
        }.start();
    }

    public void setSourceLanguage(CustomLocale language) {
        this.sourceLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("sourceLanguage", language.getCode());
        editor.apply();
    }

    /* Aliases for clarity */
    public void getTargetLanguage1(final boolean recycleResult, final GetLocaleListener responseListener) {
        getFirstLanguage(recycleResult, responseListener);
    }

    public void setTargetLanguage1(CustomLocale language) {
        setFirstLanguage(language);
    }

    public void getTargetLanguage2(final boolean recycleResult, final GetLocaleListener responseListener) {
        getSecondLanguage(recycleResult, responseListener);
    }

    public void setTargetLanguage2(CustomLocale language) {
        setSecondLanguage(language);
    }

    public void clearTargetLanguage2() {
        clearSecondLanguage();
    }

    public void setFirstTextLanguage(CustomLocale language) {
        this.firstTextLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("firstTextLanguage", language.getCode());
        editor.apply();
    }

    public void setSecondTextLanguage(CustomLocale language) {
        this.secondTextLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("secondTextLanguage", language.getCode());
        editor.apply();
    }

    public int getAmplitudeThreshold() {
        return amplitudeThreshold;
    }

    public int getMicSensitivity() {
        if (micSensitivity == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            micSensitivity = sharedPreferences.getInt("micSensibility", 50);
            setAmplitudeThreshold(micSensitivity);
        }
        return micSensitivity;
    }

    public void setMicSensitivity(int value) {
        micSensitivity = value;
        setAmplitudeThreshold(micSensitivity);
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("micSensibility", value);
        editor.apply();
    }

    public int getSpeechTimeout() {
        if (speechTimeout == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            speechTimeout = sharedPreferences.getInt("speechTimeout", Recorder.DEFAULT_SPEECH_TIMEOUT_MILLIS);
        }
        return speechTimeout;
    }

    public void setSpeechTimeout(int value) {
        speechTimeout = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("speechTimeout", value);
        editor.apply();
    }

    public int getPrevVoiceDuration() {
        if (prevVoiceDuration == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            prevVoiceDuration = sharedPreferences.getInt("prevVoiceDuration", Recorder.DEFAULT_PREV_VOICE_DURATION);
        }
        return prevVoiceDuration;
    }

    public void setPrevVoiceDuration(int value) {
        prevVoiceDuration = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("prevVoiceDuration", value);
        editor.apply();
    }

    private void setAmplitudeThreshold(int micSensitivity) {
        float amplitudePercentage = 1f - (micSensitivity / 100f);
        if (amplitudePercentage < 0.5f) {
            amplitudeThreshold = Math.round(Recorder.MIN_AMPLITUDE_THRESHOLD + ((Recorder.DEFAULT_AMPLITUDE_THRESHOLD - Recorder.MIN_AMPLITUDE_THRESHOLD) * (amplitudePercentage * 2)));
        } else {
            amplitudeThreshold = Math.round(Recorder.DEFAULT_AMPLITUDE_THRESHOLD + ((Recorder.MAX_AMPLITUDE_THRESHOLD - Recorder.DEFAULT_AMPLITUDE_THRESHOLD) * ((amplitudePercentage - 0.5F) * 2)));
        }
    }

    public int getBeamSize() {
        if (beamSize == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            beamSize = sharedPreferences.getInt("beamSize", DEFAULT_BEAM_SIZE);
        }
        return beamSize;
    }

    public void setBeamSize(int value) {
        beamSize = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("beamSize", value);
        editor.apply();
    }

    public String getName() {
        if (name.length() == 0) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            name = sharedPreferences.getString("name", "user");
        }
        return name;
    }

    public void setName(String savedName) {
        name = savedName;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("name", savedName);
        editor.apply();
    }

    public abstract static class ResponseListener {
        public void onSuccess() {
        }

        public void onFailure(int[] reasons, long value) {
        }
    }

    public boolean isFirstStart() {
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        return sharedPreferences.getBoolean("firstStart", true);
    }

    public void setFirstStart(boolean firstStart) {
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("firstStart", firstStart);
        editor.apply();
    }

    private void createNotificationChannel(){
        String channelID = "service_background_notification";
        String channelName = getResources().getString(R.string.notification_channel_name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(channelID, channelName, NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(notificationChannel);
        }
    }

    public long getTotalRamSize(){
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long totalMemory = memInfo.totalMem / 1000000L;
        android.util.Log.i("memory", "Total memory: " + totalMemory);
        return totalMemory;
    }

    public long getAvailableRamSize(){
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long totalMemory = memInfo.availMem;
        android.util.Log.i("memory", "Total memory: " + totalMemory);
        return totalMemory / 1000000L;
    }

    public long getAvailableInternalMemorySize() {
        File internalFilesDir = this.getFilesDir();
        if(internalFilesDir != null) {
            long freeMBInternal = new File(internalFilesDir.getAbsoluteFile().toString()).getFreeSpace() / 1000000L;
            return freeMBInternal;
        }
        return -1;
    }

    public long getAvailableExternalMemorySize() {
        File externalFilesDir = this.getExternalFilesDir(null);
        if(externalFilesDir != null) {
            long freeMBExternal = new File(externalFilesDir.getAbsoluteFile().toString()).getFreeSpace() / 1000000L;
            return freeMBExternal;
        }
        return -1;
    }

    public boolean isNetworkOnWifi() {
        WifiManager wifi_m = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wifi_m.isWifiEnabled()) {
            WifiInfo wifi_i = wifi_m.getConnectionInfo();
            if (wifi_i.getNetworkId() == -1) {
                return false;
            }
            return true;
        } else {
            return false;
        }
    }
}
