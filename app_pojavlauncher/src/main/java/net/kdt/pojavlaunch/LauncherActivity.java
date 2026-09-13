package net.kdt.pojavlaunch;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.ScaledVideoView;

import net.kdt.pojavlaunch.authenticator.accounts.Accounts;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.fragments.MicrosoftLoginFragment;
import net.kdt.pojavlaunch.fragments.SelectAuthFragment;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.InstanceInstaller;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.IconCacheJanitor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.services.ProgressServiceKeeper;
import net.kdt.pojavlaunch.tasks.MoJsonExtras;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.tasks.MoJsonDownloader;
import net.kdt.pojavlaunch.utils.NotificationUtils;

import git.artdeell.mojo.R;

public class LauncherActivity extends BaseActivity {
    public static final String SETTING_FRAGMENT_TAG = "SETTINGS_FRAGMENT";

    private FragmentContainerView mFragmentView;
    private ImageButton mSettingsButton;
    private ProgressLayout mProgressLayout;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private NotificationManager mNotificationManager;
    private static ActivityResultLauncher<String> mRequestPermissionLauncher;

    /* Personalization: custom menu background (image/video) and background music */
    private ImageView mBackgroundImageView;
    private FrameLayout mBackgroundVideoContainer;
    private ScaledVideoView mBackgroundVideoView;
    private View mBackgroundScrim;
    private MediaPlayer mBackgroundMusicPlayer;

