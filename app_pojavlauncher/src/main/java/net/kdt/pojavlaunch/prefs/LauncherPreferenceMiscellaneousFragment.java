package net.kdt.pojavlaunch.prefs.screens;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.GLInfoUtils;
import net.kdt.pojavlaunch.utils.RendererCompatUtil;

public class LauncherPreferenceMiscellaneousFragment extends LauncherPreferenceFragment {

    private ActivityResultLauncher<String[]> mPickImageLauncher;
    private ActivityResultLauncher<String[]> mPickVideoLauncher;
    private ActivityResultLauncher<String[]> mPickMusicLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Must be registered before STARTED, so it's done here rather than in onCreatePreferences
        mPickImageLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> handlePickedUri(uri, "backgroundImageUri", "image"));
        mPickVideoLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> handlePickedUri(uri, "backgroundVideoUri", "video"));
        mPickMusicLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> handlePickedUri(uri, "backgroundMusicUri", null));
    }

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        mVisibilityUpdater = this::updateVisibility;
        addPreferencesFromResource(R.xml.pref_misc);
        Preference driverPreference = requirePreference("zinkPreferSystemDriver");
        PackageManager packageManager = driverPreference.getContext().getPackageManager();
        boolean supportsTurnip = RendererCompatUtil.checkVulkanSupport(packageManager) && GLInfoUtils.getGlInfo().isAdreno();
        driverPreference.setVisible(supportsTurnip);
        setupMicrophoneRequestPreference();
        setupPersonalizationPreferences();
    }

    private void setupPersonalizationPreferences() {
        requirePreference("pickBackgroundImage").setOnPreferenceClickListener(p -> {
            mPickImageLauncher.launch(new String[]{"image/*"});
            return true;
        });
        requirePreference("pickBackgroundVideo").setOnPreferenceClickListener(p -> {
            mPickVideoLauncher.launch(new String[]{"video/*"});
            return true;
        });
        requirePreference("pickBackgroundMusic").setOnPreferenceClickListener(p -> {
            mPickMusicLauncher.launch(new String[]{"audio/*"});
            return true;
        });
    }

    /**
     * Persists read access to the picked file across reboots (needed since we only hold
     * a content:// Uri, not a copy of the file), saves it to preferences, and, if provided,
     * switches the "backgroundType" preference to match what was just picked (so choosing
     * a video, for example, automatically shows video instead of a stale "image" selection).
     */
    private void handlePickedUri(@Nullable Uri uri, String prefKey, @Nullable String backgroundTypeToSet) {
        if (uri == null) return;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers don't support persistable permissions; the Uri may stop working after a reboot.
        }
        SharedPreferences.Editor editor = getPreferenceManager().getSharedPreferences().edit();
        editor.putString(prefKey, uri.toString());
        if (backgroundTypeToSet != null) editor.putString("backgroundType", backgroundTypeToSet);
        editor.apply();
        LauncherPreferences.loadPreferences(getContext());
    }

    private void updateVisibility(){
        requirePreference("microphoneAccessRequest").setVisible(!getLauncherActivity().checkForPermissionRationale(33, Manifest.permission.RECORD_AUDIO));
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    private void setupMicrophoneRequestPreference() {
        Preference mRequestMicrophonePermissionPreference = requirePreference("microphoneAccessRequest");
        Activity activity = getActivity();
        if(activity instanceof LauncherActivity) {
            mRequestMicrophonePermissionPreference.setOnPreferenceClickListener(preference -> {
                ((LauncherActivity) activity).askForPermission(23, Manifest.permission.RECORD_AUDIO);
                return true;
            });
        } else {
            mRequestMicrophonePermissionPreference.setVisible(false);
        }
        updateVisibility();
    }
}
