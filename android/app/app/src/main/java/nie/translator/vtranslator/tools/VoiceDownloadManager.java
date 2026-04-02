package nie.translator.vtranslator.tools;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import nie.translator.vtranslator.access.DownloadFragment;
import nie.translator.vtranslator.access.Downloader;
import nie.translator.vtranslator.voice_translation.VoiceTranslationService;

/**
 * Manages on-demand Piper TTS voice model downloads.
 * Reuses the existing Downloader class for actual download operations.
 * Voice models are downloaded in parallel for faster completion.
 */
public class VoiceDownloadManager {
    private static final String TAG = "VoiceDownload";

    private static final Map<String, Integer> VOICE_SIZES = new HashMap<>();
    static {
        VOICE_SIZES.put("ar", 63000);
        VOICE_SIZES.put("ca", 63000);
        VOICE_SIZES.put("cs", 63000);
        VOICE_SIZES.put("da", 63000);
        VOICE_SIZES.put("de", 63000);
        VOICE_SIZES.put("el", 63000);
        VOICE_SIZES.put("en", 63000);
        VOICE_SIZES.put("es", 63000);
        VOICE_SIZES.put("fa", 63000);
        VOICE_SIZES.put("fi", 63000);
        VOICE_SIZES.put("fr", 63000);
        VOICE_SIZES.put("hi", 63000);
        VOICE_SIZES.put("hu", 63000);
        VOICE_SIZES.put("id", 63000);
        VOICE_SIZES.put("is", 63000);
        VOICE_SIZES.put("it", 63000);
        VOICE_SIZES.put("ka", 63000);
        VOICE_SIZES.put("kk", 128000);
        VOICE_SIZES.put("lv", 63000);
        VOICE_SIZES.put("ml", 63000);
        VOICE_SIZES.put("ne", 77000);
        VOICE_SIZES.put("nl", 64000);
        VOICE_SIZES.put("no", 63000);
        VOICE_SIZES.put("pl", 63000);
        VOICE_SIZES.put("pt", 63000);
        VOICE_SIZES.put("ru", 63000);
        VOICE_SIZES.put("sk", 63000);
        VOICE_SIZES.put("sr", 77000);
        VOICE_SIZES.put("sv", 63000);
        VOICE_SIZES.put("sw", 63000);
        VOICE_SIZES.put("tr", 63000);
        VOICE_SIZES.put("uk", 77000);
        VOICE_SIZES.put("vi", 63000);
    }

    public interface DownloadCallback {
        void onProgress(String langCode, int percent);
        void onComplete(String langCode);
        void onError(String langCode, String error);
        void onAllComplete();
    }

    /**
     * Shared check: does a Piper voice model file exist in internal storage?
     * Used by VoiceTranslationService.hasPiperVoice() and the language picker UI.
     */
    public static boolean isVoiceDownloaded(Context context, String langCode) {
        String modelFile = VoiceTranslationService.PIPER_VOICE_MODELS.get(langCode);
        return modelFile != null && new File(context.getFilesDir(), modelFile).exists();
    }

    public static boolean isVoiceAvailable(String langCode) {
        return VoiceTranslationService.PIPER_VOICE_MODELS.containsKey(langCode);
    }

    public static int getVoiceModelSizeKB(String langCode) {
        Integer size = VOICE_SIZES.get(langCode);
        return size != null ? size : -1;
    }

