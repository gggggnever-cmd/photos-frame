package com.gary.photosframe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
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

    // Naming new trips while the photos download (one screen per new trip).
    private static final class NewTrip {
        final String start, end;
        final List<Picker.Item> items = new ArrayList<>();
        final List<Bitmap> covers = new ArrayList<>();
        NewTrip(String start, String end) { this.start = start; this.end = end; }
    }
    private LinearLayout nameBox, coverRow;
    private TextView nameTitle, nameInfo;
    private EditText nameField;
    private final List<NewTrip> toName = new ArrayList<>();   // UI thread
    private int naming = -1;                                   // index in toName, -1 when not naming
    private volatile boolean namingOpen;
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
        if (naming >= 0 && k == KeyEvent.KEYCODE_ENTER) { saveName(false); return true; }
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
     * Shows a QR code, waits for the pick, hands it to the background DownloadQueue, lets the owner name
     * any new trips, then shows a fresh QR code for the next lot. Back leaves; downloads carry on.
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
                            "The photos keep downloading while the frame plays, and join the same trip."
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

                // Work out the trips in this pick from the photos' dates.
                List<String> days = new ArrayList<>();
                for (Picker.Item it : items) days.add(it.day);
                List<NewTrip> unnamed = new ArrayList<>();
                String pickStart = null, pickEnd = null;
                for (String[] r : Trips.cluster(days)) {
                    NewTrip t = new NewTrip(r[0], r[1]);
                    for (Picker.Item it : items) if (!it.day.isEmpty() && it.day.compareTo(r[0]) >= 0 && it.day.compareTo(r[1]) <= 0) t.items.add(it);
                    if (pickStart == null) pickStart = r[0];
                    pickEnd = r[1];
                    // Already named, or offered earlier in this visit: the photos just join it.
                    if (library.nameFor(r[0], r[1]).isEmpty() && !askedAbout(r[0], r[1])) unnamed.add(t);
                }
                DownloadQueue.add(this, s.id, pickStart, pickEnd, items);
                handedOver = true;
                lastPick = items.size();
                if (!unnamed.isEmpty()) {
                    // Four small previews per trip, spread across it, so the owner recognises it.
                    File tmp = getCacheDir();
                    for (NewTrip t : unnamed) {
                        int n = t.items.size();
                        for (int k = 0; k < Math.min(4, n); k++) {
                            Bitmap bmp = picker.thumbnail(t.items.get(k * n / Math.min(4, n)), tmp);
                            if (bmp != null) t.covers.add(bmp);
                        }
                        asked.add(new String[] {t.start, t.end});
                    }
                    namingOpen = true;
                    ui.post(() -> { toName.clear(); toName.addAll(unnamed); naming = -1; nextName(); });
                    while (namingOpen && !stopped) Thread.sleep(300);
                }
            } finally {
                if (!handedOver) picker.delete(s);
            }
        }
    }

    /** Trips offered for naming during this visit, so a skipped one isn't asked again for the next lot. */
    private final List<String[]> asked = new ArrayList<>();   // add-photos thread only
    private boolean askedAbout(String start, String end) {
        for (String[] a : asked) if (a[0].compareTo(end) <= 0 && a[1].compareTo(start) >= 0) return true;   // overlaps
        return false;
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

    /** Shows the naming screen for the next unnamed trip, or the progress/summary when all are done. */
    private void nextName() {
        naming++;
        if (naming >= toName.size()) {
            naming = -1;
            nameBox.setVisibility(View.GONE);
            hideKeyboard();
            show(null, "Getting a new code…", null, "", null);
            namingOpen = false;
            return;
        }
        NewTrip t = toName.get(naming);
        qrTile.setVisibility(View.GONE);
        steps.removeAllViews();
        footer.setVisibility(View.GONE);   // Back means "skip" here; the lines under the name box say so
        title.setText(toName.size() == 1 ? "A new trip" : "New trip " + (naming + 1) + " of " + toName.size());
        body.setVisibility(View.GONE);
        code.setVisibility(View.GONE);
        nameTitle.setText(Trips.label(t.start, t.end, "", t.items.size()));
        nameInfo.setText(t.items.size() + " photos.  Say or type a name (e.g. Egypt) and press OK.\n"
                + "Press Back to skip; the trip will show its dates. You can name it later in Settings → Trips to play.");
        coverRow.removeAllViews();
        for (Bitmap bmp : t.covers) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.shape(this, 0x14F1EDE4, 10));   // rounded corners: the picture is clipped to this shape
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(128), dp(96));
            lp.rightMargin = dp(8);
            coverRow.addView(iv, lp);
        }
        nameField.setText("");
        nameBox.setVisibility(View.VISIBLE);
        nameField.requestFocus();
        ui.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(nameField, InputMethodManager.SHOW_IMPLICIT);
        }, 300);
    }

    /** Saves the typed/spoken name (empty = keep the date name) and moves on. */
    private void saveName(boolean keep) {
        if (naming < 0) return;
        NewTrip t = toName.get(naming);
        String name = keep ? "" : nameField.getText().toString().trim();
        if (!name.isEmpty()) {
            PhotoLibrary lib = library;
            new Thread(() -> lib.nameTrip(t.start, t.end, name), "name-trip").start();
        }
        nextName();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(nameField.getWindowToken(), 0);
    }

    @Override public void onBackPressed() {
        if (naming >= 0) { saveName(true); return; }   // Back while naming = keep the date name
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

        // Naming a new trip: previews, its dates, a box for the name.
        nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        nameBox.setVisibility(View.GONE);
        coverRow = new LinearLayout(this);
        coverRow.setOrientation(LinearLayout.HORIZONTAL);
        nameBox.addView(coverRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)));
        nameBox.addView(Ui.gap(this, 16));
        nameTitle = Ui.title(this, "", 24);
        nameBox.addView(nameTitle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        nameBox.addView(Ui.gap(this, 12));
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setHint("Name (optional), e.g. Egypt");
        Ui.field(nameField);                         // a light box with dark text
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.setOnEditorActionListener((v, actionId, ev) -> { saveName(false); return true; });
        nameBox.addView(nameField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        nameBox.addView(Ui.gap(this, 12));
        nameInfo = Ui.text(this, "", 15, Ui.SOFT);
        nameInfo.setLineSpacing(0, 1.3f);
        nameBox.addView(nameInfo);
        col.addView(nameBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
