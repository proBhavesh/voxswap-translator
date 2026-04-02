package nie.translator.vtranslator;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import nie.translator.vtranslator.tools.CustomLocale;
import nie.translator.vtranslator.tools.VoiceDownloadManager;
import nie.translator.vtranslator.tools.gui.LanguagePickerAdapter;

public class LanguageSelectActivity extends AppCompatActivity {

    private LanguagePickerAdapter sourceAdapter;
    private LanguagePickerAdapter target1Adapter;
    private LanguagePickerAdapter target2Adapter;
    private Global global;
    private List<Long> activeDownloadIds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_language_select);

        global = (Global) getApplication();

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        MaterialButton doneButton = findViewById(R.id.doneButton);
        doneButton.setOnClickListener(v -> saveAndFinish());

        loadLanguages();
    }

    private void loadLanguages() {
        global.getLanguages(true, true, new Global.GetLocalesListListener() {
            @Override
            public void onSuccess(ArrayList<CustomLocale> languages) {
                global.getSourceLanguage(false, new Global.GetLocaleListener() {
                    @Override
                    public void onSuccess(CustomLocale sourceLang) {
                        global.getTargetLanguage1(false, new Global.GetLocaleListener() {
                            @Override
                            public void onSuccess(CustomLocale target1Lang) {
                                global.getTargetLanguage2(false, new Global.GetLocaleListener() {
                                    @Override
                                    public void onSuccess(CustomLocale target2Lang) {
                                        setupPickers(languages, sourceLang, target1Lang, target2Lang);
                                    }

                                    @Override
                                    public void onFailure(int[] reasons, long value) {
                                        setupPickers(languages, sourceLang, target1Lang, null);
                                    }
                                });
                            }

                            @Override
                            public void onFailure(int[] reasons, long value) {
                                setupPickers(languages, sourceLang, null, null);
                            }
                        });
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        setupPickers(languages, null, null, null);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                Toast.makeText(LanguageSelectActivity.this, R.string.error_internet_lack_loading_languages, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setupPickers(ArrayList<CustomLocale> languages, CustomLocale source, CustomLocale target1, CustomLocale target2) {
        sourceAdapter = new LanguagePickerAdapter(languages, source);
        target1Adapter = new LanguagePickerAdapter(languages, target1);
        target2Adapter = new LanguagePickerAdapter(languages, target2, true);

        /* Show voice status on target pickers only — source doesn't need TTS */
        target1Adapter.setShowVoiceStatus(this);
        target2Adapter.setShowVoiceStatus(this);

        setupRecyclerView(R.id.sourceLanguageList, sourceAdapter);
        setupRecyclerView(R.id.targetLanguage1List, target1Adapter);
        setupRecyclerView(R.id.targetLanguage2List, target2Adapter);

        setupSearch(R.id.searchSource, sourceAdapter);
        setupSearch(R.id.searchTarget1, target1Adapter);
        setupSearch(R.id.searchTarget2, target2Adapter);
    }

    private void setupRecyclerView(int recyclerViewId, LanguagePickerAdapter adapter) {
        RecyclerView recyclerView = findViewById(recyclerViewId);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        recyclerView.setNestedScrollingEnabled(true);
    }

    private void setupSearch(int editTextId, LanguagePickerAdapter adapter) {
        EditText searchField = findViewById(editTextId);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void saveAndFinish() {
        /* Save language selections */
        if (sourceAdapter != null && sourceAdapter.getSelectedLanguage() != null) {
            global.setSourceLanguage(sourceAdapter.getSelectedLanguage());
        }
        if (target1Adapter != null && target1Adapter.getSelectedLanguage() != null) {
            global.setTargetLanguage1(target1Adapter.getSelectedLanguage());
        }
        if (target2Adapter != null) {
            if (target2Adapter.getSelectedLanguage() != null) {
                global.setTargetLanguage2(target2Adapter.getSelectedLanguage());
            } else {
                global.clearTargetLanguage2();
            }
        }

        /* Check if target languages need voice downloads */
        List<String> toDownload = new ArrayList<>();
        if (target1Adapter != null && target1Adapter.getSelectedLanguage() != null) {
            String lang = target1Adapter.getSelectedLanguage().getLanguage();
            if (VoiceDownloadManager.isVoiceAvailable(lang) && !VoiceDownloadManager.isVoiceDownloaded(this, lang)) {
                toDownload.add(lang);
            }
        }
        if (target2Adapter != null && target2Adapter.getSelectedLanguage() != null) {
            String lang = target2Adapter.getSelectedLanguage().getLanguage();
            if (VoiceDownloadManager.isVoiceAvailable(lang) && !VoiceDownloadManager.isVoiceDownloaded(this, lang)) {
                toDownload.add(lang);
            }
        }

        if (toDownload.isEmpty()) {
            finish();
        } else {
            showVoiceDownloadDialog(toDownload);
        }
    }

    private void showVoiceDownloadDialog(List<String> langCodes) {
        StringBuilder description = new StringBuilder();
        for (String lang : langCodes) {
            CustomLocale locale = CustomLocale.getInstance(lang);
            int sizeMB = VoiceDownloadManager.getVoiceModelSizeKB(lang) / 1000;
            description.append("• ").append(locale.getDisplayNameWithoutTTS())
                    .append(" (~").append(sizeMB).append(" MB)\n");
        }
        description.append("\n").append(getString(R.string.voice_download_message));

        TextView progressView = new TextView(this);
        progressView.setPadding(64, 32, 64, 16);
        progressView.setText(description);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_download_title)
                .setView(progressView)
                .setCancelable(false)
                .setNegativeButton(R.string.voice_download_skip, (d, w) -> {
                    if (activeDownloadIds != null) {
                        VoiceDownloadManager.cancelDownloads(this, activeDownloadIds);
                    }
                    finish();
                })
                .create();
        dialog.show();

        activeDownloadIds = VoiceDownloadManager.downloadVoices(this, langCodes, new VoiceDownloadManager.DownloadCallback() {
            @Override
            public void onProgress(String langCode, int percent) {
                if (isFinishing() || isDestroyed()) return;
                CustomLocale locale = CustomLocale.getInstance(langCode);
                runOnUiThread(() -> progressView.setText(
                        locale.getDisplayNameWithoutTTS() + ": " + percent + "%"));
            }

            @Override
            public void onComplete(String langCode) { }

            @Override
            public void onError(String langCode, String error) {
                if (isFinishing() || isDestroyed()) return;
                runOnUiThread(() -> Toast.makeText(LanguageSelectActivity.this,
                        getString(R.string.voice_download_error, langCode), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onAllComplete() {
                if (isFinishing() || isDestroyed()) return;
                runOnUiThread(() -> {
                    dialog.dismiss();
                    finish();
                });
            }
        });
    }
}
