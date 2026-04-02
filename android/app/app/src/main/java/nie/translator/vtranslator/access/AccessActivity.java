package nie.translator.vtranslator.access;

import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import nie.translator.vtranslator.GeneralActivity;
import nie.translator.vtranslator.Global;
import nie.translator.vtranslator.R;


public class AccessActivity extends GeneralActivity {
    public static final int DOWNLOAD_FRAGMENT = 0;
    private Fragment fragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_access);

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        if (savedInstanceState != null) {
            fragment = getSupportFragmentManager().getFragment(savedInstanceState, "fragment_inizialization");
        } else {
            startFragment(DOWNLOAD_FRAGMENT, null);
        }
    }

    @Override
    protected void onStart() {
        Global global = (Global) getApplication();
        if(global != null){
            global.setAccessActivity(this);
        }
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Global global = (Global) getApplication();
        if(global != null){
            global.setAccessActivity(null);
        }
    }

    public void startFragment(int action, Bundle bundle) {
        switch (action) {
            case DOWNLOAD_FRAGMENT: {
                DownloadFragment downloadFragment = new DownloadFragment();
                if (bundle != null) {
                    downloadFragment.setArguments(bundle);
                }
                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
                transaction.replace(R.id.fragment_initialization_container, downloadFragment);
                transaction.commit();
                fragment = downloadFragment;
                break;
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        getSupportFragmentManager().putFragment(outState, "fragment_inizialization", fragment);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
