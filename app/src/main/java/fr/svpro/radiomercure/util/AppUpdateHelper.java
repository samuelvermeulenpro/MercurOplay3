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
 *       appUpdateHelper.checkForUpdate();       // starts the flow if an update just became available
 *       appUpdateHelper.resumeUpdateIfNeeded(); // catches a download that finished while backgrounded
 *   }
 *
 *   onDestroy() {
 *       appUpdateHelper.unregister();
 *   }
 * </pre>
 */
public class AppUpdateHelper {

    private static final String TAG = "AppUpdateHelper";

    private final AppUpdateManager appUpdateManager;
    private final ActivityResultLauncher<IntentSenderRequest> updateLauncher;
    private final View snackbarAnchor;

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

    /** Checks Play for a newer version and silently starts the flexible download if one exists. */
    public void checkForUpdate() {
        appUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(this::startFlexibleUpdateIfAvailable)
                .addOnFailureListener(e ->
                        Log.d(TAG, "App update check failed (not installed via Play, offline, etc.): " + e.getMessage()));
    }

    /**
     * Call on every onResume(): if a flexible update finished downloading while the app was
     * backgrounded, the DOWNLOADED state can be missed by the listener above, so this
     * re-checks explicitly and re-shows the "restart to install" prompt if needed.
     */
    public void resumeUpdateIfNeeded() {
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                promptRestart();
            } else if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // A flow was already started earlier (e.g. app was killed mid-flow); resume it.
                startFlexibleUpdateIfAvailable(info);
            }
        });
    }

    private void startFlexibleUpdateIfAvailable(AppUpdateInfo info) {
        boolean updateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                || info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS;
        if (!updateAvailable || !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            return;
        }
        appUpdateManager.startUpdateFlowForResult(
                info,
                updateLauncher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build());
    }

    private void promptRestart() {
        Snackbar.make(snackbarAnchor, R.string.update_ready_message, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.update_restart_action, v -> appUpdateManager.completeUpdate())
                .show();
    }

    /** Call from onDestroy() to avoid leaking the install-state listener. */
    public void unregister() {
        appUpdateManager.unregisterListener(installStateListener);
    }
}
