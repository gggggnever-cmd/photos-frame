package com.gary.photosframe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Add photos": sign in once with the device code, then show a QR code for the Google Photos
 * picker, wait while the user picks on their phone, queue the pick for download (DownloadQueue),
 * then show a new code for the next lot. Downloads carry on in the background after Back.
 */
public class AddPhotosActivity extends Activity {
    static final String EXTRA_ADDED = "added";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private Auth auth;
    private ImageView qr;
    private View qrTile, footer;   // the light tile round the code; the "Back" hint
    private LinearLayout steps;    // the numbered steps beside the code
    private TextView title, body, code;
    private boolean canRetry;

    // After each pick, one screen asks where the batch goes (BatchChoice) while the photos download.
    private BatchChoice choice;
    private volatile boolean choosing;                 // the add-photos thread waits while the screen is up
    private volatile BatchChoice.Option chosenOption;  // set on the UI thread before choosing turns false
    private volatile String chosenName;

    /** Where a batch goes by default: an album (albumId), an existing trip, or a new trip by date (isNew). */
    private static final class Target {
        String albumId = "", label = "", start = "", end = "";
        boolean named, isNew;
        int overlap;
    }

    /**
     * Lots picked earlier in this visit and where they went. They may not have downloaded yet, so the
     * library doesn't know their trip or album; a later lot with the same dates joins the same one.
     */
    private static final class Lot {
        final String start, end, albumId, label;
        final boolean named;
        Lot(String start, String end, String albumId, String label, boolean named) {
            this.start = start; this.end = end; this.albumId = albumId; this.label = label; this.named = named;
        }
    }
    private final List<Lot> lots = new ArrayList<>();   // add-photos thread only

