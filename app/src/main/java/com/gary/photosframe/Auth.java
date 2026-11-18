package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google sign-in for the TV, done from the phone.
 *
 * Google does not allow the Photos Picker scope with the TV "device code" flow (it answers
 * invalid_scope), so we use a Desktop OAuth client with PKCE: the phone opens Google's sign-in
 * page; afterwards Google sends the phone to http://127.0.0.1:8765/?code=..., which doesn't load
 * on a phone, and the user pastes that address into the TV's little setup page (see SetupServer).
 * Needs assets/oauth_client.json from a "Desktop app" OAuth client.
 */
final class Auth {
    static final String SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly";
    static final String REDIRECT = "http://127.0.0.1:8765";
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private final SharedPreferences prefs;
    private final String clientId, clientSecret;
    private String accessToken, verifier, state;
    private long accessExpiresAt;

    Auth(Context ctx) {
        prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE);
        String id = null, secret = null;
        try (InputStream in = ctx.getAssets().open("oauth_client.json")) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject root = new JSONObject(b.toString("UTF-8"));
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

    /** Starts a sign-in attempt and returns the Google page the phone should open. */
    synchronized String newSignInUrl() {
        SecureRandom r = new SecureRandom();
        byte[] v = new byte[48];
        r.nextBytes(v);
        verifier = b64(v);
        byte[] s = new byte[16];
        r.nextBytes(s);
        state = b64(s);
        try {
            String challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")));
            return AUTH_URL + "?client_id=" + enc(clientId) + "&redirect_uri=" + enc(REDIRECT)
                    + "&response_type=code&scope=" + enc(SCOPE) + "&state=" + enc(state)
                    + "&code_challenge=" + challenge + "&code_challenge_method=S256"
                    + "&access_type=offline&prompt=consent";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Finishes sign-in from what the user pasted: the whole 127.0.0.1 address (preferred) or just
     * the code. Throws with a human-readable message if it doesn't work.
     */
    synchronized void finishSignIn(String pasted) throws IOException {
        if (verifier == null) throw new IOException("Start again: open the sign-in link first.");
        String p = pasted == null ? "" : pasted.trim();
        String code = p, gotState = null;
        if (p.contains("code=") || p.contains("error=")) {
            String q = p.substring(p.indexOf('?') + 1);
            for (String kv : q.split("&")) {
                int i = kv.indexOf('=');
                if (i < 0) continue;
                String k = kv.substring(0, i), val = java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                if (k.equals("code")) code = val;
                if (k.equals("state")) gotState = val;
                if (k.equals("error")) throw new IOException("Google said: " + val);
            }
        }
        if (code.isEmpty()) throw new IOException("That doesn't look like the address from the sign-in page.");
        if (gotState != null && !gotState.equals(state))
            throw new IOException("That address is from an older sign-in attempt. Use the newest link.");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("code", code);
        f.put("client_id", clientId);
        if (clientSecret != null) f.put("client_secret", clientSecret);
        f.put("redirect_uri", REDIRECT);
        f.put("grant_type", "authorization_code");
        f.put("code_verifier", verifier);
        try {
            store(Http.form(TOKEN_URL, f));
        } catch (Http.HttpError e) {
            throw new IOException(e.body.contains("invalid_grant")
                    ? "That code has expired or was already used. Open the sign-in link again." : e.getMessage());
        }
        if (!isSignedIn()) throw new IOException("Google didn't return a lasting sign-in. Please try again.");
        verifier = null;
    }

    /** A valid access token; refreshes with the stored refresh token. Signs out if Google revoked it. */
    synchronized String token() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < accessExpiresAt - 60000) return accessToken;
        String refresh = prefs.getString("refresh_token", null);
        if (refresh == null) throw new SignInNeeded();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId);
        if (clientSecret != null) f.put("client_secret", clientSecret);
        f.put("refresh_token", refresh);
        f.put("grant_type", "refresh_token");
        try {
            store(Http.form(TOKEN_URL, f));
        } catch (Http.HttpError e) {
            if (e.body.contains("invalid_grant")) { signOut(); throw new SignInNeeded(); }
            throw e;
        }
        return accessToken;
    }

    /** Thrown when the TV needs a (new) sign-in, e.g. Google expired the old one. */
    static final class SignInNeeded extends IOException {
        SignInNeeded() { super("Sign-in needed"); }
    }

    private void store(JSONObject j) {
        accessToken = j.optString("access_token");
        accessExpiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000;
        String refresh = j.optString("refresh_token", null);
        if (refresh != null && !refresh.isEmpty()) prefs.edit().putString("refresh_token", refresh).apply();
    }

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }
    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
