package com.gary.photosframe;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's art button open Photos Frame instead of TCL's T-Exhibition.
 *
 * TCL's system (TclPhoneWindowManagerEx.handleKeyBeforeDispatching → TclShortCutKeyManager.startExhibit)
 * grabs that key before any app sees it. Accessibility key filtering runs earlier in Android's input
 * pipeline, so this service can take the key first. It looks only at that one key; every other key
 * is passed straight through, and it reads no screen content (see res/xml/art_key_service.xml).
 *
 * The user turns it on themselves: TV Settings → Accessibility → Photos Frame art button.
 */
public class ArtKeyService extends AccessibilityService {
    static final int TCL_ART_KEYCODE = 4181;   // measured on the A300W remote (scan code 507)
    static final int TCL_ART_SCANCODE = 507;

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != TCL_ART_KEYCODE && e.getScanCode() != TCL_ART_SCANCODE) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            android.util.Log.i("ArtKeyService", "art button → Photos Frame");
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
        return true;   // consume both down and up so T-Exhibition doesn't also start
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }
}
