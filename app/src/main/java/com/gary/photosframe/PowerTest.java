package com.gary.photosframe;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Experiment (2026-11-21): can the app put the TV to sleep and wake it again by itself? That decides how
 * "turn on when we're nearby" can work. Driven by adb only:
 *   am start --activity-single-top -n com.gary.photosframe/.MainActivity --es test_power sleep
 *   am start --activity-single-top -n com.gary.photosframe/.MainActivity --ei test_wake_in 60
 * Results are in logcat under "PowerTest".
 */
final class PowerTest {
    private static final String TAG = "PowerTest";

    /** Asks the art-button helper to "lock the screen", which on a TV normally means standby. */
    static boolean sleep() {
        boolean ok = ArtKeyService.sleepTv();
        Log.i(TAG, "sleep: lock-screen action returned " + ok);
        return ok;
    }

    /** Sets an alarm that tries to wake the TV and bring the frame up in `seconds`. */
    static void wakeIn(Context ctx, int seconds) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, new Intent(ctx, Wake.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        long at = SystemClock.elapsedRealtime() + seconds * 1000L;
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        Log.i(TAG, "wake alarm set for " + seconds + " s (exact=" + exact + ")");
    }

    public static final class Wake extends BroadcastReceiver {
        @SuppressWarnings("deprecation")
        @Override public void onReceive(Context ctx, Intent intent) {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            Log.i(TAG, "alarm fired; screen on before = " + pm.isInteractive());
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "photosframe:wake");
            wl.acquire(10_000);
            try {
                ctx.startActivity(new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception e) {
                Log.i(TAG, "couldn't open the frame: " + e);
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                    Log.i(TAG, "3 s later: screen on = " + pm.isInteractive()), 3000);
        }
    }
}
