package nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.LanguageSelectActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.settings.SettingsActivity;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.gui.DeactivableButton;
import nie.translator.rtranslator.tools.gui.MicrophoneComunicable;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.tools.gui.messages.MessagesAdapter;
import nie.translator.rtranslator.tools.services_communication.ServiceCommunicator;
import nie.translator.rtranslator.tools.services_communication.ServiceCommunicatorListener;
import nie.translator.rtranslator.voice_translation.VoiceTranslationFragment;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;


public class WalkieTalkieFragment extends VoiceTranslationFragment implements MicrophoneComunicable {
    private boolean isRecording = false;
    private boolean isMicActivated = false;

    /* Views */
    private View statusDot;
    private TextView statusLabel;
    private ImageButton settingsButton;
    private MaterialCardView languageBar;
    private TextView sourceLanguageName;
    private TextView targetLanguagesText;
    private View micGlow;
    private ImageButton micButton;
    private TextView statusText;
    private TextView captionText;
    private MaterialButton changeLanguagesButton;

    /* Service connection */
    protected WalkieTalkieService.WalkieTalkieServiceCommunicator walkieTalkieServiceCommunicator;
    protected VoiceTranslationService.VoiceTranslationServiceCallback walkieTalkieServiceCallback;

    private Handler mHandler = new Handler();

