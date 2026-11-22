package com.gary.photosframe;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * "TV off if remote unused" (Settings, asked 2026-11-22): once the photos or the screensaver have been on
 * for the chosen time with no remote button pressed, the TV goes to standby (Google Home can wake it again;
 * its "off" didn't work on this TV). Never while something else is on screen (a show), and never while sound
 * plays (Spotify in the background, the YouTube corner): that counts as someone enjoying it.
 * Runs in the always-on art-button helper's process.
 */
final class AutoOff {
    private static final String TAG = "AutoOff";
    private static final long CHECK_MS = 30_000;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static boolean started;
    private static long soundAt = -1;   // last time sound was heard (uptime ms)

    static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        Context app = ctx.getApplicationContext();
        ui.post(new Runnable() {
            @Override public void run() {
                try { check(app); } catch (Exception e) { Log.w(TAG, "check failed: " + e); }
                ui.postDelayed(this, CHECK_MS);
            }
        });
    }

    private static void check(Context app) {
        long limit = new Settings(app).autoOffMs();
        if (limit <= 0) return;
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) return;                                  // already off
        if (!(Bedtime.frameShowing || Bedtime.screensaverShowing)) return;               // a show, not the photos
        long now = SystemClock.uptimeMillis();
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) soundAt = now;
        long last = Math.max(Math.max(ArtKeyService.lastKeyAt(), Bedtime.showingSince), soundAt);
        if (now - last < limit) return;
        boolean ok = ArtKeyService.sleepTv();
        Log.i(TAG, "no remote for " + (now - last) / 60_000 + " min with the photos showing → TV off (" + ok + ")");
        if (ok) soundAt = -1;
    }
}
