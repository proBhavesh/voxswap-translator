package nie.translator.rtranslator;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;

import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.gui.LanguagePickerAdapter;

public class LanguageSelectActivity extends AppCompatActivity {

    private LanguagePickerAdapter sourceAdapter;
    private LanguagePickerAdapter target1Adapter;
    private LanguagePickerAdapter target2Adapter;
    private Global global;

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
        finish();
    }
}
