package fr.svpro.radiomercure.peertube;

import android.content.Context;
import android.content.SharedPreferences;

import fr.svpro.radiomercure.util.Config;

/**
 * Holds the current PeerTube OAuth2 access/refresh tokens in {@link SharedPreferences},
 * seeded from {@link Config} on first run. Access tokens are short-lived (~1 day) and get
 * silently refreshed by {@link PeerTubeApiClient} using the longer-lived refresh token
 * (~2 weeks) whenever a call comes back 401 - see PeerTubeApiClient#getWithAuthInternal.
 *
 * <p><b>Reseeding:</b> {@code getString(key, Config.X)} only ever falls back to the Config
 * value when the key has *never* been written. If the hardcoded tokens in Config.java are
 * later replaced (e.g. a new build ships with a freshly generated token pair), a device
 * that already persisted the old pair would otherwise keep using it forever, silently
 * shadowing the new Config values - and if that stale refresh token was itself invalidated
 * server-side in the meantime, every refresh attempt then fails for good, which looks like
 * "the access token never renews despite a refresh token being present". To avoid this, the
 * persisted pair is tagged with the Config refresh token that seeded it; if Config's value
 * ever changes, the stored pair is treated as stale and reset to the new Config values.
 */
public class PeerTubeAuthStore {

    private static final String PREFS_NAME = "peertube_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_SEEDED_FROM = "seeded_from_config_refresh_token";

    private final SharedPreferences prefs;

    public PeerTubeAuthStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        resetIfConfigTokensChanged();
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

    /**
     * If the refresh token hardcoded in Config.java differs from the one that seeded the
     * currently persisted pair (or nothing was ever persisted), (re)seed from Config and
     * remember the new marker. This makes updating the hardcoded credentials in a future
     * build actually take effect on devices that already ran an older build.
     */
    private synchronized void resetIfConfigTokensChanged() {
        String seededFrom = prefs.getString(KEY_SEEDED_FROM, null);
        if (!Config.PEERTUBE_REFRESH_TOKEN.equals(seededFrom)) {
            prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, Config.PEERTUBE_USER_TOKEN)
                    .putString(KEY_REFRESH_TOKEN, Config.PEERTUBE_REFRESH_TOKEN)
                    .putString(KEY_SEEDED_FROM, Config.PEERTUBE_REFRESH_TOKEN)
                    .apply();
        }
    }
}
