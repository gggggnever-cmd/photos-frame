package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google sign-in for a TV: OAuth 2.0 device flow ("go to google.com/device and enter this code").
 * Needs assets/oauth_client.json from a "TVs and Limited Input devices" OAuth client.
 */
final class Auth {
    static final String SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly";
    private static final String DEVICE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    static final class DeviceCode {
        final String deviceCode, userCode, verificationUrl;
        final long intervalMs, expiresAtMs;
        DeviceCode(JSONObject j) {
            deviceCode = j.optString("device_code");
            userCode = j.optString("user_code");
            verificationUrl = j.optString("verification_url", j.optString("verification_uri", "https://www.google.com/device"));
            intervalMs = j.optLong("interval", 5) * 1000;
            expiresAtMs = System.currentTimeMillis() + j.optLong("expires_in", 1800) * 1000;
        }
    }

    private final SharedPreferences prefs;
    private final String clientId, clientSecret;
    private String accessToken;
    private long accessExpiresAt;

    Auth(Context ctx) {
        prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE);
        String id = null, secret = null;
        try (InputStream in = ctx.getAssets().open("oauth_client.json")) {
            byte[] b = new byte[in.available()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONObject root = new JSONObject(new String(b, "UTF-8"));
            JSONObject c = root.has("installed") ? root.getJSONObject("installed") : root;
            id = c.optString("client_id", null);
            secret = c.optString("client_secret", null);
        } catch (Exception ignored) { }
        clientId = id;
        clientSecret = secret;
    }

    boolean isConfigured() { return clientId != null; }
    boolean isSignedIn() { return prefs.getString("refresh_token", null) != null; }
    void signOut() { prefs.edit().clear().apply(); accessToken = null; }

    DeviceCode startDeviceFlow() throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId);
        f.put("scope", SCOPE);
        return new DeviceCode(Http.form(DEVICE_URL, f));
    }

    /** Returns true once the user has approved; false while still pending. Throws if denied/expired. */
    boolean pollDeviceFlow(DeviceCode dc) throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId);
        if (clientSecret != null) f.put("client_secret", clientSecret);
        f.put("device_code", dc.deviceCode);
        f.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        try {
            store(Http.form(TOKEN_URL, f));
            return true;
        } catch (Http.HttpError e) {
            if (e.body.contains("authorization_pending") || e.body.contains("slow_down")) return false;
            throw e;
        }
    }

    /** A valid access token, refreshing it with the stored refresh token when needed. */
    synchronized String token() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < accessExpiresAt - 60000) return accessToken;
        String refresh = prefs.getString("refresh_token", null);
        if (refresh == null) throw new IOException("Not signed in");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId);
        if (clientSecret != null) f.put("client_secret", clientSecret);
        f.put("refresh_token", refresh);
        f.put("grant_type", "refresh_token");
        store(Http.form(TOKEN_URL, f));
        return accessToken;
    }

    private void store(JSONObject j) {
        accessToken = j.optString("access_token");
        accessExpiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000;
        String refresh = j.optString("refresh_token", null);
        if (refresh != null && !refresh.isEmpty()) prefs.edit().putString("refresh_token", refresh).apply();
    }
}
