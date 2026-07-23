package fr.svpro.radiomercure.peertube;

import android.content.Context;
import android.content.SharedPreferences;

import fr.svpro.radiomercure.util.Config;

/**
 * Holds the current PeerTube OAuth2 access/refresh tokens in {@link SharedPreferences},
 * seeded from {@link Config} on first run. Access tokens are short-lived (~1 day) and get
 * silently refreshed by {@link PeerTubeApiClient} using the longer-lived refresh token
 * (~2 weeks) whenever a call comes back 401 - see PeerTubeApiClient#executeAuthed.
 */
public class PeerTubeAuthStore {

    private static final String PREFS_NAME = "peertube_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";

    private final SharedPreferences prefs;

    public PeerTubeAuthStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, Config.PEERTUBE_USER_TOKEN);
    }

    public synchronized String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, Config.PEERTUBE_REFRESH_TOKEN);
    }

    public synchronized void saveTokens(String accessToken, String refreshToken) {
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken);
        if (refreshToken != null && !refreshToken.isEmpty()) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken);
        }
        editor.apply();
    }
}
