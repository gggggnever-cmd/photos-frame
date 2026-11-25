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
    private CornerServer.Commands cornerCommands;
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
    private TextView renameTitle, renameHint;
    private String renaming;                   // key of the trip or album being renamed ("start~end" or "album:<id>"), or null
    private boolean renameFromTrips;
    private boolean longPressed;   // OK was held: the menu opened, so its release must not skip
    private boolean sideHeld;      // ◀ or ▶ was held: the YouTube song changed, so its release must not change the photo
    private boolean downHeld;      // ▼ was held: Add photos opened, so its release must not step the YouTube corner

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);   // when something wakes the TV to show the frame, come up over standby
        setTurnScreenOn(true);
        root = new FrameLayout(this);
        frame = new FrameView(this);
        root.addView(frame);
        if (!frame.hasOwnPhotos()) {
            // Quiet one-time hint while only the placeholder samples are showing.
            TextView hint = Ui.text(this, "Press ▼ on the remote to add your photos  ·  ▲ for settings", 16, Ui.SOFT);
            Ui.pill(hint);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = Ui.dp(this, 28);
            root.addView(hint, lp);
            hint.animate().alpha(0f).setStartDelay(10000).setDuration(2000);
        }
        // The short messages over the photos are small glass pills (see Ui), so they read on any picture.
        storageNote = Ui.text(this, "", 15, Ui.ACCENT);
        Ui.pill(storageNote);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = Ui.dp(this, 24);
        root.addView(storageNote, sp);
        showStorageWarning();
        levelNote = Ui.text(this, "", 20, Ui.INK);
        Ui.pill(levelNote);
        levelNote.setAlpha(0f);
        FrameLayout.LayoutParams lv = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lv.bottomMargin = Ui.dp(this, 84);   // above the first-run hint and the art-button reminder
        root.addView(levelNote, lv);
        queueNote = Ui.text(this, "", 14, Ui.SOFT);
        Ui.pill(queueNote);
        queueNote.setVisibility(View.GONE);
        FrameLayout.LayoutParams qn = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        qn.bottomMargin = Ui.dp(this, 20);
        qn.rightMargin = Ui.dp(this, 24);
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
        cornerCommands = new CornerServer.Commands() {
            @Override public String command(String c, String arg) { return cornerCommand(c, arg); }
            @Override public String state() { return corner.state(); }
        };
        CornerServer.attach(this, cornerCommands);
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() {
        super.onResume();
        // adb test: am start -n com.gary.photosframe/.MainActivity --ez test_art_reminder true
        boolean test = getIntent().getBooleanExtra("test_art_reminder", false);
        getIntent().removeExtra("test_art_reminder");
        // adb experiments (see PowerTest): put the TV to sleep, or set a wake-up alarm.
        int wakeIn = getIntent().getIntExtra("test_wake_in", 0);
        String power = getIntent().getStringExtra("test_power");
        getIntent().removeExtra("test_wake_in");
        getIntent().removeExtra("test_power");
        if (wakeIn > 0) PowerTest.wakeIn(this, wakeIn);
        if ("sleep".equals(power)) ui.postDelayed(PowerTest::sleep, 1500);
        importAlbumForTest();
        // adb test: --es test_tvpic dump | <Item>=<value> (executed, not saved; see TvPicture)
        String tvpic = getIntent().getStringExtra("test_tvpic");
        getIntent().removeExtra("test_tvpic");
        if ("dump".equals(tvpic)) TvPicture.dump(this);
        else if (tvpic != null && tvpic.startsWith("tvmode=")) TvPicture.testTvMode(this, Integer.parseInt(tvpic.substring(7).trim()));
        else if (tvpic != null && tvpic.contains("=")) {
            String[] kv = tvpic.split("=");
            TvPicture.testSet(kv[0], Integer.parseInt(kv[1].trim()));
        }
        if (ArtKeyReminder.due(this, test)) {
            ArtKeyReminder.show(this, root);
            reminderUntil = android.os.SystemClock.uptimeMillis() + ArtKeyReminder.SHOW_MS;
        }
        ui.removeCallbacks(queueTick);
        ui.post(queueTick);
        corner.onResume();
        TvPicture.frameOn(this);
        Bedtime.showing(true, true);
        CornerServer.attach(this, cornerCommands);   // the screen in front takes the phone's commands
    }

    @Override protected void onDestroy() {
        CornerServer.detach(cornerCommands);
        corner.destroy();
        super.onDestroy();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(queueTick);
        corner.onPause();
        TvPicture.frameOff(this);
        Bedtime.showing(true, false);
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
        if (panel.isOpen()) return panel.onKey(code, e.getRepeatCount() > 0);
        if (ok) {
            // OK: a short press is "next photo" (on release); holding it opens the photo menu.
            if (e.getRepeatCount() == 0) { e.startTracking(); longPressed = false; }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_LEFT:
                // A short press changes the photo (on release); holding it skips the YouTube song (2026-11-22).
                if (e.getRepeatCount() == 0) { e.startTracking(); sideHeld = false; }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                panel.open();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MENU:
                // A short press makes the YouTube corner smaller (on release); holding it opens Add photos.
                if (e.getRepeatCount() == 0) { e.startTracking(); downHeld = false; }
                return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
            sideHeld = true;
            if (!corner.showing()) { note("No YouTube playing. Press ▼ to bring it back."); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { corner.next(); note("Next song"); }
            else note(corner.back());
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU) {
            downHeld = true;
            addPhotos();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            longPressed = true;
            if (tripPanel.isOpen()) tripPanel.onKey(code, true);
            else if (!panel.isOpen() && renaming == null) menu.open(frame.currentPhotos());
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!e.isTracking()) return super.onKeyUp(code, e);   // a panel or menu had this key
            if (!sideHeld && !e.isCanceled()) {
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) frame.next();
                else frame.back(() -> note("This is the first photo"));
            }
            sideHeld = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU) {
            if (!downHeld && e.isTracking() && !e.isCanceled()) {
                String n = corner.step();
                if (n != null) note(n);
                else addPhotos();   // no YouTube played yet (e.g. a new frame): ▼ adds photos, as the first-run hint says
            }
            downHeld = false;
            return e.isTracking() || super.onKeyUp(code, e);
        }
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
        else if (panel.isOpen()) panel.onKey(KeyEvent.KEYCODE_BACK, false);   // up one page, then closes
        else if (corner.showing()) { corner.close(); note("YouTube corner closed"); }   // the remote's Back (2026-11-22)
        else super.onBackPressed();
    }

    // PhotoMenu.Host
    @Override public boolean isFavourite(String id) { return frame.isFavourite(id); }
    @Override public void favourite(String id) {
        frame.toggleFavourite(id, now -> note(now ? "Added to favourites" : "Removed from favourites"));
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
    @Override public void renameTrip(String key, String label) { openRename(key, label, true); }
    @Override public String tripLabel(String id) {
        String[] t = frame.tripOfPhoto(id);
        return t == null ? null : t[2];
    }
    @Override public void renameTripOf(String id) {
        String[] t = frame.tripOfPhoto(id);
        if (t == null) { note("This photo has no date, so it isn't in an album"); return; }
        openRename(t[3], t[2], false);
    }

    /** A small box with the TV keyboard (which has a mic) to name an album or trip. Empty = a trip's date name. */
    private void buildRenameBox(FrameLayout root) {
        renameBox = new android.widget.LinearLayout(this);
        renameBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 30);
        renameBox.setPadding(pad, pad, pad, pad);
        renameBox.setBackground(Ui.card(this));
        renameTitle = Ui.title(this, "", 24);
        renameBox.addView(renameTitle);
        renameBox.addView(Ui.gap(this, 16));
        renameField = new android.widget.EditText(this);
        renameField.setSingleLine(true);
        Ui.field(renameField);                       // a light box with dark text
        renameField.setHint("Say or type a name, e.g. Egypt");
        renameField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        renameField.setOnEditorActionListener((v, a, ev) -> { saveRename(); return true; });
        renameBox.addView(renameField);
        renameBox.addView(Ui.gap(this, 12));
        renameHint = Ui.text(this, "", Ui.HINT, Ui.SOFT);
        renameBox.addView(renameHint);
        renameBox.addView(Ui.gap(this, 16));
        renameBox.addView(Ui.keys(this, "OK", "Save", "Back", "Cancel"));
        renameBox.setVisibility(View.GONE);
        root.addView(renameBox, new FrameLayout.LayoutParams(Math.round(560 * getResources().getDisplayMetrics().density), -2, Gravity.CENTER));
    }

    private void openRename(String key, String label, boolean fromTrips) {
        renaming = key;
        renameFromTrips = fromTrips;
        tripPanel.setVisibility(View.GONE);
        renameTitle.setText("Name this album: " + label.replace("  ·  ", " · "));
        renameHint.setText(AlbumStore.albumId(key) != null ? "Leave it empty to keep its name." : "Leave it empty to use the dates instead.");
        renameField.setText("");
        renameBox.setVisibility(View.VISIBLE);
        Ui.fadeIn(renameBox);
        renameField.requestFocus();
        renameField.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(renameField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 300);
    }

    private void saveRename() {
        if (renaming == null) return;
        String key = renaming;
        String name = renameField.getText().toString().trim();
        boolean back = renameFromTrips, album = AlbumStore.albumId(key) != null;
        closeRename();
        frame.rename(key, name, () -> {
            note(!name.isEmpty() ? "Album named \"" + name + "\"" : album ? "Name kept" : "Name removed; it shows its dates again");
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
    /**
     * adb-only, one time (2026-11-22): puts every photo that arrived in a time window into an album, for
     * a batch picked before albums existed. Dates are the files' arrival times (Shrinker keeps them):
     * am start --activity-single-top -n com.gary.photosframe/.MainActivity --es test_album_name "Mom & Dad"
     *   --el test_album_from <epoch ms> --el test_album_to <epoch ms>
     */
    private void importAlbumForTest() {
        String name = getIntent().getStringExtra("test_album_name");
        long from = getIntent().getLongExtra("test_album_from", -1), to = getIntent().getLongExtra("test_album_to", -1);
        getIntent().removeExtra("test_album_name");
        getIntent().removeExtra("test_album_from");
        getIntent().removeExtra("test_album_to");
        if (name == null) return;
        if (name.trim().isEmpty() || from < 0 || to < from) {
            android.util.Log.w("AlbumImport", "needs test_album_name, test_album_from <= test_album_to (epoch ms)");
            return;
        }
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            int[] r = PhotoLibrary.get(app).importAlbum(name.trim(), from, to);
            ui.post(() -> { frame.reloadQuietly(); note(r[0] + " photos in \"" + name.trim() + "\" (" + r[1] + " newly added)"); });
        }, "album-import").start();
    }

    @Override public void unhideAll() { frame.unhideAll(); note("Hidden photos will show again"); }

    // YouTube corner
    @Override public void youtubeCorner() {
        close();
        String url = CornerServer.address(this);
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 36);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.card(this));
        if (url != null) {
            android.widget.ImageView qr = new android.widget.ImageView(this);
            // Made at the size it is shown, so every square of the code stays sharp for the phone's camera.
            qr.setImageBitmap(Qr.make(url, Ui.dp(this, 220), Ui.DARK, Ui.PAPER));
            android.widget.LinearLayout.LayoutParams qp = new android.widget.LinearLayout.LayoutParams(-2, -2);
            qp.rightMargin = pad;
            card.addView(Ui.qrTile(this, qr, 220), qp);
        }
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.addView(Ui.eyebrow(this, "Phone remote"));
        col.addView(Ui.gap(this, 6));
        col.addView(Ui.title(this, "YouTube corner", Ui.TITLE));
        col.addView(Ui.gap(this, 18));
        if (url == null) {
            col.addView(Ui.text(this, "The TV isn't connected to your Wi-Fi.", 19, Ui.INK));
        } else {
            col.addView(Ui.step(this, 1, "Scan the code with your phone, on the home Wi-Fi."));
            col.addView(Ui.step(this, 2, "Paste a YouTube link and tap Play on TV. It plays in a corner while the photos carry on."));
            TextView more = Ui.text(this, "Tip: in Chrome, menu → Add to Home screen, so you don't need this code again.\n"
                    + "Not signed in to YouTube: public and unlisted videos and playlists play, with ads.", 15, Ui.SOFT);
            more.setLineSpacing(0, 1.3f);
            col.addView(more);
        }
        col.addView(Ui.gap(this, 20));
        col.addView(Ui.keys(this, "OK or Back", "Close"));
        card.addView(col, new android.widget.LinearLayout.LayoutParams(Ui.dp(this, 400), -2));
        root.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        Ui.fadeIn(card);
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
        // The phone's slider and segmented controls send the choice they want ("size" with 2); with no
        // number (n < 0) these commands step to the next choice, as they always did.
        final int n = CornerPlayer.choice(arg);
        runOnUiThread(() -> {
            switch (c) {
                case "toggle": corner.toggle(); break;
                case "next": corner.next(); break;
                case "prev": corner.prev(); break;
                case "volup": corner.volume(+10); break;
                case "voldown": corner.volume(-10); break;
                case "vol": if (n >= 0) corner.setVolume(n); break;
                case "move": if (n >= 0) corner.setCorner(n); else corner.move(); break;
                case "zoom": if (n >= 0) corner.setZoom(n); else corner.zoom(); break;
                case "blend": if (n >= 0) corner.setBlend(n); else corner.blend(); break;
                case "size": if (n >= 0) corner.setSize(n); else corner.nextSize(); break;
                case "close": corner.close(); break;
            }
        });
        return null;
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
