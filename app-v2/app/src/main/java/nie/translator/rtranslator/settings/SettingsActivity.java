package nie.translator.rtranslator.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;

import nie.translator.rtranslator.GeneralActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.access.DownloadFragment;

public class SettingsActivity extends GeneralActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_new);

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        MaterialButton backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        MaterialButton redownloadButton = findViewById(R.id.redownloadButton);
        redownloadButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, AccessActivity.class);
            startActivity(intent);
        });

        populateModelList();
    }

    private void populateModelList() {
        LinearLayout container = findViewById(R.id.modelListContainer);
        LayoutInflater inflater = getLayoutInflater();

        String[] names = DownloadFragment.DOWNLOAD_NAMES;
        int[] sizes = DownloadFragment.DOWNLOAD_SIZES;

        for (int i = 0; i < names.length; i++) {
            View row = inflater.inflate(R.layout.item_settings_model_row, container, false);

            TextView nameView = row.findViewById(R.id.modelName);
            TextView sizeView = row.findViewById(R.id.modelSize);
            TextView statusView = row.findViewById(R.id.modelStatus);

            /* Clean up the filename for display */
            String displayName = names[i].replace(".onnx", "").replace("_", " ");
            nameView.setText(displayName);

            int sizeMB = sizes[i] / 1000;
            sizeView.setText(sizeMB + " MB");

            File modelFile = new File(getFilesDir(), names[i]);
            if (modelFile.exists()) {
                statusView.setText(R.string.model_status_downloaded);
                statusView.setTextColor(ContextCompat.getColor(this, R.color.status_success));
            } else {
                statusView.setText(R.string.model_status_missing);
                statusView.setTextColor(ContextCompat.getColor(this, R.color.status_error));
            }

            container.addView(row);
        }
    }
}
