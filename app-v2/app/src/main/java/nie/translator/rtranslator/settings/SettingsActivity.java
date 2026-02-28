package nie.translator.rtranslator.settings;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;

import nie.translator.rtranslator.GeneralActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.access.DownloadFragment;
import nie.translator.rtranslator.tools.CustomServiceConnection;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.tools.services_communication.ServiceCommunicator;
import nie.translator.rtranslator.tools.services_communication.ServiceCommunicatorListener;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;
import nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie.WalkieTalkieService;

public class SettingsActivity extends GeneralActivity {
    private static final int COMMUNICATOR_ID = 1000;

    private View connectionDot;
    private TextView connectionLabel;
    private CustomServiceConnection serviceConnection;
    private boolean isBound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_new);

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        connectionDot = findViewById(R.id.connectionDot);
        connectionLabel = findViewById(R.id.connectionLabel);

        MaterialButton backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        MaterialButton redownloadButton = findViewById(R.id.redownloadButton);
        redownloadButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, AccessActivity.class);
            startActivity(intent);
        });

        populateModelList();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindToService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            serviceConnection.onServiceDisconnected();
            isBound = false;
        }
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

    private void bindToService() {
        WalkieTalkieService.WalkieTalkieServiceCommunicator communicator =
                new WalkieTalkieService.WalkieTalkieServiceCommunicator(COMMUNICATOR_ID);

        serviceConnection = new CustomServiceConnection(communicator);

        VoiceTranslationService.VoiceTranslationServiceCallback callback =
                new VoiceTranslationService.VoiceTranslationServiceCallback() {
                    @Override
                    public void onBoxConnected() {
                        super.onBoxConnected();
                        updateConnectionUI(true);
                    }

                    @Override
                    public void onBoxDisconnected() {
                        super.onBoxDisconnected();
                        updateConnectionUI(false);
                    }
                };

        ServiceCommunicatorListener responseListener = new ServiceCommunicatorListener() {
            @Override
            public void onServiceCommunicator(ServiceCommunicator serviceCommunicator) {
                WalkieTalkieService.WalkieTalkieServiceCommunicator comm =
                        (WalkieTalkieService.WalkieTalkieServiceCommunicator) serviceCommunicator;
                comm.getAttributes(new VoiceTranslationService.AttributesListener() {
                    @Override
                    public void onSuccess(ArrayList<GuiMessage> messages, boolean isMicMute, boolean isAudioMute,
                                          boolean isTTSError, boolean isEditTextOpen, boolean isBluetoothHeadsetConnected,
                                          boolean isBoxConnected, boolean isMicAutomatic, boolean isMicActivated,
                                          int listeningMic) {
                        updateConnectionUI(isBoxConnected);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                /* Service not available — keep default "Ready" state */
            }
        };

        serviceConnection.addCallbacks(callback, responseListener);

        Intent intent = new Intent(this, WalkieTalkieService.class);
        isBound = bindService(intent, serviceConnection, 0);
    }

    private void updateConnectionUI(boolean connected) {
        if (connected) {
            connectionLabel.setText(R.string.connection_connected);
            setConnectionDotColor(ContextCompat.getColor(this, R.color.brand_primary));
        } else {
            connectionLabel.setText(R.string.connection_ready);
            setConnectionDotColor(ContextCompat.getColor(this, R.color.gray_400));
        }
    }

    private void setConnectionDotColor(int color) {
        GradientDrawable bg = (GradientDrawable) connectionDot.getBackground();
        bg.setColor(color);
    }
}
