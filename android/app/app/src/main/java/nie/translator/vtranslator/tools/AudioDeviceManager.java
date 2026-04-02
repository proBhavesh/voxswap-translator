package nie.translator.vtranslator.tools;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import nie.translator.vtranslator.R;

/**
 * Centralizes audio device enumeration, display-name formatting, device lookup,
 * and Bluetooth SCO management for input/output device selection.
 */
public class AudioDeviceManager {
    private final Context context;
    private final AudioManager audioManager;

    public AudioDeviceManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public List<AudioDeviceInfo> getInputDevices() {
        return filterUserDevices(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS));
    }

    public List<AudioDeviceInfo> getOutputDevices() {
        return filterUserDevices(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
    }

    /* Only show devices the user would actually want to select —
     * filters out internal telephony endpoints, audio buses, etc. */
    private static List<AudioDeviceInfo> filterUserDevices(AudioDeviceInfo[] devices) {
        List<AudioDeviceInfo> result = new ArrayList<>();
        for (AudioDeviceInfo device : devices) {
            if (isUserFacingDevice(device.getType())) {
                result.add(device);
            }
        }
        return result;
    }

    private static boolean isUserFacingDevice(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return true;
            default:
                return false;
        }
    }

    @Nullable
    public AudioDeviceInfo findDeviceById(int deviceId, int direction) {
        AudioDeviceInfo[] devices = audioManager.getDevices(direction);
        for (AudioDeviceInfo device : devices) {
            if (device.getId() == deviceId) {
                return device;
            }
        }
        return null;
    }

    /* Fallback when device ID is not stable across reboots or BT reconnections */
    @Nullable
    public AudioDeviceInfo findDeviceByTypeAndName(int deviceType, @Nullable String productName, int direction) {
        if (deviceType == -1) return null;
        AudioDeviceInfo[] devices = audioManager.getDevices(direction);
        for (AudioDeviceInfo device : devices) {
            if (device.getType() == deviceType) {
                if (productName == null || productName.equals(device.getProductName().toString())) {
                    return device;
                }
            }
        }
        /* If exact name match failed, try type-only match */
        if (productName != null) {
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == deviceType) {
                    return device;
                }
            }
        }
        return null;
    }

    /**
     * @return human-readable label like "Galaxy Buds Pro (Bluetooth LE)" or "Built-in Mic"
     */
    public String getDeviceDisplayName(AudioDeviceInfo device) {
        String name = device.getProductName().toString().trim();
        String type = getTypeLabel(device.getType());
        if (name.isEmpty()) return type;
        return name + " (" + type + ")";
    }

    private String getTypeLabel(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return context.getString(R.string.audio_type_builtin_mic);
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return context.getString(R.string.audio_type_builtin_speaker);
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return context.getString(R.string.audio_type_earpiece);
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return context.getString(R.string.audio_type_bluetooth);
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return context.getString(R.string.audio_type_bluetooth_a2dp);
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return context.getString(R.string.audio_type_bluetooth_le);
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return context.getString(R.string.audio_type_bluetooth_le_speaker);
            case AudioDeviceInfo.TYPE_USB_DEVICE: return context.getString(R.string.audio_type_usb);
            case AudioDeviceInfo.TYPE_USB_HEADSET: return context.getString(R.string.audio_type_usb_headset);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return context.getString(R.string.audio_type_wired_headset);
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return context.getString(R.string.audio_type_wired_headphones);
            case AudioDeviceInfo.TYPE_AUX_LINE: return context.getString(R.string.audio_type_aux_line);
            default: return context.getString(R.string.audio_type_unknown);
        }
    }

    public static boolean requiresSco(AudioDeviceInfo device) {
        return device != null && device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }

    public void startSco() {
        audioManager.startBluetoothSco();
    }

    public void stopSco() {
        audioManager.stopBluetoothSco();
    }

    public void registerDeviceChangeCallback(AudioDeviceCallback callback) {
        audioManager.registerAudioDeviceCallback(callback, null);
    }

    public void unregisterDeviceChangeCallback(AudioDeviceCallback callback) {
        audioManager.unregisterAudioDeviceCallback(callback);
    }
}
