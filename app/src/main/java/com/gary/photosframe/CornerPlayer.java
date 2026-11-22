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
    // 16:9 widths on the 960 dp wide TV screen; Large and Full screen added 2026-11-22 (0 = the whole screen).
    private static final int[] WIDTH_DP = {352, 512, 720, 0};
    static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Full screen"};
    private static final double[] ZOOM = {1, 1.2, 1.4};   // "Trim black edges": off, some, more
    // "Blend" (2026-11-22): how see-through the whole video is, so it merges with the photo behind it.
    static final double[] BLEND = {1, 0.8, 0.6, 0.45};
    static final String[] BLEND_NAMES = {"Off", "Light", "Medium", "Strong"};   // 16:9 small / large on the 960 dp wide TV screen

    private final Context ctx;
    private final FrameLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private WebView web;
    private boolean pageReady;
    private String pendingJs;
    private volatile String state = "{}";
    private volatile boolean showing, wasPlaying;
    private volatile boolean hidden;   // "hold ▼": still loaded, but paused and out of sight
    private YouTubeLink last;           // so hold ▼ can bring back the last song after it was closed

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
        hidden = false;
        last = link;
        state = "{}";
        run("corner.load(" + JSONObject.quote(link.video == null ? "" : link.video) + ","
                + JSONObject.quote(link.list == null ? "" : link.list) + ")");
    }

    void toggle() { if (hidden) { unhide(); return; } run("corner.toggle()"); }

    /**
     * The remote's "hold ▼" (asked 2026-11-22): Small → Medium → Large → Full screen → hidden and paused →
     * Small and playing again. After Close it brings the last song back. Returns a short note for the screen,
     * or null if nothing has been played yet. Call on the UI thread.
     */
    String step() {
        if (!showing) {
            if (last == null) return null;
            prefs.edit().putInt("size", 0).apply();
            play(last);
            place();
            return "YouTube: " + SIZE_NAMES[0];
        }
        if (hidden) {
            prefs.edit().putInt("size", 0).apply();
            place();
            unhide();
            return "YouTube: " + SIZE_NAMES[0];
        }
        if (size() < WIDTH_DP.length - 1) {
            nextSize();
            return "YouTube: " + SIZE_NAMES[size()];
        }
        hidden = true;
        run("corner.pause()");
        if (web != null) web.setVisibility(View.INVISIBLE);
        return "YouTube paused and hidden. Hold ▼ to bring it back.";
    }

    private void unhide() {
        hidden = false;
        if (web != null) web.setVisibility(View.VISIBLE);
        run("corner.play()");
    }
    void next() { run("corner.next()"); }
    void prev() { run("corner.prev()"); }
    void volume(int delta) {
        int v = Math.max(0, Math.min(100, prefs.getInt("volume", 60) + delta));
        prefs.edit().putInt("volume", v).apply();
        run("corner.volume(" + v + ")");
    }

    /** Crops in a step (for videos whose picture has black bars of its own), then back to none. */
    void zoom() {
        int z = (prefs.getInt("zoom", 0) + 1) % ZOOM.length;
        prefs.edit().putInt("zoom", z).apply();
        run("corner.zoom(" + ZOOM[z] + ")");
    }

    /** The next see-through level, then back to solid. */
    void blend() {
        int b = (prefs.getInt("blend", 0) + 1) % BLEND.length;
        prefs.edit().putInt("blend", b).apply();
        run("corner.blend(" + BLEND[b] + ")");
    }

    /** Next corner, clockwise from bottom-right. */
    void move() {
        prefs.edit().putInt("corner", (prefs.getInt("corner", 0) + 1) % GRAVITY.length).apply();
        place();
    }

    /** Next size: Small → Medium → Large → Full screen → Small. */
    void nextSize() {
        prefs.edit().putInt("size", (size() + 1) % WIDTH_DP.length).apply();
        place();
        if (hidden) unhide();
    }
    private int size() { int s = prefs.getInt("size", 0); return s >= 0 && s < WIDTH_DP.length ? s : 0; }

    /** Stops and hides the window (it comes back with the next link). */
    void close() {
        showing = false;
        hidden = false;
        state = "{}";
        if (web != null) { run("corner.stop()"); web.setVisibility(View.GONE); }
    }

    /** The frame went to the background: pause; it carries on when the frame comes back. */
    void onPause() {
        if (web == null || !showing) return;
        try { wasPlaying = new JSONObject(state).optInt("state", -1) == 1; } catch (Exception e) { wasPlaying = false; }
        run("corner.pause()");
    }

    void onResume() { if (web != null && showing && !hidden && wasPlaying) run("corner.play()"); }

    void destroy() { if (web != null) { web.destroy(); web = null; } }

    /** What's playing, for the phone page: {title, author, state, index, count, volume, showing, corner, big}. */
    String state() {
        try {
            JSONObject j = new JSONObject(state);
            j.put("showing", showing);
            j.put("volume", prefs.getInt("volume", 60));
            j.put("size", size());
            j.put("hidden", hidden);
            j.put("zoom", prefs.getInt("zoom", 0) % ZOOM.length);
            j.put("blend", prefs.getInt("blend", 0) % BLEND.length);
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
        web.setBackgroundColor(Color.TRANSPARENT);   // the page fades its edges into the photo
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                run("corner.volume(" + prefs.getInt("volume", 60) + ")");
                run("corner.zoom(" + ZOOM[prefs.getInt("zoom", 0) % ZOOM.length] + ")");
                run("corner.blend(" + BLEND[prefs.getInt("blend", 0) % BLEND.length] + ")");
                if (pendingJs != null) { String js = pendingJs; pendingJs = null; run(js); }
            }
        });
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void state(String json) { state = json; }
            @JavascriptInterface public void error(int code) { Log.i("CornerPlayer", "YouTube error " + code); }
            @JavascriptInterface public void shape(String id) { lookUpShape(id); }
        }, "TV");
        // Under the Settings card and menus, over the photos.
        root.addView(web, 1, new FrameLayout.LayoutParams(1, 1));
        place();
        web.loadDataWithBaseURL(BASE, asset("youtube_corner.html"), "text/html", "utf-8", null);
    }

    /** The video's own width/height from YouTube's oEmbed, so the window can fill without black bars. */
    private void lookUpShape(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        "https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=" + id).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                    JSONObject j = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                    double w = j.optDouble("width", 0), h = j.optDouble("height", 0);
                    if (w > 0 && h > 0) {
                        Log.i("CornerPlayer", "video shape " + (int) w + "x" + (int) h);
                        run("corner.aspect(" + JSONObject.quote(id) + "," + (w / h) + ")");
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                Log.i("CornerPlayer", "couldn't get the video's shape: " + e);
            }
        }, "corner-shape").start();
    }

    private void place() {
        if (web == null) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        int wdp = WIDTH_DP[size()];
        FrameLayout.LayoutParams lp;
        if (wdp == 0) {   // Full screen: over the whole photo (the soft edges and Blend still apply)
            lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            int w = Math.round(wdp * d);
            lp = new FrameLayout.LayoutParams(w, w * 9 / 16, GRAVITY[prefs.getInt("corner", 0) % GRAVITY.length]);
            int m = Math.round(24 * d);
            lp.setMargins(m, m, m, m);
        }
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
