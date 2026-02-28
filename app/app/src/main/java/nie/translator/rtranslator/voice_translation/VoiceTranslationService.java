package nie.translator.rtranslator.voice_translation;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Messenger;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import nie.translator.rtranslator.GeneralService;
import nie.translator.rtranslator.tools.Timer;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.TTS;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.tools.services_communication.ServiceCallback;
import nie.translator.rtranslator.tools.services_communication.ServiceCommunicator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;


public abstract class VoiceTranslationService extends GeneralService {
    public static final int AUTO_LANGUAGE = 0;
    public static final int FIRST_LANGUAGE = 1;
    public static final int SECOND_LANGUAGE = 2;
    // commands
    public static final int GET_ATTRIBUTES = 6;
    public static final int START_MIC = 0;
    public static final int STOP_MIC = 1;
    public static final int START_SOUND = 2;
    public static final int STOP_SOUND = 3;
    public static final int SET_EDIT_TEXT_OPEN = 7;
    public static final int RECEIVE_TEXT = 4;
    // callbacks
    public static final int ON_ATTRIBUTES = 5;
    public static final int ON_VOICE_STARTED = 0;
    public static final int ON_VOICE_ENDED = 1;
    public static final int ON_VOLUME_LEVEL = 17;
    public static final int ON_MIC_ACTIVATED = 10;
    public static final int ON_MIC_DEACTIVATED = 11;
    public static final int ON_MESSAGE = 2;
    public static final int ON_CONNECTED_BLUETOOTH_HEADSET = 15;
    public static final int ON_DISCONNECTED_BLUETOOTH_HEADSET = 16;
    public static final int ON_STOPPED = 6;
    public static final int ON_BOX_CONNECTED = 30;
    public static final int ON_BOX_DISCONNECTED = 31;