    public WalkieTalkieFragment() {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        walkieTalkieServiceCommunicator = new WalkieTalkieService.WalkieTalkieServiceCommunicator(0);
        walkieTalkieServiceCallback = new HomeServiceCallback();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        statusDot = view.findViewById(R.id.statusDot);
        statusLabel = view.findViewById(R.id.statusLabel);
        settingsButton = view.findViewById(R.id.settingsButton);
        languageBar = view.findViewById(R.id.languageBar);
        sourceLanguageName = view.findViewById(R.id.sourceLanguageName);
        targetLanguagesText = view.findViewById(R.id.targetLanguagesText);
        micGlow = view.findViewById(R.id.micGlow);
        micButton = view.findViewById(R.id.micButton);
        statusText = view.findViewById(R.id.statusText);
        captionText = view.findViewById(R.id.captionText);
        changeLanguagesButton = view.findViewById(R.id.changeLanguagesButton);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(activity, SettingsActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        micButton.setOnClickListener(v -> {
            if (!isMicActivated) {
                return;
            }
            if (isRecording) {
                stopMicrophone(true);
            } else {
                startMicrophone(true);
            }
        });

        View.OnClickListener languageClick = v -> {
            Intent intent = new Intent(activity, LanguageSelectActivity.class);
            startActivity(intent);
        };
        languageBar.setOnClickListener(languageClick);
        changeLanguagesButton.setOnClickListener(languageClick);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getArguments() != null && getArguments().getBoolean("firstStart", false)) {
            getArguments().remove("firstStart");
            mHandler.postDelayed(this::connectToService, 300);
        } else {
            connectToService();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateLanguageDisplay();
    }

    @Override
    protected void connectToService() {
        activity.connectToWalkieTalkieService(walkieTalkieServiceCallback, new ServiceCommunicatorListener() {
            @Override
            public void onServiceCommunicator(ServiceCommunicator serviceCommunicator) {
                walkieTalkieServiceCommunicator = (WalkieTalkieService.WalkieTalkieServiceCommunicator) serviceCommunicator;
                restoreAttributesFromService();

                walkieTalkieServiceCommunicator.getFirstLanguage(language -> updateLanguageDisplay());
                walkieTalkieServiceCommunicator.getSecondLanguage(language -> updateLanguageDisplay());
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieFragment.super.onFailureConnectingWithService(reasons, value);
            }
        });
    }

    @Override
    public void restoreAttributesFromService() {
        walkieTalkieServiceCommunicator.getAttributes(new VoiceTranslationService.AttributesListener() {
            @Override
            public void onSuccess(ArrayList<GuiMessage> messages, boolean isMicMute, boolean isAudioMute, boolean isTTSError, boolean isEditTextOpen, boolean isBluetoothHeadsetConnected, boolean isMicAutomatic, boolean isMicActivatedParam, int listeningMic) {
                /* Set up messages adapter for caption updates */
                mAdapter = new MessagesAdapter(messages, global, () -> {
                    if (description != null) {
                        description.setVisibility(View.GONE);
                    }
                    if (mRecyclerView != null) {
                        mRecyclerView.setVisibility(View.VISIBLE);
                    }
                });
                if (mRecyclerView != null) {
                    mRecyclerView.setAdapter(mAdapter);
                }

                isMicActivated = isMicActivatedParam;

                /* Restore recording state */
                if (isMicActivatedParam && !isMicMute) {
                    setRecordingState(true);
                    if (listeningMic == VoiceTranslationService.AUTO_LANGUAGE) {
                        /* Voice is actively being captured */
                    }
                } else {
                    setRecordingState(false);
                }

                if (isMicActivatedParam) {
                    activateInputs(!isMicMute);
                } else {
                    deactivateInputs(DeactivableButton.DEACTIVATED);
                }
            }
        });
    }

    @Override
    public void startMicrophone(boolean changeAspect) {
        if (changeAspect) {
            setRecordingState(true);
        }
        walkieTalkieServiceCommunicator.startMic();
    }

    @Override
    public void stopMicrophone(boolean changeAspect) {
        if (changeAspect) {
            setRecordingState(false);
        }
        walkieTalkieServiceCommunicator.stopMic(changeAspect);
    }

    @Override
    protected void deactivateInputs(int cause) {
        isMicActivated = false;
        micButton.setAlpha(0.5f);
    }

    @Override
    protected void activateInputs(boolean start) {
        isMicActivated = true;
        micButton.setAlpha(1.0f);
        if (start) {
            setRecordingState(true);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mHandler.removeCallbacksAndMessages(null);
        activity.disconnectFromWalkieTalkieService(walkieTalkieServiceCommunicator);
    }

    private void setRecordingState(boolean recording) {
        isRecording = recording;
        if (recording) {
            micButton.setBackgroundResource(R.drawable.circle_recording);
            micButton.setImageResource(R.drawable.stop_icon);
            micGlow.setBackgroundResource(R.drawable.circle_glow_recording);
            statusText.setText(R.string.tap_to_stop);
            setStatusDotColor(ContextCompat.getColor(requireContext(), R.color.status_success));
            statusLabel.setText(R.string.status_listening);
        } else {
            micButton.setBackgroundResource(R.drawable.circle_brand);
            micButton.setImageResource(R.drawable.mic_icon);
            micGlow.setBackgroundResource(R.drawable.circle_glow);
            statusText.setText(R.string.tap_to_start);
            setStatusDotColor(ContextCompat.getColor(requireContext(), R.color.gray_400));
            statusLabel.setText(R.string.status_ready);
        }
    }

    private void setStatusDotColor(int color) {
        GradientDrawable bg = (GradientDrawable) statusDot.getBackground();
        bg.setColor(color);
    }

    private void updateLanguageDisplay() {
        if (global == null || !isAdded()) return;

        global.getSourceLanguage(false, new Global.GetLocaleListener() {
            @Override
            public void onSuccess(CustomLocale result) {
                if (isAdded()) {
                    sourceLanguageName.setText(result.getDisplayNameWithoutTTS());
                }
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                if (isAdded()) {
                    sourceLanguageName.setText("--");
                }
            }
        });

        global.getTargetLanguage1(false, new Global.GetLocaleListener() {
            @Override
            public void onSuccess(CustomLocale target1) {
                global.getTargetLanguage2(false, new Global.GetLocaleListener() {
                    @Override
                    public void onSuccess(CustomLocale target2) {
                        if (isAdded()) {
                            targetLanguagesText.setText(target1.getDisplayNameWithoutTTS() + ", " + target2.getDisplayNameWithoutTTS());
                        }
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        if (isAdded()) {
                            targetLanguagesText.setText(target1.getDisplayNameWithoutTTS());
                        }
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                if (isAdded()) {
                    targetLanguagesText.setText("--");
                }
            }
        });
    }

    private void updateCaptionText(GuiMessage message) {
        if (captionText == null || message == null || message.getMessage() == null) return;
        String text = message.getMessage().getText();
        if (text != null && !text.isEmpty()) {
            captionText.setText(text);
            captionText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
        }
    }

    @CallSuper
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != VoiceTranslationService.REQUEST_CODE_REQUIRED_PERMISSIONS) {
            return;
        }
        for (int grantResult : grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(activity, R.string.error_missing_mic_permissions, Toast.LENGTH_LONG).show();
                deactivateInputs(DeactivableButton.DEACTIVATED_FOR_MISSING_MIC_PERMISSION);
                return;
            }
        }
        if (isRecording && isMicActivated) {
            startMicrophone(false);
        }
    }


    public class HomeServiceCallback extends VoiceTranslationService.VoiceTranslationServiceCallback {
        @Override
        public void onVoiceStarted(int mode) {
            super.onVoiceStarted(mode);
            if (mode == VoiceTranslationService.AUTO_LANGUAGE) {
                /* Pulse the glow brighter while voice is detected */
                if (micGlow != null) {
                    micGlow.setAlpha(0.9f);
                }
            }
        }

        @Override
        public void onVoiceEnded() {
            super.onVoiceEnded();
            if (micGlow != null) {
                micGlow.setAlpha(1.0f);
            }
        }

        @Override
        public void onVolumeLevel(float volumeLevel) {
            super.onVolumeLevel(volumeLevel);
            if (micGlow != null && isRecording) {
                float scale = 1.0f + volumeLevel * 0.15f;
                micGlow.setScaleX(scale);
                micGlow.setScaleY(scale);
            }
        }

        @Override
        public void onMicActivated() {
            super.onMicActivated();
            activateInputs(false);
        }

        @Override
        public void onMicDeactivated() {
            super.onMicDeactivated();
            deactivateInputs(DeactivableButton.DEACTIVATED);
        }

        @Override
        public void onMessage(GuiMessage message) {
            super.onMessage(message);
            if (message != null) {
                updateCaptionText(message);

                /* Also update the messages adapter for compatibility */
                if (mAdapter != null) {
                    int messageIndex = mAdapter.getMessageIndex(message.getMessageID());
                    if (messageIndex != -1) {
                        mAdapter.setMessage(messageIndex, message);
                    } else {
                        mAdapter.addMessage(message);
                    }
                }
            }
        }

        @Override
        public void onError(int[] reasons, long value) {
            for (int aReason : reasons) {
                switch (aReason) {
                    case ErrorCodes.SAFETY_NET_EXCEPTION:
                    case ErrorCodes.MISSED_CONNECTION:
                        activity.showInternetLackDialog(R.string.error_internet_lack_services, null);
                        break;
                    case ErrorCodes.MISSING_GOOGLE_TTS:
                    case ErrorCodes.GOOGLE_TTS_ERROR:
                        /* TTS errors don't block translation, just log */
                        Log.w("HomeFragment", "TTS error: " + aReason);
                        break;
                    case VoiceTranslationService.MISSING_MIC_PERMISSION:
                        if (getContext() != null) {
                            requestPermissions(VoiceTranslationService.REQUIRED_PERMISSIONS, VoiceTranslationService.REQUEST_CODE_REQUIRED_PERMISSIONS);
                        }
                        break;
                    default:
                        activity.onError(aReason, value);
                        break;
                }
            }
        }
    }
}