    /**
     * Download voice models for the given language codes in parallel.
     * Returns a list of DownloadManager IDs that can be passed to cancelDownloads().
     */
    public static List<Long> downloadVoices(Context context, List<String> langCodes, DownloadCallback callback) {
        Context appContext = context.getApplicationContext();
        Downloader downloader = new Downloader(appContext);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        List<Long> allDownloadIds = new ArrayList<>();

        List<String> toDownload = new ArrayList<>();
        for (String lang : langCodes) {
            if (isVoiceAvailable(lang) && !isVoiceDownloaded(appContext, lang)) {
                toDownload.add(lang);
            }
        }

        if (toDownload.isEmpty()) {
            mainHandler.post(callback::onAllComplete);
            return allDownloadIds;
        }

        Map<Long, String> downloadIdToLang = new HashMap<>();
        /* Track which downloads are still in progress for polling */
        ConcurrentHashMap<String, Long> pendingDownloads = new ConcurrentHashMap<>();
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalCount = toDownload.size();

        final BroadcastReceiver[] receiverHolder = {null};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent.getAction() == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                String lang = downloadIdToLang.get(downloadId);
                if (lang == null) return;

                pendingDownloads.remove(lang);

                DownloadManager dm = appContext.getSystemService(DownloadManager.class);
                int status = -1;
                Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(downloadId));
                try {
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        if (statusIdx >= 0) status = cursor.getInt(statusIdx);
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    String filename = VoiceTranslationService.PIPER_VOICE_MODELS.get(lang);
                    File from = new File(appContext.getExternalFilesDir(null), filename);
                    File to = new File(appContext.getFilesDir(), filename);
                    FileTools.moveFile(from, to, new FileTools.MoveFileCallback() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "Voice downloaded: " + lang);
                            mainHandler.post(() -> callback.onComplete(lang));
                            checkAllDone();
                        }
                        @Override
                        public void onFailure() {
                            Log.e(TAG, "Transfer failed: " + lang);
                            mainHandler.post(() -> callback.onError(lang, "File transfer failed"));
                            checkAllDone();
                        }
                    });
                } else {
                    Log.e(TAG, "Download failed: " + lang + " status=" + status);
                    mainHandler.post(() -> callback.onError(lang, "Download failed"));
                    checkAllDone();
                }
            }

            private void checkAllDone() {
                if (completedCount.incrementAndGet() >= totalCount) {
                    mainHandler.post(callback::onAllComplete);
                    try { appContext.unregisterReceiver(receiverHolder[0]); } catch (Exception ignored) {}
                }
            }
        };
        receiverHolder[0] = receiver;

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }

        for (String lang : toDownload) {
            String filename = VoiceTranslationService.PIPER_VOICE_MODELS.get(lang);
            String url = DownloadFragment.PIPER_BASE + filename;
            long downloadId = downloader.downloadModel(url, filename);
            downloadIdToLang.put(downloadId, lang);
            pendingDownloads.put(lang, downloadId);
            allDownloadIds.add(downloadId);
            Log.i(TAG, "Started: " + lang + " (id=" + downloadId + ")");
        }

        /* Progress polling — only queries pending downloads, stops when all done */
        new Thread("voice-download-progress") {
            @Override
            public void run() {
                DownloadManager dm = appContext.getSystemService(DownloadManager.class);
                while (completedCount.get() < totalCount) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                    for (Map.Entry<String, Long> entry : pendingDownloads.entrySet()) {
                        String lang = entry.getKey();
                        long id = entry.getValue();
                        Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id));
                        try {
                            if (cursor != null && cursor.moveToFirst()) {
                                int bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                                int totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                                if (bytesIdx >= 0 && totalIdx >= 0) {
                                    long downloaded = cursor.getLong(bytesIdx);
                                    long total = cursor.getLong(totalIdx);
                                    if (total > 0) {
                                        int percent = (int) ((downloaded * 100) / total);
                                        mainHandler.post(() -> callback.onProgress(lang, percent));
                                    }
                                }
                            }
                        } finally {
                            if (cursor != null) cursor.close();
                        }
                    }
                }
            }
        }.start();

        return allDownloadIds;
    }

    public static void cancelDownloads(Context context, List<Long> downloadIds) {
        DownloadManager dm = context.getSystemService(DownloadManager.class);
        for (long id : downloadIds) {
            dm.remove(id);
        }
    }
}
