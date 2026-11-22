package com.gary.photosframe;

import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/** Lets Photos Frame be chosen as the TV's screensaver. */
public class FrameDreamService extends DreamService {
    private long reminderUntil;   // while the art-button reminder shows, OK opens the setting

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any button press wakes the TV back to normal
        setFullscreen(true);
        setScreenBright(true);
        FrameLayout root = new FrameLayout(this);
        root.addView(new FrameView(this));
        setContentView(root);
        if (ArtKeyReminder.due(this, false)) {
            ArtKeyReminder.show(this, root);
            reminderUntil = SystemClock.uptimeMillis() + ArtKeyReminder.SHOW_MS;
            setInteractive(true);   // only so OK can be caught; every other button still ends the screensaver
            root.postDelayed(() -> setInteractive(false), ArtKeyReminder.SHOW_MS);
        }
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); Bedtime.screensaverShowing = true; }
    @Override public void onDreamingStopped() { Bedtime.screensaverShowing = false; super.onDreamingStopped(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (!isInteractive()) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (ok && SystemClock.uptimeMillis() < reminderUntil) ArtKeyReminder.openSettings(this);
            wakeUp();
        }
        return true;
    }
}
