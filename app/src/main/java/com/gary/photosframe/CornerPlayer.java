package com.gary.photosframe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * "YouTube corner" (asked 2026-11-21): YouTube's official embedded player in a small window over a corner
 * of the frame, chosen and controlled from the phone (CornerServer). The player isn't signed in (Google
 * blocks sign-in inside apps' web views), so public and unlisted videos and playlists play, with ads.
 * The page is loaded with an https base address for this app so YouTube gets the referrer it requires
 * from embedding apps. The window never takes the remote's focus; the frame keeps the remote.
 */
final class CornerPlayer {
    private static final String BASE = "https://com.gary.photosframe/";
    private static final int[] GRAVITY = {
            Gravity.BOTTOM | Gravity.END, Gravity.BOTTOM | Gravity.START, Gravity.TOP | Gravity.START, Gravity.TOP | Gravity.END};
    private static final int[] WIDTH_DP = {352, 512};   // 16:9 small / large on the 960 dp wide TV screen

    private final Context ctx;
    private final FrameLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private WebView web;
    private boolean pageReady;
    private String pendingJs;
    private volatile String state = "{}";
    private volatile boolean showing, wasPlaying;

    CornerPlayer(Context ctx, FrameLayout root) {
        this.ctx = ctx;
        this.root = root;
        prefs = ctx.getSharedPreferences("corner", Context.MODE_PRIVATE);
    }

    boolean showing() { return showing; }

    /** Called from the phone's thread as soon as a link is accepted, so buttons pressed right after work. */
    void markShowing() { showing = true; }

    /** Plays a link (video and/or playlist). Call on the UI thread. */
    void play(YouTubeLink link) {
        ensure();
        web.setVisibility(View.VISIBLE);
        showing = true;
        state = "{}";
        run("corner.load(" + JSONObject.quote(link.video == null ? "" : link.video) + ","
                + JSONObject.quote(link.list == null ? "" : link.list) + ")");
    }

    void toggle() { run("corner.toggle()"); }
    void next() { run("corner.next()"); }
    void prev() { run("corner.prev()"); }
    void volume(int delta) {
        int v = Math.max(0, Math.min(100, prefs.getInt("volume", 60) + delta));
        prefs.edit().putInt("volume", v).apply();
        run("corner.volume(" + v + ")");
    }

    /** Next corner, clockwise from bottom-right. */
    void move() {
        prefs.edit().putInt("corner", (prefs.getInt("corner", 0) + 1) % GRAVITY.length).apply();
        place();
    }

    void bigger(boolean big) { prefs.edit().putInt("size", big ? 1 : 0).apply(); place(); }

    /** Stops and hides the window (it comes back with the next link). */
    void close() {
        showing = false;
        state = "{}";
        if (web != null) { run("corner.stop()"); web.setVisibility(View.GONE); }
    }

    /** The frame went to the background: pause; it carries on when the frame comes back. */
    void onPause() {
        if (web == null || !showing) return;
        try { wasPlaying = new JSONObject(state).optInt("state", -1) == 1; } catch (Exception e) { wasPlaying = false; }
        run("corner.pause()");
    }

    void onResume() { if (web != null && showing && wasPlaying) run("corner.play()"); }

    void destroy() { if (web != null) { web.destroy(); web = null; } }

    /** What's playing, for the phone page: {title, author, state, index, count, volume, showing, corner, big}. */
    String state() {
        try {
            JSONObject j = new JSONObject(state);
            j.put("showing", showing);
            j.put("volume", prefs.getInt("volume", 60));
            j.put("big", prefs.getInt("size", 0) == 1);
            return j.toString();
        } catch (Exception e) {
            return "{\"showing\":" + showing + "}";
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensure() {
        if (web != null) return;
        web = new WebView(ctx);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                run("corner.volume(" + prefs.getInt("volume", 60) + ")");
                if (pendingJs != null) { String js = pendingJs; pendingJs = null; run(js); }
            }
        });
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void state(String json) { state = json; }
            @JavascriptInterface public void error(int code) { Log.i("CornerPlayer", "YouTube error " + code); }
        }, "TV");
        // Under the Settings card and menus, over the photos.
        root.addView(web, 1, new FrameLayout.LayoutParams(1, 1));
        place();
        web.loadDataWithBaseURL(BASE, asset("youtube_corner.html"), "text/html", "utf-8", null);
    }

    private void place() {
        if (web == null) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = Math.round(WIDTH_DP[prefs.getInt("size", 0) == 1 ? 1 : 0] * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, w * 9 / 16, GRAVITY[prefs.getInt("corner", 0) % GRAVITY.length]);
        int m = Math.round(24 * d);
        lp.setMargins(m, m, m, m);
        web.setLayoutParams(lp);
    }

    private void run(String js) {
        ui.post(() -> {
            if (web == null) return;
            if (!pageReady) { if (js.startsWith("corner.load")) pendingJs = js; return; }
            web.evaluateJavascript(js, null);
        });
    }

    private String asset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "<p style=color:white>Missing " + name + "</p>";
        }
    }
}
