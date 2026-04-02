package nie.translator.vtranslator.settings;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import nie.translator.vtranslator.GeneralActivity;
import nie.translator.vtranslator.Global;
import nie.translator.vtranslator.R;
import nie.translator.vtranslator.access.AccessActivity;
import nie.translator.vtranslator.access.DownloadFragment;
import nie.translator.vtranslator.tools.AudioDeviceManager;
import nie.translator.vtranslator.tools.CustomServiceConnection;
import android.widget.Spinner;
import nie.translator.vtranslator.tools.gui.messages.GuiMessage;
import nie.translator.vtranslator.tools.services_communication.ServiceCommunicator;
import nie.translator.vtranslator.tools.services_communication.ServiceCommunicatorListener;
import nie.translator.vtranslator.voice_translation.VoiceTranslationService;
import nie.translator.vtranslator.voice_translation._walkie_talkie_mode._walkie_talkie.WalkieTalkieService;

public class SettingsActivity extends GeneralActivity {
    private static final int COMMUNICATOR_ID = 1000;

    private View connectionDot;
    private TextView connectionLabel;
    private CustomServiceConnection serviceConnection;
    private boolean isBound = false;

    /* Audio device selection */
    private Global global;
    private AudioDeviceManager audioDeviceManager;
    private Spinner inputDeviceSpinner;
    private Spinner outputDeviceSpinner;
    private ArrayAdapter<String> inputAdapter;
    private ArrayAdapter<String> outputAdapter;
    private List<AudioDeviceInfo> inputDevices = new ArrayList<>();
    private List<AudioDeviceInfo> outputDevices = new ArrayList<>();
    private AudioDeviceCallback deviceCallback;
    private boolean isRestoringSelection = false;
    private WalkieTalkieService.WalkieTalkieServiceCommunicator serviceCommunicator;
    private final Runnable refreshRunnable = this::refreshDeviceLists;
    private static final long REFRESH_DEBOUNCE_MS = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_new);

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        global = (Global) getApplication();

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
        setupAudioDeviceSelection();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (audioDeviceManager != null && deviceCallback != null) {
            audioDeviceManager.registerDeviceChangeCallback(deviceCallback);
            refreshDeviceLists();
        }
        bindToService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (audioDeviceManager != null && deviceCallback != null) {
            audioDeviceManager.unregisterDeviceChangeCallback(deviceCallback);
        }
        if (isBound) {
            unbindService(serviceConnection);
            serviceConnection.onServiceDisconnected();
            isBound = false;
        }
        serviceCommunicator = null;
    }

    private void setupAudioDeviceSelection() {
        audioDeviceManager = new AudioDeviceManager(this);

        inputDeviceSpinner = findViewById(R.id.inputDeviceSpinner);
        outputDeviceSpinner = findViewById(R.id.outputDeviceSpinner);

        inputAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        outputAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        inputDeviceSpinner.setAdapter(inputAdapter);
        outputDeviceSpinner.setAdapter(outputAdapter);

        refreshDeviceLists();

        AdapterView.OnItemSelectedListener deviceListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (isRestoringSelection) return;
                boolean isInput = (parent == inputDeviceSpinner);
                onDeviceSelected(isInput, pos);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };
        inputDeviceSpinner.setOnItemSelectedListener(deviceListener);
        outputDeviceSpinner.setOnItemSelectedListener(deviceListener);

        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                inputDeviceSpinner.removeCallbacks(refreshRunnable);
                inputDeviceSpinner.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
            }
            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                inputDeviceSpinner.removeCallbacks(refreshRunnable);
                inputDeviceSpinner.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
            }
        };
        /* Callback registered/unregistered in onStart/onStop */
    }

    private void refreshDeviceLists() {
        if (audioDeviceManager == null) return;
        isRestoringSelection = true;

        inputDevices = audioDeviceManager.getInputDevices();
        outputDevices = audioDeviceManager.getOutputDevices();

        inputAdapter.setNotifyOnChange(false);
        inputAdapter.clear();
        inputAdapter.add(getString(R.string.audio_system_default));
        for (AudioDeviceInfo d : inputDevices) {
            inputAdapter.add(audioDeviceManager.getDeviceDisplayName(d));
        }
        inputAdapter.notifyDataSetChanged();

        outputAdapter.setNotifyOnChange(false);
        outputAdapter.clear();
        outputAdapter.add(getString(R.string.audio_system_default));
        for (AudioDeviceInfo d : outputDevices) {
            outputAdapter.add(audioDeviceManager.getDeviceDisplayName(d));
        }
        outputAdapter.notifyDataSetChanged();

        int savedInputId = global.getPreferredInputDeviceId();
        inputDeviceSpinner.setSelection(findDeviceIndex(inputDevices, savedInputId));

        int savedOutputId = global.getPreferredOutputDeviceId();
        outputDeviceSpinner.setSelection(findDeviceIndex(outputDevices, savedOutputId));

        inputDeviceSpinner.post(() -> isRestoringSelection = false);
    }

    private int findDeviceIndex(List<AudioDeviceInfo> devices, int savedId) {
        if (savedId == 0) return 0;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getId() == savedId) return i + 1;
        }
        return 0;
    }

    private void onDeviceSelected(boolean isInput, int pos) {
        List<AudioDeviceInfo> devices = isInput ? inputDevices : outputDevices;
        int deviceId = (pos == 0) ? 0 : devices.get(pos - 1).getId();
        int currentId = isInput ? global.getPreferredInputDeviceId() : global.getPreferredOutputDeviceId();
        if (deviceId == currentId) return;

        AudioDeviceInfo selected = (pos == 0) ? null : devices.get(pos - 1);
        String name = selected != null ? selected.getProductName().toString() : null;
        int type = selected != null ? selected.getType() : -1;

        if (isInput) {
            global.setPreferredInputDevice(deviceId, name, type);
            if (serviceCommunicator != null) {
                serviceCommunicator.setInputDevice(deviceId);
            }
        } else {
            global.setPreferredOutputDevice(deviceId, name, type);
            if (serviceCommunicator != null) {
                serviceCommunicator.setOutputDevice(deviceId);
            }
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
                        runOnUiThread(() -> updateConnectionUI(true));
                    }

                    @Override
                    public void onBoxDisconnected() {
                        super.onBoxDisconnected();
                        runOnUiThread(() -> updateConnectionUI(false));
                    }
                };

        ServiceCommunicatorListener responseListener = new ServiceCommunicatorListener() {
            @Override
            public void onServiceCommunicator(ServiceCommunicator sc) {
                serviceCommunicator =
                        (WalkieTalkieService.WalkieTalkieServiceCommunicator) sc;

                /* Send saved device preferences to the service */
                int savedInputId = global.getPreferredInputDeviceId();
                if (savedInputId != 0) {
                    serviceCommunicator.setInputDevice(savedInputId);
                }
                int savedOutputId = global.getPreferredOutputDeviceId();
                if (savedOutputId != 0) {
                    serviceCommunicator.setOutputDevice(savedOutputId);
                }

                serviceCommunicator.getAttributes(new VoiceTranslationService.AttributesListener() {
                    @Override
                    public void onSuccess(ArrayList<GuiMessage> messages, boolean isMicMute, boolean isAudioMute,
                                          boolean isTTSError, boolean isEditTextOpen, boolean isBluetoothHeadsetConnected,
                                          boolean isBoxConnected, boolean isMicAutomatic, boolean isMicActivated,
                                          int listeningMic) {
                        runOnUiThread(() -> updateConnectionUI(isBoxConnected));
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
