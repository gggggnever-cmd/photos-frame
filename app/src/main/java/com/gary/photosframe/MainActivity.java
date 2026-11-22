package com.gary.photosframe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements SettingsPanel.Host, PhotoMenu.Host, TripPanel.Host {
    private static final int REQ_ADD = 1;
    private FrameView frame;
    private TextView storageNote, levelNote;
    private FrameLayout root;
    private long reminderUntil;   // while the art-button reminder shows, a short OK opens the setting
    // Photos still downloading in the background: a small corner line, and the frame takes in new photos.
    private TextView queueNote;
    // YouTube corner: the little player, the phone's remote page, and the card with its QR code.
    private CornerPlayer corner;
    private CornerServer cornerServer;
    private android.widget.LinearLayout cornerCard;
    private int seenAdded;
    private long lastRescan;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable queueTick = new Runnable() {
        @Override public void run() {
            String st = DownloadQueue.status();
            queueNote.setText(st == null ? "" : "Adding photos: " + st);
            queueNote.setVisibility(st == null ? View.GONE : View.VISIBLE);
            int added = DownloadQueue.addedCount();
            long now = android.os.SystemClock.uptimeMillis();
            // Rescan now and then (each rescan reads the new photos' dates), and once at the end.
            if (added != seenAdded && (st == null || now - lastRescan > 3 * 60 * 1000)) {
                seenAdded = added;
                lastRescan = now;
                frame.reloadQuietly();
            }
            ui.postDelayed(this, 5000);
        }
    };
    private SettingsPanel panel;
    private PhotoMenu menu;
    private TripPanel tripPanel;
    private android.widget.LinearLayout renameBox;
    private android.widget.EditText renameField;
    private TextView renameTitle;
    private String[] renaming;                 // {start, end} of the trip being renamed, or null
    private boolean renameFromTrips;
    private boolean longPressed;   // OK was held: the menu opened, so its release must not skip

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
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
        queueNote = new TextView(this);
        queueNote.setTextColor(0x99F1EDE4);
        queueNote.setTextSize(13);
        queueNote.setShadowLayer(6, 0, 0, 0xFF000000);
        queueNote.setVisibility(View.GONE);
        FrameLayout.LayoutParams qn = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        qn.bottomMargin = 16;
        qn.rightMargin = 24;
        root.addView(queueNote, qn);
        DownloadQueue.resume(this);   // carries on with picks still waiting from before a restart
        seenAdded = DownloadQueue.addedCount();
        lastRescan = android.os.SystemClock.uptimeMillis();
        panel = new SettingsPanel(this, new Settings(this), this);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(panel.cardWidth(), -2, Gravity.CENTER));
        menu = new PhotoMenu(this, this);
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(menu.cardWidth(), -2, Gravity.CENTER));
        tripPanel = new TripPanel(this, this);
        tripPanel.setVisibility(View.GONE);
        root.addView(tripPanel, new FrameLayout.LayoutParams(tripPanel.cardWidth(), -2, Gravity.CENTER));
        buildRenameBox(root);
        corner = new CornerPlayer(this, root);
        cornerServer = new CornerServer(this, new CornerServer.Commands() {
            @Override public String command(String c, String arg) { return cornerCommand(c, arg); }
            @Override public String state() { return corner.state(); }
        });
        cornerServer.start();
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() {
        super.onResume();
        // adb test: am start -n com.gary.photosframe/.MainActivity --ez test_art_reminder true
        boolean test = getIntent().getBooleanExtra("test_art_reminder", false);
        getIntent().removeExtra("test_art_reminder");
        if (ArtKeyReminder.due(this, test)) {
            ArtKeyReminder.show(this, root);
            reminderUntil = android.os.SystemClock.uptimeMillis() + ArtKeyReminder.SHOW_MS;
        }
        ui.removeCallbacks(queueTick);
        ui.post(queueTick);
        corner.onResume();
    }

    @Override protected void onDestroy() {
        cornerServer.stop();
        corner.destroy();
        super.onDestroy();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(queueTick);
        corner.onPause();
        super.onPause();
    }

    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (renaming != null) return super.onKeyDown(code, e);                   // the text box handles keys
        if (cornerCard != null) {   // the YouTube corner code: OK or Back closes it
            if (ok || code == KeyEvent.KEYCODE_BACK) { closeCornerCard(); return true; }
            return true;
        }
        if (tripPanel.isOpen()) {
            if (ok) { if (e.getRepeatCount() == 0) { e.startTracking(); longPressed = false; } return true; }
            return tripPanel.onKey(code, false);
        }
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
            if (tripPanel.isOpen()) tripPanel.onKey(code, true);
            else if (!panel.isOpen() && renaming == null) menu.open(frame.currentPhotos());
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (tripPanel.isOpen()) {
                if (!longPressed && e.isTracking() && !e.isCanceled()) tripPanel.onKey(code, false);
                longPressed = false;
                return true;
            }
            if (renaming != null) return super.onKeyUp(code, e);
            if (cornerCard != null) return true;
            if (!longPressed && !menu.isOpen() && !panel.isOpen() && e.isTracking() && !e.isCanceled()) {
                if (android.os.SystemClock.uptimeMillis() < reminderUntil) { reminderUntil = 0; ArtKeyReminder.openSettings(this); }
                else frame.next();
            }
            longPressed = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override public void onBackPressed() {
        if (renaming != null) { closeRename(); return; }
        if (cornerCard != null) { closeCornerCard(); return; }
        if (tripPanel.isOpen()) { tripPanel.onKey(KeyEvent.KEYCODE_BACK, false); return; }
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
        frame.hide(id, () -> note("Photo hidden. To bring it back: Settings → Hidden photos"));
    }
    @Override public Boolean isPlace(String id) { return frame.isPlace(id); }
    @Override public void togglePlace(String id) {
        frame.togglePlace(id, now -> note(now ? "Added to Places only" : "Taken out of Places only"));
    }
    @Override public int placeCount() { return frame.placeCount(); }
    @Override public int[] checkProgress() { return frame.checkProgress(); }
    @Override public void highlight(int index) { frame.setHighlight(index); }
    @Override public void closeMenu() { menu.close(); }

    // SettingsPanel.Host (counts)
    @Override public int hiddenCount() { return frame.hiddenCount(); }
    @Override public int favouriteCount() { return frame.favouriteCount(); }
    @Override public java.util.List<String[]> days() { return frame.days(); }

    // Trips
    @Override public java.util.List<String[]> tripRows() { return frame.tripRows(); }
    @Override public void openTrips() { panel.setVisibility(View.GONE); tripPanel.open(new Settings(this).trips()); }
    @Override public void tripsChosen(String chosen) {
        new Settings(this).setTrips(chosen);
        frame.applySettings();
    }
    @Override public void closeTrips() { tripPanel.setVisibility(View.GONE); panel.update(); panel.setVisibility(View.VISIBLE); }
    @Override public void renameTrip(String start, String end, String label) { openRename(start, end, label, true); }
    @Override public String tripLabel(String id) {
        String[] t = frame.tripOfPhoto(id);
        return t == null ? null : t[2];
    }
    @Override public void renameTripOf(String id) {
        String[] t = frame.tripOfPhoto(id);
        if (t == null) { note("This photo has no date, so it isn't in a trip"); return; }
        openRename(t[0], t[1], t[2], false);
    }

    /** A small box with the TV keyboard (which has a mic) to name a trip. Empty = back to the date name. */
    private void buildRenameBox(FrameLayout root) {
        renameBox = new android.widget.LinearLayout(this);
        renameBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(28 * getResources().getDisplayMetrics().density);
        renameBox.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF21E1C1A);
        bg.setCornerRadius(pad / 2f);
        renameBox.setBackground(bg);
        renameTitle = new TextView(this);
        renameTitle.setTextColor(0xFFF1EDE4);
        renameTitle.setTextSize(22);
        renameTitle.setTypeface(android.graphics.Typeface.SERIF);
        renameBox.addView(renameTitle);
        renameField = new android.widget.EditText(this);
        renameField.setSingleLine(true);
        renameField.setTextSize(22);
        renameField.setTextColor(0xFF2E2B28);       // the TV draws the text box light
        renameField.setHintTextColor(0x992E2B28);
        renameField.setHint("Say or type a name, e.g. Egypt");
        renameField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        renameField.setOnEditorActionListener((v, a, ev) -> { saveRename(); return true; });
        renameBox.addView(renameField);
        TextView hint = new TextView(this);
        hint.setTextColor(0xB3F1EDE4);
        hint.setTextSize(14);
        hint.setText("OK to save  ·  leave empty to use the dates instead  ·  Back to cancel");
        renameBox.addView(hint);
        renameBox.setVisibility(View.GONE);
        root.addView(renameBox, new FrameLayout.LayoutParams(Math.round(560 * getResources().getDisplayMetrics().density), -2, Gravity.CENTER));
    }

    private void openRename(String start, String end, String label, boolean fromTrips) {
        renaming = new String[]{start, end};
        renameFromTrips = fromTrips;
        tripPanel.setVisibility(View.GONE);
        renameTitle.setText("Name this trip: " + label.replace("  ·  ", " · "));
        renameField.setText("");
        renameBox.setVisibility(View.VISIBLE);
        renameField.requestFocus();
        renameField.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(renameField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 300);
    }

    private void saveRename() {
        if (renaming == null) return;
        String[] r = renaming;
        String name = renameField.getText().toString().trim();
        boolean back = renameFromTrips;
        closeRename();
        frame.nameTrip(r[0], r[1], name, () -> {
            note(name.isEmpty() ? "Trip name removed; it shows its dates again" : "Trip named \"" + name + "\"");
            if (back) { tripPanel.reload(); tripPanel.setVisibility(View.VISIBLE); }
        });
    }

    private void closeRename() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(renameField.getWindowToken(), 0);
        renameBox.setVisibility(View.GONE);
        boolean back = renameFromTrips;
        renaming = null;
        if (back) tripPanel.setVisibility(View.VISIBLE);
        frame.requestFocus();
    }
    @Override public void unhideAll() { frame.unhideAll(); note("Hidden photos will show again"); }

    // YouTube corner
    @Override public void youtubeCorner() {
        close();
        String url = CornerServer.address(this);
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(28 * getResources().getDisplayMetrics().density);
        card.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF21E1C1A);
        bg.setCornerRadius(pad / 2f);
        card.setBackground(bg);
        if (url != null) {
            android.widget.ImageView qr = new android.widget.ImageView(this);
            qr.setImageBitmap(Qr.make(url, 360, 0xFF2E2B28, 0xFFF1EDE4));
            int q = Math.round(200 * getResources().getDisplayMetrics().density);
            android.widget.LinearLayout.LayoutParams qp = new android.widget.LinearLayout.LayoutParams(q, q);
            qp.rightMargin = pad;
            card.addView(qr, qp);
        }
        TextView t = new TextView(this);
        t.setTextColor(0xFFF1EDE4);
        t.setTextSize(17);
        t.setLineSpacing(0, 1.3f);
        t.setText(url == null ? "YouTube corner\n\nThe TV isn't connected to your Wi-Fi."
                : "YouTube corner\n\nScan with your phone (on the home Wi-Fi). On that page, paste a YouTube link "
                + "and tap Play on TV: it plays in a corner while the photos carry on.\n"
                + "Tip: in Chrome, ⋮ → Add to Home screen, so you don't need this code again.\n\n"
                + "Not signed in to YouTube: public and unlisted videos and playlists play, with ads.\n\nOK or Back to close");
        card.addView(t, new android.widget.LinearLayout.LayoutParams(Math.round(380 * getResources().getDisplayMetrics().density), -2));
        root.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        cornerCard = card;
    }

    private void closeCornerCard() {
        if (cornerCard == null) return;
        root.removeView(cornerCard);
        cornerCard = null;
    }

    /** A command from the phone page (on the server's thread). Returns null if fine, else a message. */
    private String cornerCommand(String c, String arg) {
        if (c.equals("play")) {
            YouTubeLink link = YouTubeLink.parse(arg);
            if (link == null) return "That doesn't look like a YouTube link. In YouTube tap Share → Copy link, then paste it here.";
            corner.markShowing();
            runOnUiThread(() -> { closeCornerCard(); corner.play(link); });
            return null;
        }
        if (!corner.showing() && !c.equals("close")) return "Nothing is playing. Paste a link and tap Play on TV.";
        runOnUiThread(() -> {
            switch (c) {
                case "toggle": corner.toggle(); break;
                case "next": corner.next(); break;
                case "prev": corner.prev(); break;
                case "volup": corner.volume(+10); break;
                case "voldown": corner.volume(-10); break;
                case "move": corner.move(); break;
                case "size": corner.bigger(!isBig()); break;
                case "close": corner.close(); break;
            }
        });
        return null;
    }

    private boolean isBig() { return getSharedPreferences("corner", MODE_PRIVATE).getInt("size", 0) == 1; }

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
        // Photos picked just now download in the background; queueTick brings them in as they arrive.
        if (req == REQ_ADD && DownloadQueue.addedCount() != seenAdded) {
            seenAdded = DownloadQueue.addedCount();
            lastRescan = android.os.SystemClock.uptimeMillis();
            frame.reload();
        }
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
