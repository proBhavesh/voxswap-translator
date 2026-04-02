package nie.translator.vtranslator.voice_translation;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nie.translator.vtranslator.Global;
import nie.translator.vtranslator.R;
import nie.translator.vtranslator.tools.ErrorCodes;
import nie.translator.vtranslator.tools.gui.DeactivableButton;
import nie.translator.vtranslator.tools.gui.messages.MessagesAdapter;

public abstract class VoiceTranslationFragment extends Fragment {
    public static final int TIME_FOR_SCROLLING = 50;
    protected VoiceTranslationActivity activity;
    protected Global global;
    protected MessagesAdapter mAdapter;
    protected RecyclerView mRecyclerView;
    protected TextView description;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (VoiceTranslationActivity) requireActivity();
        global = (Global) activity.getApplication();
    }

    protected abstract void connectToService();

    protected abstract void deactivateInputs(int cause);

    protected abstract void activateInputs(boolean start);

    public abstract void restoreAttributesFromService();

    @Override
    public void onStart() {
        super.onStart();
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @Override
    public void onStop() {
        super.onStop();
        deactivateInputs(DeactivableButton.DEACTIVATED);
        if (activity.getCurrentFragment() != VoiceTranslationActivity.DEFAULT_FRAGMENT && !activity.isChangingConfigurations()) {
            Toast.makeText(activity, getResources().getString(R.string.toast_working_background), Toast.LENGTH_SHORT).show();
        }
    }

    protected void onFailureConnectingWithService(int[] reasons, long value) {
        for (int aReason : reasons) {
            switch (aReason) {
                case ErrorCodes.MISSED_ARGUMENT:
                case ErrorCodes.SAFETY_NET_EXCEPTION:
                case ErrorCodes.MISSED_CONNECTION:
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setMessage(R.string.error_internet_lack_accessing);
                    builder.setNegativeButton(R.string.exit, (dialog, which) -> activity.exitFromVoiceTranslation());
                    builder.setPositiveButton(R.string.retry, (dialogInterface, i) -> connectToService());
                    AlertDialog dialog = builder.create();
                    dialog.setCanceledOnTouchOutside(false);
                    dialog.show();
                    break;
                case ErrorCodes.MISSING_GOOGLE_TTS:
                    activity.showMissingGoogleTTSDialog(null);
                    break;
                case ErrorCodes.GOOGLE_TTS_ERROR:
                    activity.showGoogleTTSErrorDialog((d, which) -> connectToService());
                    break;
                default:
                    activity.onError(aReason, value);
                    break;
            }
        }
    }
}
