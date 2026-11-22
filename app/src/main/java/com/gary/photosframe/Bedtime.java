package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * "Off when we go to bed" (asked 2026-11-21): each phone tells the TV when it's plugged in or unplugged
 * (a phone automation opens /phone?k=…&who=gary&charging=1 on CornerServer). When every phone that has
 * reported in the last week is charging, and it's night, the TV goes to sleep, but only while the photos
 * or the screensaver are showing, so a film is never cut off (it waits for the screensaver instead).
 */
final class Bedtime {
    private static final String TAG = "Bedtime";
    static final int NIGHT_FROM = 21, NIGHT_TO = 5;               // 9 pm to 5 am
    private static final long KNOWN_FOR_MS = 7L * 24 * 3600 * 1000; // a phone counts if heard from this week
    private static final long CHECK_MS = 60_000;
    private static final long GRACE_MS = 60_000;                  // a minute after the last phone, in case of a mistake

    /** True while the frame screen is in front or the screensaver runs (set by them). */
    static volatile boolean frameShowing, screensaverShowing;

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static boolean checking;
    private static long allChargingSince;

    /** A phone reports. Returns a short line for the phone. */
    static synchronized String report(Context ctx, String who, boolean charging) {
        String name = who.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (name.isEmpty()) return "Add who=yourname to the address.";
        prefs(ctx).edit().putString(name, (charging ? "1" : "0") + "|" + System.currentTimeMillis()).apply();
        Map<String, Boolean> phones = phones(ctx);
        Log.i(TAG, name + (charging ? " plugged in" : " unplugged") + "; phones: " + phones);
        if (allCharging(phones)) {
            if (allChargingSince == 0) allChargingSince = System.currentTimeMillis();
            startChecking(ctx.getApplicationContext());
            return "Got it. All phones are charging" + (night() ? ": the TV will turn off." : ", but it isn't night yet.");
        }
        allChargingSince = 0;
        int waiting = 0;
        for (boolean c : phones.values()) if (!c) waiting++;
        return "Got it. Waiting for " + waiting + " more phone" + (waiting == 1 ? "" : "s") + " to charge.";
    }

    /** Removes a phone (a renamed phone or a test name). */
    static synchronized String forget(Context ctx, String who) {
        String name = who.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        prefs(ctx).edit().remove(name).apply();
        return "Forgot " + name + ".";
    }

    /** {name → charging} for every phone heard from this week. */
    static synchronized Map<String, Boolean> phones(Context ctx) {
        Map<String, Boolean> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            String[] p = String.valueOf(e.getValue()).split("\\|");
            if (p.length < 2) continue;
            try { if (now - Long.parseLong(p[1]) > KNOWN_FOR_MS) continue; } catch (NumberFormatException x) { continue; }
            out.put(e.getKey(), "1".equals(p[0]));
        }
        return out;
    }

    private static boolean allCharging(Map<String, Boolean> phones) {
        if (phones.isEmpty()) return false;
        for (boolean c : phones.values()) if (!c) return false;
        return true;
    }

    static boolean night() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= NIGHT_FROM || h < NIGHT_TO;
    }

    private static void startChecking(Context app) {
        if (checking) return;
        checking = true;
        ui.post(new Runnable() {
            @Override public void run() {
                synchronized (Bedtime.class) {
                    if (!allCharging(phones(app))) { checking = false; Log.i(TAG, "someone unplugged: stop"); return; }
                    android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
                    if (pm != null && !pm.isInteractive()) { checking = false; Log.i(TAG, "TV already off: stop"); return; }
                    boolean ready = night() && System.currentTimeMillis() - allChargingSince >= GRACE_MS;
                    if (ready && (frameShowing || screensaverShowing)) {
                        boolean ok = ArtKeyService.sleepTv();
                        Log.i(TAG, "everyone's phone is charging at night → TV off (" + ok + ")");
                        checking = false;
                        return;
                    }
                    if (ready) Log.i(TAG, "bedtime, but something else is on screen: waiting for the photos or screensaver");
                }
                ui.postDelayed(this, CHECK_MS);
            }
        });
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("bedtime_phones", Context.MODE_PRIVATE);
    }
}
