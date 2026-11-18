package com.gary.photosframe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements SettingsPanel.Host, PhotoMenu.Host {
    private static final int REQ_ADD = 1;
    private FrameView frame;
    private TextView storageNote, levelNote;
    private SettingsPanel panel;
    private PhotoMenu menu;
    private boolean longPressed;   // OK was held: the menu opened, so its release must not skip

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
        menu = new PhotoMenu(this, this);
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(menu.cardWidth(), -2, Gravity.CENTER));
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (menu.isOpen()) return e.getRepeatCount() > 0 || menu.onKey(code);   // ignore the held OK's repeats
        if (panel.isOpen()) return panel.onKey(code);
        if (ok) {
            // OK: a short press is "next photo" (on release); holding it opens the photo menu.
            if (e.getRepeatCount() == 0) { e.startTracking(); longPressed = false; }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                frame.next();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                frame.back(() -> note("This is the first photo"));
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

    @Override public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            longPressed = true;
            menu.open(frame.currentPhotos());
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (!longPressed && !menu.isOpen() && !panel.isOpen() && e.isTracking() && !e.isCanceled()) frame.next();
            longPressed = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override public void onBackPressed() {
        if (menu.isOpen()) closeMenu();
        else if (panel.isOpen()) close();
        else super.onBackPressed();
    }

    // PhotoMenu.Host
    @Override public boolean isFavourite(String id) { return frame.isFavourite(id); }
    @Override public void favourite(String id) {
        frame.toggleFavourite(id, now -> note(now ? "♥ Added to favourites" : "Removed from favourites"));
    }
    @Override public void hide(String id) {
        frame.hide(id, () -> note("Hidden. Settings → Hidden photos brings it back"));
    }
    @Override public void highlight(int index) { frame.setHighlight(index); }
    @Override public void closeMenu() { menu.close(); }

    // SettingsPanel.Host (counts)
    @Override public int hiddenCount() { return frame.hiddenCount(); }
    @Override public int favouriteCount() { return frame.favouriteCount(); }
    @Override public java.util.List<String[]> days() { return frame.days(); }
    @Override public void unhideAll() { frame.unhideAll(); note("Hidden photos will show again"); }

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
