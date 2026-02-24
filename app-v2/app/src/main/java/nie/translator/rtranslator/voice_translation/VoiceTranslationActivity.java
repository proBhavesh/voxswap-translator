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

package nie.translator.rtranslator.voice_translation;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import java.util.ArrayList;
import java.util.List;
import nie.translator.rtranslator.GeneralActivity;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.settings.SettingsActivity;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.CustomServiceConnection;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.services_communication.ServiceCommunicatorListener;
import nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie.WalkieTalkieFragment;
import nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie.WalkieTalkieService;


public class VoiceTranslationActivity extends GeneralActivity {
    //flags
    public static final int NORMAL_START = 0;
    public static final int FIRST_START = 1;
    //constants
    public static final int WALKIE_TALKIE_FRAGMENT = 0;
    public static final int DEFAULT_FRAGMENT = WALKIE_TALKIE_FRAGMENT;
    public static final int NO_PERMISSIONS = -10;
    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 2;
    public static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.RECORD_AUDIO,
    };
    //objects
    private Global global;
    private Fragment fragment;
    private CoordinatorLayout fragmentContainer;
    private int currentFragment = -1;
    private ArrayList<CustomServiceConnection> walkieTalkieServiceConnections = new ArrayList<>();
    private Handler mainHandler;
    //variables
    private int connectionId = 1;
    Configuration config;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        global = (Global) getApplication();
        mainHandler = new Handler(Looper.getMainLooper());

        // Clean fragments (only if the app is recreated (When user disable permission))
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        // Remove previous fragments (case of the app was restarted after changed permission on android 6 and higher)
        List<Fragment> fragmentList = fragmentManager.getFragments();
        for (Fragment fragment : fragmentList) {
            if (fragment != null) {
                fragmentManager.beginTransaction().remove(fragment).commit();
            }
        }

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        fragmentContainer = findViewById(R.id.fragment_container);
    }

    @Override
    protected void onStart() {
        super.onStart();
        setFragment(DEFAULT_FRAGMENT);
        if(getResources() != null) {
            config = getResources().getConfiguration();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    public void setFragment(int fragmentName) {
        switch (fragmentName) {
            case WALKIE_TALKIE_FRAGMENT: {
                if (getCurrentFragment() != WALKIE_TALKIE_FRAGMENT) {
                    WalkieTalkieFragment walkieTalkieFragment = new WalkieTalkieFragment();
                    FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                    Bundle bundle = new Bundle();
                    bundle.putBoolean("firstStart", true);
                    walkieTalkieFragment.setArguments(bundle);
                    transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
                    transaction.replace(R.id.fragment_container, walkieTalkieFragment);
                    transaction.commit();
                    currentFragment = WALKIE_TALKIE_FRAGMENT;
                    saveFragment();
                }
                break;
            }
        }
    }

    public void saveFragment() {
        new Thread("saveFragment") {
            @Override
            public void run() {
                super.run();
                SharedPreferences sharedPreferences = VoiceTranslationActivity.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt("fragment", getCurrentFragment());
                editor.apply();
            }
        }.start();
    }

    public int getCurrentFragment() {
        if (currentFragment != -1) {
            return currentFragment;
        } else {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (currentFragment != null) {
                if (currentFragment.getClass().equals(WalkieTalkieFragment.class)) {
                    return WALKIE_TALKIE_FRAGMENT;
                }
            }
        }
        return -1;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if(getResources() != null) {
            config = getResources().getConfiguration();
        }
    }

    /**
     * Handles user acceptance (or denial) of our permission request.
     */
    @CallSuper
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_CODE_REQUIRED_PERMISSIONS) {
            return;
        }

        for (int grantResult : grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(global, R.string.error_missing_location_permissions, Toast.LENGTH_LONG).show();
                return;
            }
        }
    }

    @Override
    public void onBackPressed() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (fragment != null) {
            if (fragment instanceof WalkieTalkieFragment) {
                super.onBackPressed();
            } else {
                super.onBackPressed();
            }
        } else {
            super.onBackPressed();
        }
    }

    public void exitFromVoiceTranslation() {
        setFragment(DEFAULT_FRAGMENT);
    }


    // services management

    public void startWalkieTalkieService(final Notification notification, final Global.ResponseListener responseListener) {
        final Intent intent = new Intent(this, WalkieTalkieService.class);
        /* VoxSwap: pass sourceLanguage + targetLanguage1 + targetLanguage2 */
        global.getSourceLanguage(false, new Global.GetLocaleListener() {
            @Override
            public void onSuccess(CustomLocale sourceLang) {
                intent.putExtra("sourceLanguage", sourceLang);
                global.getTargetLanguage1(false, new Global.GetLocaleListener() {
                    @Override
                    public void onSuccess(CustomLocale target1) {
                        intent.putExtra("firstLanguage", target1);
                        global.getTargetLanguage2(false, new Global.GetLocaleListener() {
                            @Override
                            public void onSuccess(CustomLocale target2) {
                                intent.putExtra("secondLanguage", target2);
                                if (NotificationManagerCompat.from(VoiceTranslationActivity.this).areNotificationsEnabled()) {
                                    intent.putExtra("notification", notification);
                                }
                                startService(intent);
                                responseListener.onSuccess();
                            }

                            @Override
                            public void onFailure(int[] reasons, long value) {
                                /* No target2 selected — start service with only target1 */
                                if (NotificationManagerCompat.from(VoiceTranslationActivity.this).areNotificationsEnabled()) {
                                    intent.putExtra("notification", notification);
                                }
                                startService(intent);
                                responseListener.onSuccess();
                            }
                        });
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        responseListener.onFailure(reasons, value);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public synchronized void connectToWalkieTalkieService(final VoiceTranslationService.VoiceTranslationServiceCallback callback, final ServiceCommunicatorListener responseListener) {
        startWalkieTalkieService(buildNotification(), new Global.ResponseListener() {
            @Override
            public void onSuccess() {
                CustomServiceConnection walkieTalkieServiceConnection = new CustomServiceConnection(new WalkieTalkieService.WalkieTalkieServiceCommunicator(connectionId));
                connectionId++;
                walkieTalkieServiceConnection.addCallbacks(callback, responseListener);
                walkieTalkieServiceConnections.add(walkieTalkieServiceConnection);
                bindService(new Intent(VoiceTranslationActivity.this, WalkieTalkieService.class), walkieTalkieServiceConnection, BIND_ABOVE_CLIENT);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                responseListener.onFailure(reasons, value);
            }
        });
    }

    public void disconnectFromWalkieTalkieService(WalkieTalkieService.WalkieTalkieServiceCommunicator walkieTalkieServiceCommunicator) {
        int index = -1;
        boolean found = false;
        for (int i = 0; i < walkieTalkieServiceConnections.size() && !found; i++) {
            if (walkieTalkieServiceConnections.get(i).getServiceCommunicator().equals(walkieTalkieServiceCommunicator)) {
                index = i;
                found = true;
            }
        }
        if (index != -1) {
            CustomServiceConnection serviceConnection = walkieTalkieServiceConnections.remove(index);
            unbindService(serviceConnection);
            serviceConnection.onServiceDisconnected();
        }
    }

    public void stopWalkieTalkieService() {
        stopService(new Intent(this, WalkieTalkieService.class));
    }

    //notification
    private Notification buildNotification() {
        String channelID = "service_background_notification";
        Intent resultIntent = new Intent(this, VoiceTranslationActivity.class);
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntentWithParentStack(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelID);
        builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.walkietalkie_mode_running))
                .setContentIntent(resultPendingIntent)
                .setSmallIcon(R.drawable.mic_icon)
                .setOngoing(true)
                .setChannelId(channelID)
                .build();
        return builder.build();
    }

    public CoordinatorLayout getFragmentContainer() {
        return fragmentContainer;
    }

    public static class Callback {
        public void onMissingSearchPermission() {
        }

        public void onSearchPermissionGranted() {
        }
    }
}
