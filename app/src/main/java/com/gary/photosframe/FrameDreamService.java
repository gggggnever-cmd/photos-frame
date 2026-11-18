package com.gary.photosframe;

import android.service.dreams.DreamService;

/** Lets Photos Frame be chosen as the TV's screensaver. */
public class FrameDreamService extends DreamService {
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any button press wakes the TV back to normal
        setFullscreen(true);
        setScreenBright(true);
        setContentView(new FrameView(this));
    }
}
