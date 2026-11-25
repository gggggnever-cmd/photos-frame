package com.gary.photosframe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * How bright the room is, from the TV's own light sensor (asked 2026-11-24: dim the photos when the room is dark,
 * like Samsung's Art Mode). The sensor isn't offered to apps (no Android light sensor, nothing in TCL's picture
 * library), but TCL's sensor service logs every reading once a second:
 *   "TclSensor: get lux from light sensor success, lux:12, reg:..."
 * so this follows the system log, like GoogleOff (READ_LOGS, granted once by adb). Without it, lux() is -1.
 */
final class RoomLight {
    private static final String TAG = "RoomLight";
    private static final int WINDOW = 30;            // readings kept (about 30 s), so a passing shadow doesn't count
    private static final int[] recent = new int[WINDOW];
    private static int count, next;
    private static long lastAt;
    private static boolean started;

    static synchronized void start(Context ctx) {
        if (started) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no READ_LOGS: room light unknown");
            return;
        }
        started = true;
        Thread t = new Thread(RoomLight::watch, "room-light");
        t.setDaemon(true);
        t.start();
    }

    private static void watch() {
        while (true) {
            Process p = null;
            try {
                p = new ProcessBuilder("logcat", "-T", "1", "-s", "TclSensor:*").redirectErrorStream(true).start();
                BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
                for (String line; (line = in.readLine()) != null; ) {
                    int at = line.indexOf("lux:");
                    if (at < 0) continue;
                    int end = at + 4;
                    while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
                    if (end > at + 4) add(Integer.parseInt(line.substring(at + 4, end)));
                }
            } catch (Exception e) {
                Log.w(TAG, "log watch stopped: " + e);
            } finally {
                if (p != null) p.destroy();
            }
            try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
        }
    }

    private static synchronized void add(int lux) {
        recent[next] = lux;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
        lastAt = SystemClock.uptimeMillis();
    }

    /** The room's light (median of the last ~30 s, in lux), or -1 if no reading in the last minute. */
    static synchronized int lux() {
        if (count == 0 || SystemClock.uptimeMillis() - lastAt > 60_000) return -1;
        int[] s = java.util.Arrays.copyOf(recent, count);
        java.util.Arrays.sort(s);
        return s[count / 2];
    }

    /**
     * Auto brightness for the photos, on the Settings scale (-70 … 100): darker room, dimmer photos. A first guess,
     * tuned at night only (the kitchen read 0 lux with the lights low); see CLAUDE.md before changing it.
     */
    static int autoBrightness(int lux) {
        if (lux < 0) return 10;          // no reading: the old default
        if (lux <= 1) return -40;
        if (lux <= 5) return -20;
        if (lux <= 20) return 0;
        if (lux <= 60) return 15;
        if (lux <= 150) return 30;
        if (lux <= 400) return 50;
        return 70;
    }
}
