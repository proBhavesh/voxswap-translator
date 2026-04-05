package nie.translator.vtranslator.tools;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes translation captions to timestamped text files in Documents/VoxSwap/.
 * One file per recording session. Thread-safe (all public methods synchronized).
 */
public class CaptionFileWriter {
    private static final String TAG = "CaptionFileWriter";
    public static final String DIR_NAME = "VoxSwap";

    private File currentFile;
    private BufferedWriter writer;
    private long lastResultID = -1;
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final SimpleDateFormat fileNameFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);

    /**
     * Opens a new caption file for this session.
     * Checks storage permission first — skips silently if denied.
     *
     * @param context     used for permission check
     * @param sourceLang  display name of source language
     * @param target1Lang display name of target language 1
     * @param target2Lang display name of target language 2
     */
    public synchronized void open(Context context, String sourceLang, String target1Lang, String target2Lang) {
        if (writer != null) {
            close();
        }

        /* API 29+ (Android 10+): scoped storage grants Documents/ access without
         * WRITE_EXTERNAL_STORAGE. Only check the permission on older versions. */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && !Tools.hasPermissions(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            Log.w(TAG, "Storage permission not granted, skipping caption file creation");
            return;
        }

        try {
            File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            File voxswapDir = new File(docsDir, DIR_NAME);
            voxswapDir.mkdirs();

            Date now = new Date();
            String fileName = "VoxSwap_" + fileNameFormat.format(now) + ".txt";
            currentFile = new File(voxswapDir, fileName);

            writer = new BufferedWriter(new FileWriter(currentFile));

            /* Write header */
            SimpleDateFormat headerDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            writer.write("VoxSwap Translation Session");
            writer.newLine();
            writer.write(headerDateFormat.format(now));
            writer.newLine();

            StringBuilder langLine = new StringBuilder(sourceLang);
            if (target1Lang != null && !target1Lang.isEmpty()) {
                langLine.append(" → ").append(target1Lang);
                if (target2Lang != null && !target2Lang.isEmpty()) {
                    langLine.append(", ").append(target2Lang);
                }
            }
            writer.write(langLine.toString());
            writer.newLine();
            writer.write("========================================");
            writer.newLine();
            writer.flush();

            lastResultID = -1;
            Log.i(TAG, "Caption file opened: " + currentFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Failed to open caption file", e);
            writer = null;
            currentFile = null;
        }
    }

    /**
     * Appends a translation entry to the current file.
     * Groups entries by resultID — same resultID means translations of the same utterance.
     *
     * @param resultID       unique ID from Translator for this translation request
     * @param originalText   the source text (STT output)
     * @param translatedText the translated text
     * @param targetLangCode language code of the translation (e.g. "fr", "es")
     */
    public synchronized void appendEntry(long resultID, String originalText, String translatedText, String targetLangCode) {
        if (writer == null) return;

        try {
            if (resultID != lastResultID) {
                writer.newLine();
                writer.write("[" + timestampFormat.format(new Date()) + "] " + originalText);
                writer.newLine();
                lastResultID = resultID;
            }
            writer.write("    → [" + targetLangCode + "] " + translatedText);
            writer.newLine();
            writer.flush();

        } catch (IOException e) {
            Log.e(TAG, "Failed to write caption entry", e);
        }
    }

    /**
     * Closes the current file. Safe to call multiple times.
     *
     * @return absolute path of the closed file, or null if no file was open
     */
    public synchronized String close() {
        String path = null;
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close caption file", e);
            }
            writer = null;
            if (currentFile != null) {
                path = currentFile.getAbsolutePath();
                Log.i(TAG, "Caption file closed: " + path);
            }
        }
        lastResultID = -1;
        currentFile = null;
        return path;
    }

    /**
     * @return true if a file is currently open for writing
     */
    public synchronized boolean isOpen() {
        return writer != null;
    }
}
