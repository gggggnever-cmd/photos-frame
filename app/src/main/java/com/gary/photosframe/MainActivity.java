package com.gary.photosframe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements SettingsPanel.Host {
    private static final int REQ_ADD = 1;
    private FrameView frame;
    private TextView storageNote, levelNote;
    private SettingsPanel panel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        frame = new FrameView(this);
        root.addView(frame);
        if (!frame.hasOwnPhotos()) {
            // Quiet one-time hint while only the placeholder samples are showing.
            TextView hint = new TextView(this);
            hint.setText("Press ▼ on the remote to add your photos  ·  ▲ for settings");
            hint.setTextColor(0x99F1EDE4);
            hint.setTextSize(16);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = 24;
            root.addView(hint, lp);
            hint.animate().alpha(0f).setStartDelay(10000).setDuration(2000);
        }
        storageNote = new TextView(this);
        storageNote.setTextColor(0xCCE8C9A0);
        storageNote.setTextSize(15);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = 20;
        root.addView(storageNote, sp);
        showStorageWarning();
        levelNote = new TextView(this);
        levelNote.setTextColor(0xFFF1EDE4);
        levelNote.setTextSize(20);
        levelNote.setShadowLayer(8, 0, 0, 0xFF000000);
        levelNote.setAlpha(0f);
        FrameLayout.LayoutParams lv = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lv.bottomMargin = 60;
        root.addView(levelNote, lv);
        panel = new SettingsPanel(this, new Settings(this), this);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(panel.cardWidth(), -2, Gravity.CENTER));
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (panel.isOpen()) return panel.onKey(code);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                frame.next();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!frame.back()) note("This is the first photo");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                panel.open();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MENU:
                addPhotos();
                return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public void onBackPressed() {
        if (panel.isOpen()) close(); else super.onBackPressed();
    }

    // SettingsPanel.Host
    @Override public void settingsChanged() { frame.applySettings(); }
    @Override public void addPhotos() { close(); startActivityForResult(new Intent(this, AddPhotosActivity.class), REQ_ADD); }
    @Override public void close() { panel.setVisibility(View.GONE); }

    /** A short message near the bottom that fades away. */
    private void note(String s) {
        levelNote.animate().cancel();
        levelNote.setText(s);
        levelNote.setAlpha(1f);
        levelNote.animate().alpha(0f).setStartDelay(2500).setDuration(800);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_ADD && res == RESULT_OK) frame.reload();
        showStorageWarning();
    }

    /** Low/full storage: say so for 30 seconds, then let the picture be. Rotation is never affected. */
    private void showStorageWarning() {
        String w = Storage.warning(this);
        storageNote.animate().cancel();
        storageNote.setText(w == null ? "" : w);
        storageNote.setAlpha(w == null ? 0f : 1f);
        if (w != null) storageNote.animate().alpha(0f).setStartDelay(30000).setDuration(3000);
    }
}
