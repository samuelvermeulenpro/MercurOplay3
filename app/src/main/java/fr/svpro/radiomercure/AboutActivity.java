package fr.svpro.radiomercure;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ImageButton buttonBack = findViewById(R.id.buttonBack);
        TextView textAppVersion = findViewById(R.id.textAppVersion);

        buttonBack.setOnClickListener(v -> finish());

        textAppVersion.setText(getString(R.string.about_version_format, BuildConfig.VERSION_NAME));

        findViewById(R.id.rowTerms).setOnClickListener(v -> openUrl(getString(R.string.about_terms_url)));
        findViewById(R.id.rowLicense).setOnClickListener(v -> openUrl(getString(R.string.about_license_url)));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.about_link_error, Toast.LENGTH_SHORT).show();
        }
    }
}
