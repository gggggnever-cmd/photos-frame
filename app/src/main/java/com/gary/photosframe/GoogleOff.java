package com.gary.photosframe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * "Hey Google, turn off the TV" (asked 2026-11-22). Google's command reaches the TV, but on this TCL the Cast
 * service can't put the TV in standby: it logs "Cloud.Action.CastReceiver.Display.TurnOff.In", then
 * "TurnStandby result: 0" and reports CAST::EDISPLAY_ATV to Google. This watches the system log for that
 * command and puts the TV in standby itself, the same standby as the remote's power button and Alexa.
 *
 * Needs READ_LOGS, which only adb can grant (once; it survives updates):
 *   adb shell pm grant com.gary.photosframe android.permission.READ_LOGS
 * Without it this does nothing. Runs in the always-on art-button helper's process.
 */
final class GoogleOff {
    private static final String TAG = "GoogleOff";
    private static final String COMMAND = "Cloud.Action.CastReceiver.Display.TurnOff.In";
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static boolean started;
    private static long lastOffAt;

    static synchronized void start(Context ctx) {
        if (started) return;
        Context app = ctx.getApplicationContext();
        if (app.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no READ_LOGS permission: Google Home's off isn't handled (see GoogleOff)");
            return;
        }
        started = true;
        Thread t = new Thread(() -> watch(app), "google-off");
        t.setDaemon(true);
        t.start();
    }

    private static void watch(Context app) {
        while (true) {
            Process p = null;
            try {
                // Only cast_shell's lines, from now on ("-T 1" replays one old line, which is skipped).
                p = new ProcessBuilder("logcat", "-T", "1", "-s", "cast_shell:I").redirectErrorStream(true).start();
                BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
                boolean first = true;
                for (String line; (line = in.readLine()) != null; ) {
                    if (first) { first = false; continue; }
                    if (line.contains(COMMAND)) ui.post(() -> turnOff(app));
                }
            } catch (Exception e) {
                Log.w(TAG, "log watch stopped: " + e);
            } finally {
                if (p != null) p.destroy();
            }
            try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }   // logcat ended: start again
        }
    }

    private static void turnOff(Context app) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastOffAt < 5_000) return;   // Google often sends the command twice
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;   // already off
        lastOffAt = now;
        boolean ok = ArtKeyService.sleepTv();
        Log.i(TAG, "Google Home asked to turn the TV off → standby (" + ok + ")");
    }
}
