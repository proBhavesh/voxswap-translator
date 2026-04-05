package nie.translator.vtranslator.access;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.button.MaterialButton;

import java.io.File;

import nie.translator.vtranslator.Global;
import nie.translator.vtranslator.LoadingActivity;
import nie.translator.vtranslator.R;
import nie.translator.vtranslator.tools.FileTools;
import nie.translator.vtranslator.voice_translation.neural_networks.NeuralNetworkApi;

public class DownloadFragment extends Fragment {
    private static final String HF_BASE = "https://huggingface.co/bhavsh/voxswap-models/resolve/main/";
    private static final String MODELS_BASE = HF_BASE;
    public static final String PIPER_BASE = HF_BASE;

    /* Core models downloaded on first launch — NLLB + Whisper + English Piper voice */
    public static final String[] DOWNLOAD_URLS = {
            MODELS_BASE + "NLLB_cache_initializer.onnx",
            MODELS_BASE + "NLLB_decoder.onnx",
            MODELS_BASE + "NLLB_embed_and_lm_head.onnx",
            MODELS_BASE + "NLLB_encoder.onnx",
            MODELS_BASE + "base-encoder.int8.onnx",
            MODELS_BASE + "base-decoder.int8.onnx",
            MODELS_BASE + "base-tokens.txt",
            PIPER_BASE + "en_US-lessac-medium.onnx",
    };
    public static final String[] DOWNLOAD_NAMES = {
            "NLLB_cache_initializer.onnx",
            "NLLB_decoder.onnx",
            "NLLB_embed_and_lm_head.onnx",
            "NLLB_encoder.onnx",
            "base-encoder.int8.onnx",
            "base-decoder.int8.onnx",
            "base-tokens.txt",
            "en_US-lessac-medium.onnx",
    };
    public static final int[] DOWNLOAD_SIZES = {
            24000,   /* NLLB_cache_initializer */
            171000,  /* NLLB_decoder */
            500000,  /* NLLB_embed_and_lm_head */
            254000,  /* NLLB_encoder */
            28000,   /* base-encoder.int8 */
            125000,  /* base-decoder.int8 */
            798,     /* base-tokens.txt */
            63000,   /* en_US-lessac-medium (English Piper voice) */
    };
    private static final long INTERVAL_TIME_FOR_GUI_UPDATES_MS = 500;
    private AccessActivity activity;
    private Global global;
    private Downloader downloader;
    private Thread guiUpdater;
    private android.os.Handler mainHandler;

    private RecyclerView modelListView;
    private MaterialButton actionButton;
    private ModelListAdapter adapter;

    public DownloadFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        modelListView = view.findViewById(R.id.modelList);
        actionButton = view.findViewById(R.id.actionButton);

        adapter = new ModelListAdapter(DOWNLOAD_NAMES, DOWNLOAD_SIZES);
        modelListView.setLayoutManager(new LinearLayoutManager(getContext()));
        modelListView.setHasFixedSize(true);
        modelListView.setAdapter(adapter);

        initializeModelStatuses();
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (AccessActivity) requireActivity();
        global = (Global) activity.getApplication();
        mainHandler = new android.os.Handler(Looper.getMainLooper());
        downloader = new Downloader(global);