    // permissions
    public static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 3;
    public static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.RECORD_AUDIO,
    };

    // errors
    public static final int MISSING_MIC_PERMISSION = 400;

    // objects
    Notification notification;
    protected Recorder.Callback mVoiceCallback;
    protected Handler clientHandler;
    @Nullable
    protected Recorder mVoiceRecorder;   //this will be null if the user has not granted mic permission
    protected UtteranceProgressListener ttsListener;
    @Nullable
    protected TTS tts;
    protected Handler mainHandler;
    private static final long WAKELOCK_TIMEOUT = 600 * 1000L;  // 10 minutes, so if the service stopped without calling onDestroyed the wakeLock would still be released within 10 minutes (the wakeLock will be reacquired before the 10 minutes if the service is still running)
    private Timer wakeLockTimer;  // to reactivate the timer every 10 minutes, so as long as the service is active the wakelock will never expire
    private PowerManager.WakeLock screenWakeLock;

    // variables
    private ArrayList<GuiMessage> messages = new ArrayList<>(); // messages exchanged since the beginning of the service
    protected boolean isMicMute = true;
    protected boolean isAudioMute = false;
    protected boolean isEditTextOpen = false;
    protected int utterancesCurrentlySpeaking = 0;
    protected final Object mLock = new Object();
    protected boolean isMicActivated = true;
    protected boolean isMicAutomatic = true;
    protected boolean manualRecognizingFirstLanguage = false;
    protected boolean manualRecognizingSecondLanguage = false;
    protected boolean manualRecognizingAutoLanguage = false;

    /* synthesizeToFile support: utterance metadata and playback queue */
    private final AtomicLong utteranceCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, CustomLocale> utteranceLanguageMap = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PcmAudioData> playbackQueue = new ConcurrentLinkedQueue<>();
    private final Object playbackLock = new Object();
    private boolean isPlayingBack = false;
    @Nullable
    private AudioTrack currentAudioTrack;


    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(this.getMainLooper());
        //reset translator last input and last output text
        /*if(((Global) getApplication())!=null){
            Translator translator = ((Global) getApplication()).getTranslator();
            if(translator != null){
                translator.resetLastInputOutput();
            }
        }*/
        // wake lock initialization (to keep the process active when the phone is on standby)
        acquireWakeLock();
        // tts initialization
        ttsListener = new UtteranceProgressListener() {
            @Override
            public void onStart(String s) {
            }

            @Override
            public void onDone(String utteranceId) {
                /* Read the WAV file that synthesizeToFile wrote */
                File wavFile = new File(getCacheDir(), utteranceId + ".wav");
                CustomLocale language = utteranceLanguageMap.remove(utteranceId);

                if (wavFile.exists()) {
                    PcmAudioData pcmData = readWavFile(wavFile);
                    wavFile.delete();

                    if (pcmData != null) {
                        pcmData.language = language;

                        /* Notify subclass first — WalkieTalkieService grabs PCM for UDP */
                        onPcmGenerated(pcmData);

                        /* Queue for sequential local playback */
                        enqueuePlayback(pcmData);
                    }
                }

                /* Existing logic: decrement counter, restart mic if needed */
                synchronized (mLock) {
                    if (utterancesCurrentlySpeaking > 0) {
                        utterancesCurrentlySpeaking--;
                    }
                    if (utterancesCurrentlySpeaking == 0) {
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (shouldDeactivateMicDuringTTS()) {
                                    if (!isMicMute) {
                                        startVoiceRecorder();
                                    }
                                    notifyMicActivated();
                                }
                            }
                        }, 500);
                    }
                }
            }

            @Override
            public void onError(String s) {
            }
        };
        initializeTTS();
    }

    private void initializeTTS() {
        tts = new TTS(this, new TTS.InitListener() {  // tts initialization (to be improved, automatic package installation)
            @Override
            public void onInit() {
                if(tts != null) {
                    tts.setOnUtteranceProgressListener(ttsListener);
                }
            }

            @Override
            public void onError(int reason) {
                tts = null;
                notifyError(new int[]{reason}, -1);
                isAudioMute = true;
            }
        });
    }

    public abstract void initializeVoiceRecorder();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (notification == null) {
            notification = intent.getParcelableExtra("notification");
        }
        if (notification != null) {
            startForeground(11, notification);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    protected boolean isAudioMute() {
        return isAudioMute;
    }

    // voice recorder

    /*private static class StartVoiceRecorderTask extends AsyncTask<VoiceTranslationService, Void, VoiceTranslationService> {
        @Override
        protected VoiceTranslationService doInBackground(VoiceTranslationService... voiceTranslationServices) {
            if (voiceTranslationServices.length > 0) {
                return voiceTranslationServices[0];
            }
            return null;
        }

        @Override
        protected void onPostExecute(VoiceTranslationService voiceTranslationService) {
            super.onPostExecute(voiceTranslationService);
            if (voiceTranslationService != null) {
                voiceTranslationService.startVoiceRecorder();
            }
        }
    }*/

    public void startVoiceRecorder() {
        if (!Tools.hasPermissions(this, REQUIRED_PERMISSIONS)) {
            notifyError(new int[]{MISSING_MIC_PERMISSION}, -1);
        } else if(isMicAutomatic){
            if(mVoiceRecorder == null){
                initializeVoiceRecorder();
            }
            if (mVoiceRecorder != null && !isMicMute) {
                mVoiceRecorder.start();
            }
        }
    }

    public void stopVoiceRecorder() {
        if (mVoiceRecorder != null && isMicAutomatic) {
            mVoiceRecorder.stop();
        }
    }

    public void endVoice() {
        if(mVoiceRecorder != null) {
            mVoiceRecorder.end();
        }
    }

    protected int getVoiceRecorderSampleRate() {
        if (mVoiceRecorder != null) {
            return mVoiceRecorder.getSampleRate();
        } else {
            return 0;
        }
    }

    protected boolean isMetaText(String text){
        //returns true if one of the first 3 characters is a '[' or a '('
        if(text.length() >= 3) {
            return (text.charAt(0) == '[' || text.charAt(0) == '(') || (text.charAt(1) == '[' || text.charAt(1) == '(') || (text.charAt(2) == '[' || text.charAt(2) == '(');
        }else{
            return false;
        }
    }

    // tts

    public synchronized void speak(String result, CustomLocale language) {
        synchronized (mLock) {
            if (tts != null && tts.isActive() && !isAudioMute) {
                utterancesCurrentlySpeaking++;
                if (shouldDeactivateMicDuringTTS()) {
                    stopVoiceRecorder();
                    notifyMicDeactivated();
                }

                tts.setLanguage(language, this);

                /* Write to temp WAV file instead of playing directly */
                String utteranceId = "tts_" + utteranceCounter.getAndIncrement();
                utteranceLanguageMap.put(utteranceId, language);
                File wavFile = new File(getCacheDir(), utteranceId + ".wav");
                tts.synthesizeToFile(result, null, wavFile, utteranceId);
            }
        }
    }

    protected boolean shouldDeactivateMicDuringTTS() {
        return true;
    }

    /**
     * Called when TTS generates PCM audio. Override in WalkieTalkieService
     * to capture PCM for UDP streaming to box.
     * Called from a TTS background thread, before local playback starts.
     */
    protected void onPcmGenerated(PcmAudioData pcmData) {
        /* Base class does nothing — subclass hooks in here */
    }

    /* --- WAV reading --- */

    /**
     * Reads a WAV file produced by Android TTS synthesizeToFile().
     * Android's platform (FileSynthesisCallback) always writes a standard 44-byte header:
     * RIFF (12B) + fmt (24B) + data header (8B) = 44B, followed by raw PCM.
     * Returns null if the file is invalid or unreadable.
     */
    private PcmAudioData readWavFile(File wavFile) {
        try (FileInputStream fis = new FileInputStream(wavFile)) {
            byte[] header = new byte[44];
            if (fis.read(header) < 44) return null;

            /* Verify RIFF/WAVE header */
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
                Log.e("VoiceTranslationService", "WAV file missing RIFF header");
                return null;
            }

            /* Parse WAV header fields (little-endian) */
            int channels = (header[22] & 0xFF) | ((header[23] & 0xFF) << 8);
            int sampleRate = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8)
                           | ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
            int dataSize = (header[40] & 0xFF) | ((header[41] & 0xFF) << 8)
                         | ((header[42] & 0xFF) << 16) | ((header[43] & 0xFF) << 24);

            /* Validate parsed values — reject clearly invalid data */
            if (channels < 1 || channels > 2 || sampleRate < 8000 || sampleRate > 48000 || dataSize <= 0) {
                Log.e("VoiceTranslationService", "WAV header has invalid values: channels=" + channels
                    + " sampleRate=" + sampleRate + " dataSize=" + dataSize);
                return null;
            }

            /* Read PCM data */
            byte[] pcmBytes = new byte[dataSize];
            int bytesRead = 0;
            while (bytesRead < dataSize) {
                int read = fis.read(pcmBytes, bytesRead, dataSize - bytesRead);
                if (read == -1) break;
                bytesRead += read;
            }

            return new PcmAudioData(pcmBytes, sampleRate, channels);
        } catch (IOException e) {
            Log.e("VoiceTranslationService", "Failed to read WAV file", e);
            return null;
        }
    }

    /* --- AudioTrack playback queue ---
     * synthesizeToFile completes faster than real-time, so multiple onDone() callbacks
     * can fire in quick succession. Without a queue, AudioTracks would overlap.
     * Each track's completion callback triggers the next one in the queue.
     */

    private void enqueuePlayback(PcmAudioData pcmData) {
        playbackQueue.add(pcmData);
        playNext();
    }

    private void playNext() {
        synchronized (playbackLock) {
            if (isPlayingBack) return;
            PcmAudioData pcmData = playbackQueue.poll();
            if (pcmData == null) return;

            /* Skip local speaker when box is receiving the audio */
            if (isBoxConnected()) {
                playNext();
                return;
            }

            isPlayingBack = true;

            int channelConfig = (pcmData.channels == 1)
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;

            AudioTrack audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcmData.sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(pcmData.pcmBytes.length)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();

            audioTrack.write(pcmData.pcmBytes, 0, pcmData.pcmBytes.length);

            /* Marker at end of audio — fires when playback finishes */
            int totalFrames = pcmData.pcmBytes.length / (2 * pcmData.channels);
            audioTrack.setNotificationMarkerPosition(totalFrames);
            audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override
                public void onMarkerReached(AudioTrack track) {
                    synchronized (playbackLock) {
                        track.stop();
                        track.release();
                        currentAudioTrack = null;
                        isPlayingBack = false;
                    }
                    playNext();
                }
                @Override
                public void onPeriodicNotification(AudioTrack track) {}
            });

            currentAudioTrack = audioTrack;
            audioTrack.play();
        }
    }

    /**
     * Stops any in-progress playback and discards all queued audio.
     * Called on STOP_SOUND command and in onDestroy().
     */
    private void stopPlayback() {
        synchronized (playbackLock) {
            playbackQueue.clear();
            if (currentAudioTrack != null) {
                try {
                    currentAudioTrack.stop();
                } catch (IllegalStateException ignored) {
                    /* AudioTrack may already be stopped */
                }
                currentAudioTrack.release();
                currentAudioTrack = null;
            }
            isPlayingBack = false;
        }
    }

    /* --- PCM audio data --- */

    protected static class PcmAudioData {
        public final byte[] pcmBytes;    /* raw 16-bit PCM, little-endian */
        public final int sampleRate;     /* e.g. 22050 or 24000 */
        public final int channels;       /* 1 = mono (always mono from Android TTS) */
        public CustomLocale language;    /* which language this audio is for */

        public PcmAudioData(byte[] pcmBytes, int sampleRate, int channels) {
            this.pcmBytes = pcmBytes;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    protected boolean isBluetoothHeadsetConnected() {
        return false;
    }

    protected boolean isBoxConnected() {
        return false;
    }

    // messages

    protected void addOrUpdateMessage(GuiMessage message) {
        if(message.getMessageID() != -1) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getMessageID() == message.getMessageID()) {
                    messages.set(i, message);
                    return;
                }
            }
        }
        messages.add(message);
    }

    // other

    private void startWakeLockReactivationTimer(final Timer.Callback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                wakeLockTimer = new Timer(WAKELOCK_TIMEOUT - 10000, 1000, callback);
                wakeLockTimer.start();
            }
        });
    }

    private void resetWakeLockReactivationTimer() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (wakeLockTimer != null) {
                    wakeLockTimer.cancel();
                    wakeLockTimer = null;
                }
            }
        });
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
        screenWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speechGoogleUserEdition:screenWakeLock");
        screenWakeLock.acquire(WAKELOCK_TIMEOUT);
        android.util.Log.i("performance", "WakeLock acquired");
        startWakeLockReactivationTimer(new Timer.Callback() {
            @Override
            public void onTick(long millisUntilEnd) {}

            @Override
            public void onEnd() {
                acquireWakeLock();
            }
        });
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
        // Stop listening to voice
        stopVoiceRecorder();
        if(mVoiceRecorder != null) {
            mVoiceRecorder.destroy();
            mVoiceRecorder = null;
        }
        //stop tts and audio playback
        stopPlayback();
        utteranceLanguageMap.clear();
        if(tts != null) {
            tts.stop();
            tts.shutdown();
        }
        //stop foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        //reset translator last input and last output text
        /*if(((Global) getApplication())!=null){
            Translator translator = ((Global) getApplication()).getTranslator();
            if(translator != null){
                translator.resetLastInputOutput();
            }
        }*/
        //release wake lock
        resetWakeLockReactivationTimer();
        if (screenWakeLock != null) {
            while (screenWakeLock.isHeld()) {
                screenWakeLock.release();
                android.util.Log.i("performance", "WakeLock released");
            }
            screenWakeLock = null;
        }
    }

    // communication

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new Messenger(clientHandler).getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    protected boolean executeCommand(int command, Bundle data) {
        if (!super.executeCommand(command, data)) {
            switch (command) {
                case START_MIC:
                    isMicMute = false;
                    startVoiceRecorder();
                    return true;
                case STOP_MIC:
                    if (data.getBoolean("permanent")) {
                        isMicMute = true;
                        if(mVoiceRecorder != null && mVoiceRecorder.isRecording()) {
                            endVoice();
                        }else {
                            stopVoiceRecorder();
                        }
                    }
                    stopVoiceRecorder();
                    return true;
                case START_SOUND:
                    isAudioMute = false;
                    if (tts != null && !tts.isActive()) {
                        initializeTTS();
                    }
                    return true;
                case STOP_SOUND:
                    isAudioMute = true;
                    if (utterancesCurrentlySpeaking > 0) {
                        utterancesCurrentlySpeaking = 0;
                        if(tts != null) {
                            tts.stop();
                        }
                        /* Clean up pending playback and orphaned metadata from cancelled synthesis */
                        stopPlayback();
                        utteranceLanguageMap.clear();
                        ttsListener.onDone("");
                    }
                    return true;
                case SET_EDIT_TEXT_OPEN:
                    isEditTextOpen = data.getBoolean("value");
                    return true;
                case GET_ATTRIBUTES:
                    Bundle bundle = new Bundle();
                    bundle.putInt("callback", ON_ATTRIBUTES);
                    bundle.putParcelableArrayList("messages", messages);
                    bundle.putBoolean("isMicMute", isMicMute);
                    bundle.putBoolean("isAudioMute", isAudioMute);
                    bundle.putBoolean("isTTSError", tts == null);
                    bundle.putBoolean("isEditTextOpen", isEditTextOpen);
                    bundle.putBoolean("isBluetoothHeadsetConnected", isBluetoothHeadsetConnected());
                    bundle.putBoolean("isBoxConnected", isBoxConnected());
                    bundle.putBoolean("isMicAutomatic", isMicAutomatic);
                    bundle.putBoolean("isMicActivated", isMicActivated);
                    if(mVoiceRecorder != null && mVoiceRecorder.isRecording()){
                        if(manualRecognizingFirstLanguage) {
                            bundle.putInt("listeningMic", FIRST_LANGUAGE);
                        } else if(manualRecognizingSecondLanguage) {
                            bundle.putInt("listeningMic", SECOND_LANGUAGE);
                        } else {
                            bundle.putInt("listeningMic", AUTO_LANGUAGE);
                        }
                    }else{
                        bundle.putInt("listeningMic", -1);
                    }
                    super.notifyToClient(bundle);
                    return true;
            }
            return false;
        }
        return true;
    }

    public void notifyMessage(GuiMessage message) {
        Bundle bundle = new Bundle();
        bundle.putInt("callback", ON_MESSAGE);
        bundle.putParcelable("message", message);
        super.notifyToClient(bundle);
    }

    protected void notifyVoiceStart() {
        Bundle bundle = new Bundle();
        bundle.clear();
        if(manualRecognizingFirstLanguage){
            bundle.putInt("mode", FIRST_LANGUAGE);
        }else if(manualRecognizingSecondLanguage){
            bundle.putInt("mode", SECOND_LANGUAGE);
        }else{
            bundle.putInt("mode", AUTO_LANGUAGE);
        }
        bundle.putInt("callback", ON_VOICE_STARTED);
        super.notifyToClient(bundle);
    }

    protected void notifyVoiceEnd() {
        Bundle bundle = new Bundle();
        bundle.clear();
        bundle.putInt("callback", ON_VOICE_ENDED);
        super.notifyToClient(bundle);
    }

    protected void notifyVolumeLevel(float volumeLevel) {
        Bundle bundle = new Bundle();
        bundle.clear();
        bundle.putInt("callback", ON_VOLUME_LEVEL);
        bundle.putFloat("volumeLevel", volumeLevel);
        super.notifyToClient(bundle);
    }

    protected void notifyMicActivated() {
        isMicActivated = true;
        Bundle bundle = new Bundle();
        bundle.clear();
        bundle.putInt("callback", ON_MIC_ACTIVATED);
        super.notifyToClient(bundle);
    }

    protected void notifyMicDeactivated() {
        isMicActivated = false;
        Bundle bundle = new Bundle();
        bundle.clear();
        bundle.putInt("callback", ON_MIC_DEACTIVATED);
        super.notifyToClient(bundle);
    }

    public void notifyError(int[] reasons, long value) {
        super.notifyError(reasons, value);
        if (mVoiceRecorder != null) {
            mVoiceCallback.onVoiceEnd();
        }
    }

    // connection with clients
    public static abstract class VoiceTranslationServiceCommunicator extends ServiceCommunicator {
        private ArrayList<VoiceTranslationServiceCallback> clientCallbacks = new ArrayList<>();
        private ArrayList<AttributesListener> attributesListeners = new ArrayList<>();

        protected VoiceTranslationServiceCommunicator(int id) {
            super(id);
        }

        protected boolean executeCallback(int callback, Bundle data) {
            if (callback != -1) {
                switch (callback) {
                    case ON_ATTRIBUTES: {
                        ArrayList<GuiMessage> messages = data.getParcelableArrayList("messages");
                        boolean isMicMute = data.getBoolean("isMicMute");
                        boolean isAudioMute = data.getBoolean("isAudioMute");
                        boolean isTTSError = data.getBoolean("isTTSError");
                        boolean isEditTextOpen = data.getBoolean("isEditTextOpen");
                        boolean isBluetoothHeadsetConnected = data.getBoolean("isBluetoothHeadsetConnected");
                        boolean isBoxConnected = data.getBoolean("isBoxConnected");
                        boolean isMicAutomatic = data.getBoolean("isMicAutomatic");
                        boolean isMicActivated = data.getBoolean("isMicActivated");
                        int listeningMic = data.getInt("listeningMic");
                        while (!attributesListeners.isEmpty()) {
                            attributesListeners.remove(0).onSuccess(messages, isMicMute, isAudioMute, isTTSError, isEditTextOpen, isBluetoothHeadsetConnected, isBoxConnected, isMicAutomatic, isMicActivated, listeningMic);
                        }
                        return true;
                    }
                    case ON_VOICE_STARTED: {
                        int mode = data.getInt("mode");
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onVoiceStarted(mode);
                        }
                        return true;
                    }
                    case ON_VOICE_ENDED: {
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onVoiceEnded();
                        }
                        return true;
                    }
                    case ON_VOLUME_LEVEL: {
                        float volumeLevel = data.getFloat("volumeLevel");
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onVolumeLevel(volumeLevel);
                        }
                        return true;
                    }
                    case ON_MIC_ACTIVATED: {
                        for (int i = 0; i < clientCallbacks.size(); i++){
                            clientCallbacks.get(i).onMicActivated();
                        }
                        return true;
                    }
                    case ON_MIC_DEACTIVATED: {
                        for (int i = 0; i < clientCallbacks.size(); i++){
                            clientCallbacks.get(i).onMicDeactivated();
                        }
                        return true;
                    }
                    case ON_MESSAGE: {
                        GuiMessage message = data.getParcelable("message");
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onMessage(message);
                        }
                        return true;
                    }
                    case ON_CONNECTED_BLUETOOTH_HEADSET: {
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onBluetoothHeadsetConnected();
                        }
                        return true;
                    }
                    case ON_DISCONNECTED_BLUETOOTH_HEADSET: {
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onBluetoothHeadsetDisconnected();
                        }
                        return true;
                    }
                    case ON_ERROR: {
                        int[] reasons = data.getIntArray("reasons");
                        long value = data.getLong("value");
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onError(reasons, value);
                        }
                        return true;
                    }
                    case ON_BOX_CONNECTED: {
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onBoxConnected();
                        }
                        return true;
                    }
                    case ON_BOX_DISCONNECTED: {
                        for (int i = 0; i < clientCallbacks.size(); i++) {
                            clientCallbacks.get(i).onBoxDisconnected();
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void initializeCommunication(Messenger serviceMessenger) {
            super.initializeCommunication(serviceMessenger);
            // we send our serviceMessenger which the service will use to communicate with us
            Bundle bundle = new Bundle();
            bundle.putInt("command", INITIALIZE_COMMUNICATION);
            bundle.putParcelable("messenger", new Messenger(serviceHandler));
            super.sendToService(bundle);
        }

        // commands

        public void getAttributes(AttributesListener responseListener) {
            attributesListeners.add(responseListener);
            if (attributesListeners.size() == 1) {
                Bundle bundle = new Bundle();
                bundle.putInt("command", GET_ATTRIBUTES);
                super.sendToService(bundle);
            }
        }

        public void startMic() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_MIC);
            super.sendToService(bundle);
        }

        public void stopMic(boolean permanent) {  //permanent=true se l' ha settato l' utente anzichè essere un automatismo
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_MIC);
            bundle.putBoolean("permanent", permanent);
            super.sendToService(bundle);
        }

        public void startSound() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_SOUND);
            super.sendToService(bundle);
        }

        public void stopSound() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_SOUND);
            super.sendToService(bundle);
        }

        public void setEditTextOpen(boolean editTextOpen) {
            Bundle bundle = new Bundle();
            bundle.putInt("command", SET_EDIT_TEXT_OPEN);
            bundle.putBoolean("value", editTextOpen);
            super.sendToService(bundle);
        }

        public void receiveText(String text) {
            Bundle bundle = new Bundle();
            bundle.putInt("command", RECEIVE_TEXT);
            bundle.putString("text", text);
            super.sendToService(bundle);
        }

        public void addCallback(ServiceCallback callback) {
            clientCallbacks.add((VoiceTranslationServiceCallback) callback);
        }

        public int removeCallback(ServiceCallback callback) {
            clientCallbacks.remove(callback);
            return clientCallbacks.size();
        }
    }

    public static abstract class VoiceTranslationServiceCallback extends ServiceCallback {
        public void onVoiceStarted(int mode) {
        }

        public void onVoiceEnded() {
        }

        public void onVolumeLevel(float volumeLevel) {
        }

        public void onMicActivated(){
        }

        public void onMicDeactivated(){
        }

        public void onMessage(GuiMessage message) {
        }

        public void onBluetoothHeadsetConnected() {
        }

        public void onBluetoothHeadsetDisconnected() {
        }

        public void onBoxConnected() {
        }

        public void onBoxDisconnected() {
        }
    }

    public interface AttributesListener {
        void onSuccess(ArrayList<GuiMessage> messages, boolean isMicMute, boolean isAudioMute, boolean isTTSError, boolean isEditTextOpen, boolean isBluetoothHeadsetConnected, boolean isBoxConnected, boolean isMicAutomatic, boolean isMicActivated, int listeningMic);
    }

    protected abstract class VoiceTranslationServiceRecognizerListener implements RecognizerListener {
        @Override
        public void onError(int[] reasons, long value) {
            notifyError(reasons, value);
        }
    }
}
