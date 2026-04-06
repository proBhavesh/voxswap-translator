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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import nie.translator.vtranslator.access.DownloadFragment;
import nie.translator.vtranslator.access.Downloader;

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
        VOICE_SIZES.put("is", 73000);
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

    private static final class FileState {
        final String filename;
        final String lang;
        final long downloadId;
        volatile int percent = 0;
        volatile boolean dirty = false;

        FileState(String filename, String lang, long downloadId) {
            this.filename = filename;
            this.lang = lang;
            this.downloadId = downloadId;
        }
    }

    private static final class LangState {
        final List<FileState> files = new ArrayList<>();
        final AtomicInteger remainingFiles;
        volatile int lastPostedPercent = -1;

        LangState(int expectedFiles) {
            this.remainingFiles = new AtomicInteger(expectedFiles);
        }
    }

    /** Combined size for variant languages (both genders), single size otherwise. */
    public static int getVoiceModelSizeKB(String langCode) {
        Integer size = VOICE_SIZES.get(langCode);
        if (size == null) return -1;
        if (PiperVoiceCatalog.isVariant(langCode)) {
            return size * 2;
        }
        return size;
    }

    /**
     * Download all required voice files for the given languages in parallel.
     * Variant languages download both male and female voices. Per-language progress
     * is averaged across files; onComplete(lang) fires only when all files for that
     * language are present.
     */
    public static List<Long> downloadVoices(Context context, List<String> langCodes, DownloadCallback callback) {
        Context appContext = context.getApplicationContext();
        Downloader downloader = new Downloader(appContext);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        List<Long> allDownloadIds = new ArrayList<>();

        Map<String, List<String>> missingByLang = new LinkedHashMap<>();
        int totalFileCount = 0;
        for (String lang : langCodes) {
            if (!PiperVoiceCatalog.isAvailable(lang)) continue;
            List<String> missing = new ArrayList<>();
            for (String file : PiperVoiceCatalog.requiredFiles(lang)) {
                if (!PiperVoiceCatalog.voiceFileExists(appContext, file)) {
                    missing.add(file);
                }
            }
            if (!missing.isEmpty()) {
                missingByLang.put(lang, missing);
                totalFileCount += missing.size();
            }
        }

        if (missingByLang.isEmpty()) {
            mainHandler.post(callback::onAllComplete);
            return allDownloadIds;
        }

        /* filesById is the single source of truth keyed by DownloadManager id.
         * langStates holds direct FileState references so the polling loop never does a string lookup.
         * remainingFiles is set to the planned count up front so a fast download can't fire
         * onComplete(lang) before the rest of the files in that lang are even enqueued. */
        final ConcurrentHashMap<Long, FileState> filesById = new ConcurrentHashMap<>();
        final Map<String, LangState> langStates = new HashMap<>();
        for (Map.Entry<String, List<String>> e : missingByLang.entrySet()) {
            langStates.put(e.getKey(), new LangState(e.getValue().size()));
        }

        final AtomicInteger completedFileCount = new AtomicInteger(0);
        final int totalFileCountFinal = totalFileCount;

        final BroadcastReceiver[] receiverHolder = {null};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent.getAction() == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                final FileState fs = filesById.get(downloadId);
                if (fs == null) return;

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
                    File from = new File(appContext.getExternalFilesDir(null), fs.filename);
                    File to = new File(appContext.getFilesDir(), fs.filename);
                    FileTools.moveFile(from, to, new FileTools.MoveFileCallback() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "Voice file downloaded: " + fs.filename);
                            fs.percent = 100;
                            fs.dirty = true;
                            PiperVoiceCatalog.invalidate(fs.lang);
                            LangState ls = langStates.get(fs.lang);
                            if (ls != null && ls.remainingFiles.decrementAndGet() == 0) {
                                Log.i(TAG, "All voice files complete for: " + fs.lang);
                                mainHandler.post(() -> callback.onComplete(fs.lang));
                            }
                            checkAllDone();
                        }
                        @Override
                        public void onFailure() {
                            Log.e(TAG, "Transfer failed: " + fs.filename);
                            mainHandler.post(() -> callback.onError(fs.lang, "File transfer failed"));
                            checkAllDone();
                        }
                    });
                } else {
                    Log.e(TAG, "Download failed: " + fs.filename + " status=" + status);
                    mainHandler.post(() -> callback.onError(fs.lang, "Download failed"));
                    checkAllDone();
                }
            }

            private void checkAllDone() {
                if (completedFileCount.incrementAndGet() >= totalFileCountFinal) {
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

        for (Map.Entry<String, List<String>> e : missingByLang.entrySet()) {
            String lang = e.getKey();
            LangState ls = langStates.get(lang);
            for (String filename : e.getValue()) {
                String url = DownloadFragment.PIPER_BASE + filename;
                long downloadId = downloader.downloadModel(url, filename);
                FileState fs = new FileState(filename, lang, downloadId);
                filesById.put(downloadId, fs);
                ls.files.add(fs);
                allDownloadIds.add(downloadId);
                Log.i(TAG, "Started: " + filename + " (lang=" + lang + ", id=" + downloadId + ")");
            }
        }

        new Thread("voice-download-progress") {
            @Override
            public void run() {
                DownloadManager dm = appContext.getSystemService(DownloadManager.class);
                while (completedFileCount.get() < totalFileCountFinal) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }

                    List<FileState> pending = new ArrayList<>();
                    for (FileState fs : filesById.values()) {
                        if (fs.percent < 100) pending.add(fs);
                    }
                    if (pending.isEmpty()) continue;

                    long[] ids = new long[pending.size()];
                    for (int i = 0; i < pending.size(); i++) ids[i] = pending.get(i).downloadId;

                    Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(ids));
                    try {
                        if (cursor != null) {
                            int idIdx = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
                            int bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                            int totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                            while (cursor.moveToNext()) {
                                long id = cursor.getLong(idIdx);
                                long downloaded = cursor.getLong(bytesIdx);
                                long total = cursor.getLong(totalIdx);
                                if (total <= 0) continue;
                                FileState fs = filesById.get(id);
                                if (fs == null) continue;
                                int pct = (int) ((downloaded * 100) / total);
                                if (pct != fs.percent) {
                                    fs.percent = pct;
                                    fs.dirty = true;
                                }
                            }
                        }
                    } finally {
                        if (cursor != null) cursor.close();
                    }

                    for (Map.Entry<String, LangState> e : langStates.entrySet()) {
                        final String lang = e.getKey();
                        LangState ls = e.getValue();
                        boolean anyDirty = false;
                        int sum = 0;
                        for (FileState fs : ls.files) {
                            if (fs.dirty) anyDirty = true;
                            sum += fs.percent;
                        }
                        if (!anyDirty) continue;

                        int avg = sum / ls.files.size();
                        if (avg != ls.lastPostedPercent) {
                            ls.lastPostedPercent = avg;
                            final int avgFinal = avg;
                            mainHandler.post(() -> callback.onProgress(lang, avgFinal));
                        }
                        for (FileState fs : ls.files) {
                            fs.dirty = false;
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
