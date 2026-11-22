package com.gary.photosframe;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's art button open Photos Frame instead of TCL's T-Exhibition.
 *
 * TCL's system (TclPhoneWindowManagerEx.handleKeyBeforeDispatching → TclShortCutKeyManager.startExhibit)
 * grabs that key before any app sees it. Accessibility key filtering runs earlier in Android's input
 * pipeline, so this service can take the key first. Every other key is passed straight through; it reads
 * no screen content (see res/xml/art_key_service.xml).
 *
 * Also (2026-11-21) "photos when we come home": Google Home wakes the TV when the first of the owners
 * arrives. When the TV wakes and no remote button was pressed just before, and nothing starts playing sound
 * (a phone casting YouTube or Spotify), this opens the frame. Only the time of the last button press is
 * kept, never which button.
 *
 * The user turns it on themselves: TV Settings → Accessibility → Photos Frame art button.
 */
public class ArtKeyService extends AccessibilityService {
    static final int TCL_ART_KEYCODE = 4181;   // measured on the A300W remote (scan code 507)
    static final int TCL_ART_SCANCODE = 507;
    private static final String TAG = "ArtKeyService";
    /** A button press this close before the wake means a person turned the TV on. */
    private static final long REMOTE_WINDOW_MS = 8_000;
    /** How long to wait after waking before deciding (a cast app needs a moment to start its sound). */
    private static final long SETTLE_MS = 6_000;

    /** The running helper, if the owner has it switched on (used to put the TV to sleep). */
    static volatile ArtKeyService running;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long lastKeyAt = -1;
    private long wokeAt;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_SCREEN_ON.equals(i.getAction())) return;
            wokeAt = SystemClock.uptimeMillis();
            long sinceKey = lastKeyAt < 0 ? -1 : wokeAt - lastKeyAt;
            Log.i(TAG, "screen on; last button " + (sinceKey < 0 ? "never" : sinceKey + " ms") + " before");
            final long thisWake = wokeAt;
            ui.postDelayed(() -> decide(thisWake), SETTLE_MS);
        }
    };

    private void decide(long wake) {
        if (wake != wokeAt) return;                                    // woke again since; that one decides
        boolean pressed = lastKeyAt >= 0 && lastKeyAt > wake - REMOTE_WINDOW_MS;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean sound = am != null && am.isMusicActive();
        if (pressed) { Log.i(TAG, "woken by the remote: leave the TV as it is"); return; }
        if (sound) { Log.i(TAG, "woken with sound playing (a cast?): leave it"); return; }
        Log.i(TAG, "woken with no remote and no sound (Google Home?) → Photos Frame");
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        lastKeyAt = SystemClock.uptimeMillis();   // only when, not which
        if (e.getKeyCode() != TCL_ART_KEYCODE && e.getScanCode() != TCL_ART_SCANCODE) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "art button → Photos Frame");
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
        return true;   // consume both down and up so T-Exhibition doesn't also start
    }

    /** Puts the TV into standby ("lock screen" on a TV). False if the helper is switched off. */
    static boolean sleepTv() {
        ArtKeyService s = running;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override protected void onServiceConnected() {
        running = this;
        registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_ON));
        CornerServer.ensureRunning(this);   // phones can report charging even when only the screensaver is up
    }

    @Override public boolean onUnbind(Intent i) {
        running = null;
        try { unregisterReceiver(screen); } catch (IllegalArgumentException ignored) { }
        return super.onUnbind(i);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }
}