    private TextView queueLine;
    private PhotoLibrary library;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        auth = new Auth(this);
        buildUi();
        ui.post(tick);
        start();
    }

    @Override protected void onDestroy() { stopped = true; super.onDestroy(); }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (canRetry && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) { start(); return true; }
        if (choice.isOpen() && choice.onKey(k)) return true;
        return super.onKeyDown(k, e);
    }

    private void start() {
        canRetry = false;
        if (!auth.isConfigured()) {
            show(null, "Not set up yet", null, "Google sign-in isn't set up on this TV yet. Ask whoever set up the app.", null);
            return;
        }
        if (Storage.isFull(this)) {
            show(null, "No room for more photos", null, Storage.warning(this)
                    + "\n\nPress Back to return to the frame.", null);
            return;
        }
        new Thread(this::run, "add-photos").start();
    }

    private void run() {
        try {
            if (!auth.isSignedIn()) signIn();
            if (stopped) return;
            try {
                pick();
            } catch (Auth.SignInNeeded e) {   // Google expired the old sign-in: sign in again, then pick.
                signIn();
                if (!stopped) pick();
            }
        } catch (Exception e) {
            if (stopped) return;
            String msg = e.getMessage();
            if (e instanceof Http.HttpError && ((Http.HttpError) e).code == 401) auth.signOut();
            canRetry = true;
            show(null, "Something went wrong", null, msg + "\n\nPress OK to try again, or Back to return to the frame.", null);
        }
    }

    /** One-time sign-in, done on the phone through the TV's little setup page (see SetupServer). */
    private void signIn() throws IOException, InterruptedException {
        final Object lock = new Object();
        final boolean[] ok = {false};
        SetupServer server = new SetupServer(auth, pasted -> {
            try {
                auth.finishSignIn(pasted);
                synchronized (lock) { ok[0] = true; lock.notifyAll(); }
                return null;
            } catch (IOException e) {
                return e.getMessage();
            }
        });
        String url = server.start();
        if (url == null) throw new IOException("The TV isn't connected to your Wi-Fi.");
        try {
            show(url, "Sign in to Google Photos (only needed once)", new String[] {
                            "With your phone on the same Wi-Fi as the TV, scan this code.",
                            "A page opens with three short steps."},
                    "If the phone can't reach it, type this address in a computer's browser instead (also on this Wi-Fi):",
                    url.replace("http://", ""));
            synchronized (lock) {
                while (!ok[0] && !stopped) lock.wait(1000);
            }
        } finally {
            server.stop();
        }
    }

    /**
     * Shows a QR code, waits for the pick, hands it to the background DownloadQueue, asks on one screen
     * where the batch goes, then shows a fresh QR code for the next lot. Back leaves; downloads carry on.
     */
    private void pick() throws IOException, InterruptedException {
        DownloadQueue.resetReport();
        library = PhotoLibrary.get(this);
        int lastPick = 0;
        while (!stopped) {
            Picker picker = new Picker(auth);
            Picker.Session s = picker.create();
            boolean handedOver = false;
            try {
                String warn = Storage.warning(this);
                if (lastPick == 0) {
                    show(s.pickerUri, "Choose photos for the frame", new String[] {
                                    "Scan with your phone to open Google Photos.",
                                    "Pick your photos, up to 2,000 at a time.",
                                    "Tap Done. A new code appears here for the next lot."},
                            "These download in the background. Tip: type the trip's album name in the search box, "
                                    + "then tap the circle next to each day."
                                    + (warn == null ? "" : "\n\n" + warn), null);
                } else {
                    show(s.pickerUri, String.format("%,d", lastPick) + " photos added to the queue. More?", new String[] {
                                    "Scan this new code to pick the next lot, up to 2,000.",
                                    "Or press Back when you have finished."},
                            "The photos keep downloading while the frame plays, and go into the album you chose."
                                    + (warn == null ? "" : "\n\n" + warn), null);
                }
                boolean picked = false;
                while (!stopped) {
                    if (picker.isDone(s)) { picked = true; break; }
                    if (System.currentTimeMillis() > s.timeoutAtMs) break;   // code too old: show a fresh one
                    Thread.sleep(s.pollMs);
                }
                if (stopped || !picked) continue;
                show(null, "Reading your pick…", null, "", null);
                List<Picker.Item> items = picker.photos(s);

                // The pick's dates, grouped the way trips are, and where the batch goes unless the owner says otherwise.
                List<String> days = new ArrayList<>();
                for (Picker.Item it : items) days.add(it.day);
                List<String[]> clusters = Trips.cluster(days);
                String pickStart = clusters.isEmpty() ? null : clusters.get(0)[0];
                String pickEnd = clusters.isEmpty() ? null : clusters.get(clusters.size() - 1)[1];
                Target target = defaultFor(clusters, items);
                // Queued at once with the default, so downloading starts while the owner decides.
                DownloadQueue.add(this, s.id, pickStart, pickEnd, target.albumId, items);
                handedOver = true;
                lastPick = items.size();

                // Four small previews spread across the pick, so the owner recognises it.
                List<Bitmap> covers = new ArrayList<>();
                int n = items.size();
                for (int k = 0; k < Math.min(4, n); k++) {
                    Bitmap bmp = picker.thumbnail(items.get(k * n / Math.min(4, n)), getCacheDir());
                    if (bmp != null) covers.add(bmp);
                }
                List<BatchChoice.Option> opts = options(target);
                String heading = String.format("%,d photos", n) + (pickStart == null ? "" : "  ·  " + Trips.months(pickStart, pickEnd));
                chosenOption = opts.get(0);
                chosenName = null;
                choosing = true;
                ui.post(() -> openChoice(heading, covers, opts));
                while (choosing && !stopped) Thread.sleep(300);
                apply(chosenOption, chosenName, target, s.id, items, clusters);
            } finally {
                if (!handedOver) picker.delete(s);
            }
        }
    }

    /**
     * The default for a pick: the album or trip its dates overlap most (lots picked earlier in this visit
     * count too, as they may not have arrived yet); otherwise a new album by date, which is simply the
     * automatic date trip, named like one ("Oct 2026 · 7 days").
     */
    private Target defaultFor(List<String[]> clusters, List<Picker.Item> items) {
        Target best = null;
        for (String[] r : clusters) {
            for (Lot l : lots) {
                int o = Trips.overlap(r[0], r[1], l.start, l.end);
                if (o > 0 && (best == null || o > best.overlap)) best = target(l.albumId, l.label, l.start, l.end, l.named, o);
            }
            String[] m = library.bestMatch(r[0], r[1]);   // {key, label, overlap, albumId, named, start, end}
            if (m != null) {
                int o = Integer.parseInt(m[2]);
                if (best == null || o > best.overlap) best = target(m[3], m[1], m[5], m[6], !m[4].isEmpty(), o);
            }
        }
        if (best != null) return best;
        String[] big = null;
        int most = -1;
        for (String[] r : clusters) {
            int c = 0;
            for (Picker.Item it : items) if (!it.day.isEmpty() && it.day.compareTo(r[0]) >= 0 && it.day.compareTo(r[1]) <= 0) c++;
            if (c > most) { most = c; big = r; }
        }
        Target t = big == null ? target("", "Undated photos", "", "", true, 0)   // no dates: they stay with all photos
                : target("", Trips.label(big[0], big[1], "", most), big[0], big[1], false, 0);
        t.isNew = true;
        return t;
    }

    private static Target target(String albumId, String label, String start, String end, boolean named, int overlap) {
        Target t = new Target();
        t.albumId = albumId; t.label = label.replace("  ·  ", " · "); t.start = start; t.end = end;
        t.named = named; t.overlap = overlap;
        return t;
    }

    /** The rows of the choice screen: the default first, then naming, a new album, and the albums to add to. */
    private List<BatchChoice.Option> options(Target t) {
        List<BatchChoice.Option> out = new ArrayList<>();
        out.add(new BatchChoice.Option(BatchChoice.DEFAULT, (t.isNew ? "New album: " : "Add to ") + t.label,
                t.albumId.isEmpty() ? "by date" : "", t.albumId, t.label));
        if (t.albumId.isEmpty() && !t.named && !t.start.isEmpty())
            out.add(new BatchChoice.Option(BatchChoice.NAME, "Name this album", "e.g. Egypt", "", ""));
        out.add(new BatchChoice.Option(BatchChoice.NEW, "New album…", "type a name", "", ""));
        for (String[] a : library.albumChoices())   // {id, name, count}
            if (!a[0].equals(t.albumId))
                out.add(new BatchChoice.Option(BatchChoice.ALBUM, "Add to " + a[1], String.format("%,d", Integer.parseInt(a[2])) + " photos", a[0], a[1]));
        return out;
    }

    /** Carries out the owner's choice (on the add-photos thread) and remembers it for the next lots. */
    private void apply(BatchChoice.Option o, String name, Target t, String session, List<Picker.Item> items, List<String[]> clusters) {
        String albumId = t.albumId, label = t.label;
        boolean named = t.named;
        if (o.kind == BatchChoice.NEW && name != null) {
            albumId = library.createAlbum(name);
            label = name;
            named = true;
            DownloadQueue.setAlbum(this, session, albumId, items);
        } else if (o.kind == BatchChoice.ALBUM) {
            albumId = o.albumId;
            label = o.name;
            named = true;
            DownloadQueue.setAlbum(this, session, albumId, items);
        } else if (o.kind == BatchChoice.NAME && name != null) {
            library.nameTrip(t.start, t.end, name);   // a trip by date: no membership, just its name
            label = name;
            named = true;
        }
        Log.i("AddPhotos", items.size() + " photos go to " + (albumId.isEmpty() ? "date trip " : "album " + albumId + " ") + label);
        for (String[] r : clusters) lots.add(new Lot(r[0], r[1], albumId, label, named));
    }

    /** The queue line under the text, refreshed every two seconds while this screen is open. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            String st = DownloadQueue.status(), pr = DownloadQueue.problems();
            int added = DownloadQueue.addedTotal();
            StringBuilder s = new StringBuilder();
            if (st != null) s.append(st);
            else if (added > 0) s.append("All downloaded: ").append(String.format("%,d", added)).append(" new photos");
            if (!pr.isEmpty()) s.append(s.length() > 0 ? "\n" : "").append(pr);
            queueLine.setText(s);
            queueLine.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
            ui.postDelayed(this, 2000);
        }
    };

    /** Swaps the QR screen for the choice of where the batch goes. */
    private void openChoice(String heading, List<Bitmap> covers, List<BatchChoice.Option> opts) {
        qrTile.setVisibility(View.GONE);
        steps.removeAllViews();
        footer.setVisibility(View.GONE);   // the choice card shows its own keys
        body.setVisibility(View.GONE);
        code.setVisibility(View.GONE);
        title.setText("Where should these photos go?");
        choice.open(heading, covers, opts);
    }

    /** The owner chose (UI thread): hand it to the add-photos thread, which carries it out. */
    private void chosen(BatchChoice.Option o, String name) {
        chosenOption = o;
        chosenName = name;
        show(null, "Getting a new code…", null, "", null);
        choosing = false;
    }

    @Override public void onBackPressed() {
        if (choice.isOpen()) { choice.back(); return; }   // Back on the choice = the default (or out of typing)
        super.onBackPressed();
    }

    /** One screen: an optional QR code, a title, optional numbered steps, a paragraph, and an optional address. */
    private void show(String qrText, String t, String[] stepList, String b, String c) {
        ui.post(() -> {
            // Made at the size it is shown, so every square of the code stays sharp for the phone's camera.
            Bitmap bmp = qrText == null ? null : Qr.make(qrText, dp(260), Ui.DARK, Ui.PAPER);
            qr.setImageBitmap(bmp);
            qrTile.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
            title.setText(t);
            steps.removeAllViews();
            if (stepList != null) for (int i = 0; i < stepList.length; i++) steps.addView(Ui.step(this, i + 1, stepList[i]));
            body.setText(b);
            body.setVisibility(b == null || b.isEmpty() ? View.GONE : View.VISIBLE);
            code.setText(c == null ? "" : c);
            code.setVisibility(c == null ? View.GONE : View.VISIBLE);
            footer.setVisibility(View.VISIBLE);
        });
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        // The code on its light tile to the left, the words to the right, centred together.
        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER);
        content.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(56);
        content.setPadding(pad, dp(36), pad, dp(64));

        qr = new ImageView(this);
        qrTile = Ui.qrTile(this, qr, 260);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-2, -2);
        qp.rightMargin = dp(52);
        content.addView(qrTile, qp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.eyebrow(this, "Photos Frame  ·  Add photos"));
        col.addView(Ui.gap(this, 8));
        title = Ui.title(this, "", 32);
        title.setLineSpacing(0, 1.1f);
        col.addView(title);
        col.addView(Ui.gap(this, 20));
        steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        col.addView(steps);
        body = Ui.text(this, "", Ui.BODY, Ui.SOFT);
        body.setLineSpacing(0, 1.35f);
        col.addView(body);
        code = Ui.text(this, "", 24, Ui.INK);        // the address to type on a computer, set apart in its own box
        code.setTypeface(Typeface.MONOSPACE);
        code.setBackground(Ui.shape(this, 0x14F1EDE4, 12));
        code.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(14);
        col.addView(code, cp);

        // Where the batch goes, after each pick (replaces the QR and steps while it shows).
        choice = new BatchChoice(this, this::chosen);
        col.addView(choice, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // Fill whatever width is left after the QR: a fixed width overflowed the 960dp TV screen,
        // which squeezed the QR (cutting off its left edge) and pushed the text off the right edge.
        content.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Along the bottom: what Back does (left) and how the downloads are going (right).
        footer = Ui.keys(this, "Back", "Return to the frame");
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        fp.leftMargin = pad;
        fp.bottomMargin = dp(28);
        root.addView(footer, fp);
        queueLine = Ui.text(this, "", 15, Ui.SOFT);
        queueLine.setLineSpacing(0, 1.25f);
        queueLine.setBackground(Ui.shape(this, 0x14F1EDE4, 14));
        queueLine.setPadding(dp(16), dp(9), dp(16), dp(10));
        queueLine.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = pad;
        lp.bottomMargin = dp(22);
        root.addView(queueLine, lp);

        setContentView(root);
        Ui.fadeIn(content);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
