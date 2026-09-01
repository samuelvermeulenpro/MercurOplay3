package fr.svpro.radiomercure;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import fr.svpro.radiomercure.playback.PlaybackService;
import fr.svpro.radiomercure.util.AppUpdateHelper;

public class MainActivity extends AppCompatActivity {

    private static final long EXIT_CONFIRM_WINDOW_MS = 2000L;

    private AppUpdateHelper appUpdateHelper;
    private long lastBackPressTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Fragment navHostFragment = getSupportFragmentManager()
                .findFragmentById(R.id.navHostFragment);
        NavController navController = navHostFragment instanceof NavHostFragment
                ? ((NavHostFragment) navHostFragment).getNavController()
                : null;

        if (navController != null) {
            BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
            NavigationUI.setupWithNavController(bottomNav, navController);

            // "Contact" isn't a NavController destination (it's a plain form Activity), so
            // it can't be wired through NavigationUI like the other 3 tabs. Intercepting the
            // click here - after setupWithNavController - only replaces the tap listener;
            // the destination-changed listener that keeps the correct tab highlighted (set
            // up by setupWithNavController above) stays attached to navController and keeps
            // working normally.
            bottomNav.setOnItemSelectedListener(item -> {
                if (item.getItemId() == R.id.action_open_contact) {
                    startActivity(new Intent(this, fr.svpro.radiomercure.contact.ContactActivity.class));
                    return false; // don't visually select this item; leave the real tab highlighted
                }
                return NavigationUI.onNavDestinationSelected(item, navController);
            });
        }

        // Back press: let normal in-graph navigation happen first (e.g. PeerTube videos ->
        // channels list). Only once there's nothing left to pop - i.e. we're at a top-level
        // tab - does a double-tap-to-exit take over, explicitly stopping the shared playback
        // service rather than just backgrounding the app (which keeps it running on purpose).
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (navController != null && navController.popBackStack()) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < EXIT_CONFIRM_WINDOW_MS) {
                    exitAppAndStopPlayback();
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(MainActivity.this, R.string.exit_confirm_message, Toast.LENGTH_SHORT).show();
                }
            }
        });

        ImageButton buttonAbout = findViewById(R.id.buttonAbout);
        buttonAbout.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));

        // Must be registered unconditionally here (not inside a click listener or callback)
        // so the launcher exists before the Activity reaches STARTED.
        ActivityResultLauncher<IntentSenderRequest> updateLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> { /* Play Core reports progress via the install-state listener instead. */ });
        appUpdateHelper = new AppUpdateHelper(this, updateLauncher, findViewById(R.id.mainRoot));
    }

    /**
     * A deliberate hard exit: unlike pressing Home (which should keep the live stream/
     * podcast/PeerTube video playing in the background, by design), this explicitly stops
     * the shared {@link PlaybackService} - releasing the player/session and dismissing the
     * notification - before closing every Activity in the app's task.
     */
    @OptIn(markerClass = UnstableApi.class)
    private void exitAppAndStopPlayback() {
        stopService(new Intent(this, PlaybackService.class));
        finishAffinity();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appUpdateHelper.checkForUpdate();
        appUpdateHelper.resumeUpdateIfNeeded();
    }

    @Override
    protected void onDestroy() {
        appUpdateHelper.unregister();
        super.onDestroy();
    }
}
