package fr.svpro.radiomercure.util;

import android.app.Activity;
import android.content.IntentSender;
import android.util.Log;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.annotation.NonNull;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

import fr.svpro.radiomercure.R;

/**
 * Wraps Google Play's In-App Updates (Play Core) using a <b>flexible</b> flow: if a newer
 * version is available on Play, it downloads quietly in the background while the person
 * keeps using the app, then a Snackbar invites them to restart to finish installing it.
 * Nothing here is intrusive or blocking - this only does anything at all when the app was
 * installed via Google Play and Play itself reports a newer version is available.
 *
 * <p>Usage from an Activity:
 * <pre>
 *   private ActivityResultLauncher&lt;IntentSenderRequest&gt; updateLauncher;
 *   private AppUpdateHelper appUpdateHelper;
 *
 *   onCreate() {
 *       // Must be registered unconditionally before STARTED, e.g. as a field initializer
 *       // or here in onCreate - never inside a click listener or callback.
 *       updateLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -&gt; { });
 *       appUpdateHelper = new AppUpdateHelper(this, updateLauncher, findViewById(R.id.mainRoot));
 *   }
 *
 *   onResume() {
 *       appUpdateHelper.checkForUpdate(); // offers the update once, or the restart prompt once downloaded
 *   }
 *
 *   onDestroy() {
 *       appUpdateHelper.unregister();
 *   }
 * </pre>
 */
public class AppUpdateHelper {

    private static final String TAG = "AppUpdateHelper";

    /**
     * The update dialog is offered at most once per app launch. Play's dialog is its own
     * Activity, so closing it (accept or decline) resumes ours again - and Play keeps
     * reporting UPDATE_AVAILABLE while the download is pending, which used to re-open the
     * dialog right after confirming. Static so a configuration change (new Activity
     * instance) doesn't ask again either.
     */
    private static boolean updateFlowRequested = false;

    private final AppUpdateManager appUpdateManager;
    private final ActivityResultLauncher<IntentSenderRequest> updateLauncher;
    private final View snackbarAnchor;
    private Snackbar restartSnackbar;

    private final InstallStateUpdatedListener installStateListener = state -> {
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            promptRestart();
        }
    };

    public AppUpdateHelper(@NonNull Activity activity,
                            @NonNull ActivityResultLauncher<IntentSenderRequest> updateLauncher,
                            @NonNull View snackbarAnchor) {
        this.appUpdateManager = AppUpdateManagerFactory.create(activity);
        this.updateLauncher = updateLauncher;
        this.snackbarAnchor = snackbarAnchor;
        this.appUpdateManager.registerListener(installStateListener);
    }

    /**
     * Call on every onResume(). Shows the "restart to install" prompt if a flexible update
     * finished downloading (the listener can miss it while backgrounded); otherwise offers
     * the update once if Play reports one and no download is already under way.
     */
    public void checkForUpdate() {
        appUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(info -> {
                    if (info.installStatus() == InstallStatus.DOWNLOADED) {
                        promptRestart();
                    } else {
                        startFlexibleUpdateIfAvailable(info);
                    }
                })
                .addOnFailureListener(e ->
                        Log.d(TAG, "App update check failed (not installed via Play, offline, etc.): " + e.getMessage()));
    }

    private void startFlexibleUpdateIfAvailable(AppUpdateInfo info) {
        int status = info.installStatus();
        boolean alreadyUnderWay = status == InstallStatus.PENDING
                || status == InstallStatus.DOWNLOADING
                || status == InstallStatus.INSTALLING;
        if (updateFlowRequested
                || alreadyUnderWay
                || info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE
                || !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            return;
        }
        updateFlowRequested = true;
        appUpdateManager.startUpdateFlowForResult(
                info,
                updateLauncher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build());
    }

    private void promptRestart() {
        // Both the listener and checkForUpdate() can report DOWNLOADED: show a single prompt.
        if (restartSnackbar != null && restartSnackbar.isShownOrQueued()) return;
        restartSnackbar = Snackbar.make(snackbarAnchor, R.string.update_ready_message, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.update_restart_action, v -> appUpdateManager.completeUpdate());
        restartSnackbar.show();
    }

    /** Call from onDestroy() to avoid leaking the install-state listener. */
    public void unregister() {
        appUpdateManager.unregisterListener(installStateListener);
    }
}
