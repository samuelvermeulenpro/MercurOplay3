package fr.svpro.radiomercure;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import fr.svpro.radiomercure.util.AppUpdateHelper;

public class MainActivity extends AppCompatActivity {

    private AppUpdateHelper appUpdateHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Fragment navHostFragment = getSupportFragmentManager()
                .findFragmentById(R.id.navHostFragment);
        if (navHostFragment instanceof NavHostFragment) {
            NavController navController = ((NavHostFragment) navHostFragment).getNavController();
            BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
            NavigationUI.setupWithNavController(bottomNav, navController);
        }

        ImageButton buttonAbout = findViewById(R.id.buttonAbout);
        buttonAbout.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));

        // Must be registered unconditionally here (not inside a click listener or callback)
        // so the launcher exists before the Activity reaches STARTED.
        ActivityResultLauncher<IntentSenderRequest> updateLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> { /* Play Core reports progress via the install-state listener instead. */ });
        appUpdateHelper = new AppUpdateHelper(this, updateLauncher, findViewById(R.id.mainRoot));
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