        actionButton.setOnClickListener(v -> {
            SharedPreferences sharedPreferences = global.getSharedPreferences("default", Context.MODE_PRIVATE);
            long currentDownloadId = sharedPreferences.getLong("currentDownloadId", -1);
            if (currentDownloadId == -2) {
                startVTranslator();
            } else {
                retryCurrentDownload();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (global != null) {
            guiUpdater = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        if (getContext() != null) {
                            mainHandler.post(this::updateModelStatuses);
                        }
                        Thread.sleep(INTERVAL_TIME_FOR_GUI_UPDATES_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });

            SharedPreferences sharedPreferences = global.getSharedPreferences("default", Context.MODE_PRIVATE);
            long currentDownloadId = sharedPreferences.getLong("currentDownloadId", -1);

            if (currentDownloadId == -1) {
                new Thread(() -> DownloadReceiver.internalCheckAndStartNextDownload(global, downloader, -1)).start();
                guiUpdater.start();
            } else if (currentDownloadId == -2) {
                showContinueButton();
            } else {
                guiUpdater.start();
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (guiUpdater != null && !guiUpdater.isInterrupted()) {
            guiUpdater.interrupt();
        }
    }

    private void initializeModelStatuses() {
        if (getContext() == null) return;
        for (int i = 0; i < DOWNLOAD_NAMES.length; i++) {
            File internalFile = new File(requireContext().getFilesDir(), DOWNLOAD_NAMES[i]);
            if (internalFile.exists()) {
                adapter.updateStatus(i, ModelListAdapter.STATUS_DOWNLOADED);
            } else {
                File externalFile = new File(requireContext().getExternalFilesDir(null), DOWNLOAD_NAMES[i]);
                if (externalFile.exists()) {
                    adapter.updateStatus(i, ModelListAdapter.STATUS_TRANSFERRING);
                } else {
                    adapter.updateStatus(i, ModelListAdapter.STATUS_NOT_DOWNLOADED);
                }
            }
        }
    }

    private void updateModelStatuses() {
        if (getContext() == null || global == null) return;

        SharedPreferences sharedPreferences = global.getSharedPreferences("default", Context.MODE_PRIVATE);
        long currentDownloadId = sharedPreferences.getLong("currentDownloadId", -1);
        String lastDownloadSuccess = sharedPreferences.getString("lastDownloadSuccess", "");
        String lastTransferSuccess = sharedPreferences.getString("lastTransferSuccess", "");
        String lastTransferFailure = sharedPreferences.getString("lastTransferFailure", "");

        if (currentDownloadId == -2) {
            for (int i = 0; i < DOWNLOAD_NAMES.length; i++) {
                adapter.updateStatus(i, ModelListAdapter.STATUS_DOWNLOADED);
            }
            showContinueButton();
            return;
        }

        for (int i = 0; i < DOWNLOAD_NAMES.length; i++) {
            /* Skip File.exists() syscall for items already marked downloaded */
            if (adapter.getStatus(i) == ModelListAdapter.STATUS_DOWNLOADED) continue;

            File internalFile = new File(requireContext().getFilesDir(), DOWNLOAD_NAMES[i]);
            if (internalFile.exists()) {
                adapter.updateStatus(i, ModelListAdapter.STATUS_DOWNLOADED);
                continue;
            }

            if (NeuralNetworkApi.isVerifying) {
                int verifyingIndex = getVerifyingIndex(lastDownloadSuccess);
                if (i == verifyingIndex) {
                    adapter.updateStatus(i, ModelListAdapter.STATUS_VERIFYING);
                    continue;
                }
            }

            if (!lastDownloadSuccess.isEmpty() && DOWNLOAD_NAMES[i].equals(lastDownloadSuccess)
                    && !lastDownloadSuccess.equals(lastTransferSuccess)
                    && !lastDownloadSuccess.equals(lastTransferFailure)) {
                adapter.updateStatus(i, ModelListAdapter.STATUS_TRANSFERRING);
                continue;
            }

            if (DOWNLOAD_NAMES[i].equals(lastTransferFailure) && !lastTransferFailure.equals(lastTransferSuccess)) {
                adapter.updateStatus(i, ModelListAdapter.STATUS_ERROR);
                continue;
            }

            if (currentDownloadId >= 0) {
                int downloadingIndex = downloader.findDownloadUrlIndex(currentDownloadId);
                if (i == downloadingIndex) {
                    int downloadStatus = downloader.getRunningDownloadStatus();
                    if (downloadStatus == DownloadManager.STATUS_FAILED) {
                        adapter.updateStatus(i, ModelListAdapter.STATUS_ERROR);
                    } else {
                        int progress = downloader.getIndividualDownloadProgress(100);
                        adapter.updateStatusAndProgress(i, ModelListAdapter.STATUS_DOWNLOADING, progress);
                    }
                    continue;
                }
            }

            File externalFile = new File(requireContext().getExternalFilesDir(null), DOWNLOAD_NAMES[i]);
            if (externalFile.exists()) {
                adapter.updateStatus(i, ModelListAdapter.STATUS_TRANSFERRING);
            } else {
                adapter.updateStatus(i, ModelListAdapter.STATUS_NOT_DOWNLOADED);
            }
        }
    }

    private int getVerifyingIndex(String lastDownloadSuccess) {
        if (lastDownloadSuccess.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < DOWNLOAD_NAMES.length; i++) {
            if (DOWNLOAD_NAMES[i].equals(lastDownloadSuccess)) {
                return i + 1 < DOWNLOAD_NAMES.length ? i + 1 : -1;
            }
        }
        return -1;
    }

    private void showContinueButton() {
        actionButton.setText(R.string.button_continue);
    }

    private void retryCurrentDownload() {
        SharedPreferences sharedPreferences = global.getSharedPreferences("default", Context.MODE_PRIVATE);
        long currentDownloadId = sharedPreferences.getLong("currentDownloadId", -1);
        int urlIndex = downloader.findDownloadUrlIndex(currentDownloadId);
        if (urlIndex >= 0) {
            if (downloader.getRunningDownloadStatus() != DownloadManager.STATUS_RUNNING) {
                long downloadId = downloader.downloadModel(DOWNLOAD_URLS[urlIndex], DOWNLOAD_NAMES[urlIndex]);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putLong("currentDownloadId", downloadId);
                editor.apply();
            }
        } else {
            String lastDownloadSuccess = sharedPreferences.getString("lastDownloadSuccess", "");
            String lastTransferFailure = sharedPreferences.getString("lastTransferFailure", "");

            if (!lastTransferFailure.isEmpty() && !lastTransferFailure.equals(sharedPreferences.getString("lastTransferSuccess", ""))) {
                retryCurrentTransfer();
                return;
            }

            if (!lastDownloadSuccess.isEmpty()) {
                int nameIndex = -1;
                for (int i = 0; i < DOWNLOAD_NAMES.length; i++) {
                    if (DOWNLOAD_NAMES[i].equals(lastDownloadSuccess)) {
                        nameIndex = i;
                        break;
                    }
                }
                if (nameIndex != -1 && (nameIndex + 1) < DOWNLOAD_URLS.length) {
                    long downloadId = downloader.downloadModel(DOWNLOAD_URLS[nameIndex + 1], DOWNLOAD_NAMES[nameIndex + 1]);
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putLong("currentDownloadId", downloadId);
                    editor.apply();
                }
            } else {
                new Thread(() -> DownloadReceiver.internalCheckAndStartNextDownload(global, downloader, -1)).start();
            }
        }

        if (guiUpdater == null || guiUpdater.isInterrupted()) {
            guiUpdater = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        if (getContext() != null) {
                            mainHandler.post(this::updateModelStatuses);
                        }
                        Thread.sleep(INTERVAL_TIME_FOR_GUI_UPDATES_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            guiUpdater.start();
        }
    }

    private void retryCurrentTransfer() {
        SharedPreferences sharedPreferences = global.getSharedPreferences("default", Context.MODE_PRIVATE);
        String lastTransferFailure = sharedPreferences.getString("lastTransferFailure", "");
        if (lastTransferFailure.length() > 0) {
            int nameIndex = -1;
            for (int i = 0; i < DownloadFragment.DOWNLOAD_NAMES.length; i++) {
                if (DownloadFragment.DOWNLOAD_NAMES[i].equals(lastTransferFailure)) {
                    nameIndex = i;
                    break;
                }
            }
            if (nameIndex != -1) {
                File from = new File(global.getExternalFilesDir(null) + "/" + DownloadFragment.DOWNLOAD_NAMES[nameIndex]);
                File to = new File(global.getFilesDir() + "/" + DownloadFragment.DOWNLOAD_NAMES[nameIndex]);
                int finalNameIndex = nameIndex;
                FileTools.moveFile(from, to, new FileTools.MoveFileCallback() {
                    @Override
                    public void onSuccess() {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString("lastTransferSuccess", DownloadFragment.DOWNLOAD_NAMES[finalNameIndex]);
                        editor.apply();

                        if (finalNameIndex < (DownloadFragment.DOWNLOAD_URLS.length - 1)) {
                            new Thread(() -> DownloadReceiver.internalCheckAndStartNextDownload(global, downloader, finalNameIndex)).start();
                        } else {
                            editor = sharedPreferences.edit();
                            editor.putLong("currentDownloadId", -2);
                            editor.apply();
                            startVTranslator();
                        }
                    }

                    @Override
                    public void onFailure() {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString("lastTransferFailure", DownloadFragment.DOWNLOAD_NAMES[finalNameIndex]);
                        editor.apply();
                    }
                });
            }
        }
    }

    private void startVTranslator() {
        if (activity != null) {
            Intent intent = new Intent(activity, LoadingActivity.class);
            intent.putExtra("activity", "download");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            activity.finish();
        }
    }
}
