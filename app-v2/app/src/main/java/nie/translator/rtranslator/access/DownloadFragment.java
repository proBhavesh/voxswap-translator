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

package nie.translator.rtranslator.access;

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

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.LoadingActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.tools.FileTools;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;

public class DownloadFragment extends Fragment {
    public static final String[] DOWNLOAD_URLS = {
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_cache_initializer.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_decoder.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_embed_and_lm_head.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_encoder.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_cache_initializer.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_cache_initializer_batch.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_decoder.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_detokenizer.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_encoder.onnx",
            "https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_initializer.onnx"
    };
    public static final String[] DOWNLOAD_NAMES = {
            "NLLB_cache_initializer.onnx",
            "NLLB_decoder.onnx",
            "NLLB_embed_and_lm_head.onnx",
            "NLLB_encoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_cache_initializer_batch.onnx",
            "Whisper_decoder.onnx",
            "Whisper_detokenizer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_initializer.onnx"
    };
    public static final int[] DOWNLOAD_SIZES = {
            24000,
            171000,
            500000,
            254000,
            14000,
            14000,
            173000,
            461,
            88000,
            69
    };
    private static final long INTERVAL_TIME_FOR_GUI_UPDATES_MS = 100;
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
                startRTranslator();
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
                            startRTranslator();
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

    private void startRTranslator() {
        if (activity != null) {
            Intent intent = new Intent(activity, LoadingActivity.class);
            intent.putExtra("activity", "download");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            activity.finish();
        }
    }
}