    /* Allows to switch from one button "type" to another */
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbackListener = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            mSettingsButton.setImageDrawable(ContextCompat.getDrawable(getBaseContext(), f instanceof MainMenuFragment
                    ? R.drawable.ic_px_sliders : R.drawable.ic_px_home));
            // Re-apply personalization in case the user just changed it in the settings screen
            if(f instanceof MainMenuFragment) refreshPersonalization();
        }
    };

    /* Listener for the back button in settings */
    private final ExtraListener<String> mBackPreferenceListener = (key, value) -> {
        if(value.equals("true")) onBackPressed();
        return false;
    };

    /* Listener for the auth method selection screen */
    private final ExtraListener<Boolean> mSelectAuthMethod = (key, value) -> {
        // The "false" value is used to stop auth method selection
        FragmentManager manager = getSupportFragmentManager();
        if(!value || manager.isStateSaved()) return false;
        Fragment fragment = manager.findFragmentById(mFragmentView.getId());
        // Allow starting the add account only from the main menu, should it be moved to fragment itself ?
        if(!(fragment instanceof MainMenuFragment)) return false;

        Tools.swapFragment(this, SelectAuthFragment.class, SelectAuthFragment.TAG, null);
        return false;
    };

    /* Listener for the settings fragment */
    private final View.OnClickListener mSettingButtonListener = v -> {
        FragmentManager manager = getSupportFragmentManager();
        if(manager.isStateSaved()) return;
        Fragment fragment = manager.findFragmentById(mFragmentView.getId());
        if(fragment instanceof MainMenuFragment){
            Tools.swapFragment(this, LauncherPreferenceFragment.class, SETTING_FRAGMENT_TAG, null);
        } else{
            // The setting button doubles as a home button now
            Tools.backToMainMenu(this);
        }
    };

    private final ExtraListener<Boolean> mLaunchGameListener = (key, value) -> {
        if(mProgressLayout.hasProcesses()){
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }

        Instance selectedInstance = Instances.loadSelectedInstance();

        if(selectedInstance == null) {
            Toast.makeText(this, R.string.no_instance, Toast.LENGTH_LONG).show();
            return false;
        }

        if(selectedInstance.installer != null) {
            selectedInstance.installer.start();
            return false;
        }

        if (!Tools.isValidString(selectedInstance.versionId)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }

        if(Accounts.getCurrent() == null){
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return false;
        }
        String normalizedVersionId = MoJsonExtras.normalizeVersionId(selectedInstance.versionId);
        JVersionList.Version mcVersion = MoJsonExtras.getListedVersion(normalizedVersionId);
        new MoJsonDownloader().start(
                this.getAssets(),
                mcVersion,
                normalizedVersionId,
                new ContextAwareDoneListener(this, normalizedVersionId)
        );
        return false;
    };

    private final TaskCountListener mDoubleLaunchPreventionListener = taskCount -> {
        // Hide the notification that starts the game if there are tasks executing.
        // Prevents the user from trying to launch the game with tasks ongoing.
        if(taskCount > 0) {
            Tools.runOnUiThread(() ->
                    mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START)
            );
        }
        return false;
    };
    @Override
    protected boolean shouldIgnoreNotch() {
        return getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean setFullscreen() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pojav_launcher);

        try {
            Os.setenv("TMPDIR", Tools.DIR_CACHE.getAbsolutePath(), true);
         }
        catch (Exception e) {
            throw new RuntimeException(e);
        }

        IconCacheJanitor.runJanitor();

        getWindow().setBackgroundDrawable(null);
        bindViews();
        refreshPersonalization();
        mRequestPermissionLauncher = this.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) Tools.runOnUiThread(() -> Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show());
                }
        );
        checkNotificationPermission();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ProgressKeeper.addTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));

        mSettingsButton.setOnClickListener(mSettingButtonListener);
        ProgressKeeper.addTaskCountListener(mProgressLayout);
        ExtraCore.addExtraListener(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.addExtraListener(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);

        ExtraCore.addExtraListener(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        new AsyncVersionList().getVersionList(versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions));

        mProgressLayout.observe(ProgressLayout.DOWNLOAD_GAME);
        mProgressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        mProgressLayout.observe(ProgressLayout.INSTALL_MODPACK);
        mProgressLayout.observe(ProgressLayout.AUTHENTICATE);
        mProgressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
        mProgressLayout.observe(ProgressLayout.INSTANCE_INSTALL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        InstanceInstaller.postInstallCheck(this);
        resumeBackgroundMedia();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ContextExecutor.clearActivity();
        pauseBackgroundMedia();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbackListener, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mProgressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(mProgressLayout);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbackListener);
        releaseBackgroundMusic();
        if(mBackgroundVideoView != null) mBackgroundVideoView.stopPlayback();
    }

    /** Custom implementation to feel more natural when a backstack isn't present */
    @Override
    public void onBackPressed() {
        MicrosoftLoginFragment fragment = (MicrosoftLoginFragment) getVisibleFragment(MicrosoftLoginFragment.TAG);
        if(fragment != null){
            if(fragment.canGoBack()){
                fragment.goBack();
                return;
            }
        }

        super.onBackPressed();
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag){
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private Fragment getVisibleFragment(int id){
        Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    public void askForPermission(int minApi, final String permission) {
        if(Build.VERSION.SDK_INT < minApi) return;
        mRequestPermissionLauncher.launch(permission);
    }
    public boolean checkForPermission(int minApi, final String permission) {
        return Build.VERSION.SDK_INT < minApi ||
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_DENIED;
    }
    public boolean checkForPermissionRationale(int minApi, final String permission) {
        return checkForPermission(minApi, permission) || ActivityCompat.shouldShowRequestPermissionRationale(this, permission);
    }

    private void checkNotificationPermission() {
        if(LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK ||
            this.checkForPermission(33, Manifest.permission.POST_NOTIFICATIONS)) {
            return;
        }
        showNotificationPermissionReasoning();
    }

    private void showNotificationPermissionReasoning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(R.string.notification_permission_dialog_text)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        askForPermission(33, Manifest.permission.POST_NOTIFICATIONS))
                .setNegativeButton(android.R.string.cancel, (d, w)-> handleNoNotificationPermission())
                .show();
    }

    private void handleNoNotificationPermission() {
        LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = true;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(LauncherPreferences.PREF_KEY_SKIP_NOTIFICATION_CHECK, true)
                .apply();
    }

    /** Stuff all the view boilerplate here */
    private void bindViews(){
        mFragmentView = findViewById(R.id.container_fragment);
        mSettingsButton = findViewById(R.id.setting_button);
        mProgressLayout = findViewById(R.id.progress_layout);
        mBackgroundImageView = findViewById(R.id.launcher_background_image);
        mBackgroundVideoContainer = findViewById(R.id.launcher_background_video_container);
        mBackgroundVideoView = findViewById(R.id.launcher_background_video);
        mBackgroundScrim = findViewById(R.id.launcher_background_scrim);
    }

    /* ---------------- Personalization: custom menu background & background music ---------------- */

    /** Reads the current preferences and shows/hides/starts the custom background image, video and music accordingly. */
    private void refreshPersonalization() {
        boolean enabled = LauncherPreferences.PREF_BACKGROUND_MEDIA_ENABLED;
        boolean wantsImage = enabled && "image".equals(LauncherPreferences.PREF_BACKGROUND_TYPE)
                && LauncherPreferences.PREF_BACKGROUND_IMAGE_URI != null;
        boolean wantsVideo = enabled && "video".equals(LauncherPreferences.PREF_BACKGROUND_TYPE)
                && LauncherPreferences.PREF_BACKGROUND_VIDEO_URI != null;

        stopBackgroundVideo();

        if(wantsImage) {
            try {
                mBackgroundImageView.setImageURI(Uri.parse(LauncherPreferences.PREF_BACKGROUND_IMAGE_URI));
                mBackgroundImageView.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                mBackgroundImageView.setVisibility(View.GONE);
                wantsImage = false;
            }
        } else {
            mBackgroundImageView.setVisibility(View.GONE);
        }

        if(wantsVideo) {
            startBackgroundVideo(Uri.parse(LauncherPreferences.PREF_BACKGROUND_VIDEO_URI));
        } else {
            mBackgroundVideoContainer.setVisibility(View.GONE);
        }

        // Let the custom background show through the menu buttons area when a background is active
        boolean mediaActive = wantsImage || wantsVideo;
        View menuRoot = findViewById(R.id.fragment_menu_main);
        if(menuRoot != null) {
            menuRoot.setBackgroundColor(mediaActive ? Color.TRANSPARENT : ContextCompat.getColor(this, R.color.background_app));
        }
        if(mBackgroundScrim != null) {
            mBackgroundScrim.setVisibility(mediaActive ? View.VISIBLE : View.GONE);
        }

        refreshBackgroundMusic();
    }

    private void startBackgroundVideo(Uri videoUri) {
        try {
            mBackgroundVideoView.setVideoURI(videoUri);
            mBackgroundVideoView.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                mp.setVolume(0f, 0f); // The video's own audio track is muted; background music is handled separately
                mBackgroundVideoView.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                mBackgroundVideoView.start();
            });
            mBackgroundVideoView.setOnErrorListener((mp, what, extra) -> {
                mBackgroundVideoContainer.setVisibility(View.GONE);
                return true;
            });
            mBackgroundVideoContainer.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            mBackgroundVideoContainer.setVisibility(View.GONE);
        }
    }

    private void stopBackgroundVideo() {
        try {
            if(mBackgroundVideoView != null && mBackgroundVideoView.isPlaying()) mBackgroundVideoView.stopPlayback();
        } catch (Exception ignored) {}
    }

    private void refreshBackgroundMusic() {
        releaseBackgroundMusic();
        if(!LauncherPreferences.PREF_BACKGROUND_MEDIA_ENABLED || LauncherPreferences.PREF_MUTE_BACKGROUND_MUSIC) return;
        String musicUriString = LauncherPreferences.PREF_BACKGROUND_MUSIC_URI;
        if(musicUriString == null) return;
        try {
            mBackgroundMusicPlayer = new MediaPlayer();
            mBackgroundMusicPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mBackgroundMusicPlayer.setDataSource(this, Uri.parse(musicUriString));
            mBackgroundMusicPlayer.setLooping(true);
            mBackgroundMusicPlayer.setOnPreparedListener(MediaPlayer::start);
            mBackgroundMusicPlayer.prepareAsync();
        } catch (Exception e) {
            releaseBackgroundMusic();
        }
    }

    private void releaseBackgroundMusic() {
        if(mBackgroundMusicPlayer != null) {
            try { mBackgroundMusicPlayer.release(); } catch (Exception ignored) {}
            mBackgroundMusicPlayer = null;
        }
    }

    /** Called from onResume: resumes playback without restarting the video/music from the beginning. */
    private void resumeBackgroundMedia() {
        if(mBackgroundVideoContainer != null && mBackgroundVideoContainer.getVisibility() == View.VISIBLE
                && mBackgroundVideoView != null) {
            mBackgroundVideoView.start();
        }
        if(mBackgroundMusicPlayer != null && !mBackgroundMusicPlayer.isPlaying()) {
            try { mBackgroundMusicPlayer.start(); } catch (Exception ignored) {}
        }
    }

    /** Called from onPause: pauses playback (rather than releasing) so it can resume seamlessly. */
    private void pauseBackgroundMedia() {
        try {
            if(mBackgroundVideoView != null && mBackgroundVideoView.isPlaying()) mBackgroundVideoView.pause();
        } catch (Exception ignored) {}
        if(mBackgroundMusicPlayer != null && mBackgroundMusicPlayer.isPlaying()) {
            try { mBackgroundMusicPlayer.pause(); } catch (Exception ignored) {}
        }
    }
}
