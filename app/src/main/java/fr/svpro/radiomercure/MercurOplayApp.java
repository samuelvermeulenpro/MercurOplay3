package fr.svpro.radiomercure;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import java.security.Security;
import org.conscrypt.Conscrypt;

public class MercurOplayApp extends Application {

    private static final String TAG = "MercurOplayApp";

    public static final String PLAYBACK_CHANNEL_ID = "mercuroplay_playback_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        installModernTlsProvider();
        createNotificationChannel();
    }

    /**
     * Android only enables TLS 1.3 by default starting API 29 (Android 10). Below that -
     * which includes this app's whole minSdk 24-28 range - any server requiring/preferring
     * TLS 1.3 (a fairly common modern reverse-proxy default, e.g. nginx/Caddy "modern" TLS
     * profiles) fails the handshake outright on those devices, for every HTTPS call in the
     * app (PeerTube API, Icecast status, iTunes artwork, the podcast feed - not just one
     * endpoint). Installing Conscrypt as the top security provider gives OkHttp a TLS 1.3
     * capable engine on API 21+, independent of the platform's own TLS support.
     */
    private void installModernTlsProvider() {
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable t) {
            // Defensive: never let a TLS provider install failure crash app startup: worst
            // case, older devices fall back to the platform's own (TLS 1.2-only) provider.
            Log.w(TAG, "Could not install Conscrypt TLS provider, falling back to platform TLS", t);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    PLAYBACK_CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Lecture en cours - Radio Mercure");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
