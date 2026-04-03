package nie.translator.vtranslator.voice_translation.networking;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Requests a WiFi network from Android's ConnectivityManager and provides
 * the {@link Network} handle for per-socket binding.
 *
 * On no-internet WiFi hotspots (like VoxSwap box), Android normally drops
 * the WiFi connection and routes traffic through mobile data. Calling
 * {@code requestNetwork(TRANSPORT_WIFI)} tells the OS we explicitly need
 * this WiFi — it keeps the connection alive and gives us a Network object
 * to bind sockets to the WiFi interface.
 *
 * Key: the request does NOT include {@code NET_CAPABILITY_INTERNET}, so it
 * matches WiFi networks without internet connectivity.
 */
public class WifiNetworkBinder {

    private static final String TAG = "WifiNetworkBinder";

    private final ConnectivityManager connectivityManager;
    private final Listener listener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network wifiNetwork;

    public WifiNetworkBinder(Context context, Listener listener) {
        this.connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    /**
     * Start requesting a WiFi network. The callback fires asynchronously
     * when Android finds a matching WiFi network (which may already be connected).
     */
    public void request() {
        if (networkCallback != null) {
            Log.w(TAG, "Already requested, ignoring duplicate request()");
            return;
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                /* Do NOT add NET_CAPABILITY_INTERNET — the box hotspot has no internet,
                 * and adding this capability would prevent the request from matching it. */
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "WiFi network available: " + network);
                wifiNetwork = network;
                listener.onWifiNetworkAvailable(network);
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.d(TAG, "WiFi network lost: " + network);
                if (network.equals(wifiNetwork)) {
                    wifiNetwork = null;
                    listener.onWifiNetworkLost();
                }
            }
        };

        connectivityManager.requestNetwork(request, networkCallback);
        Log.d(TAG, "WiFi network requested");
    }

    /**
     * Stop the network request and release the callback. Safe to call
     * multiple times or if request() was never called.
     */
    public void release() {
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException e) {
                /* Callback was already unregistered — harmless */
                Log.w(TAG, "Callback already unregistered: " + e.getMessage());
            }
            networkCallback = null;
        }
        wifiNetwork = null;
        Log.d(TAG, "Released");
    }

    /**
     * @return the current WiFi Network, or null if not yet available / lost.
     */
    public Network getNetwork() {
        return wifiNetwork;
    }

    /**
     * Callbacks for WiFi network availability changes.
     * Both methods fire on a ConnectivityManager binder thread, not the main thread.
     */
    public interface Listener {
        void onWifiNetworkAvailable(Network network);
        void onWifiNetworkLost();
    }
}
