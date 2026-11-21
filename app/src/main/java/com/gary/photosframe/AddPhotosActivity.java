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
    private static final int INK = 0xFFF1EDE4, SOFT = 0xFFB9B2A6, DARK = 0xFF2E2B28;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private Auth auth;
    private ImageView qr;
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
            show(null, "Not set up yet", "Google sign-in isn't set up on this TV yet. Ask whoever set up the app.", null);
            return;
        }
        if (Storage.isFull(this)) {
            show(null, "No room for more photos", Storage.warning(this)
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
            show(null, "Something went wrong", msg + "\n\nPress OK to try again, or Back to return to the frame.", null);
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
            show(url, "Sign in to Google Photos (only needed once)",
                    "With your phone on the same Wi-Fi as the TV, scan this code.\n"
                            + "A page opens with three short steps. If the phone can't reach it, type this address\n"
                            + "in a computer's browser instead (also on this Wi-Fi):", url.replace("http://", ""));
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
                    show(s.pickerUri, "Choose photos for the frame",
                            "Scan with your phone to open Google Photos.\n"
                                    + "Tip: type the trip's album name in the search box, then tap the circle next to each day.\n"
                                    + "Up to 2,000 photos at a time. After you tap Done, a new code appears here for the\n"
                                    + "next lot while these download in the background."
                                    + (warn == null ? "" : "\n\n" + warn), null);
                } else {
                    show(s.pickerUri, String.format("%,d", lastPick) + " photos added to the queue. More?",
                            "Scan this new code to pick the next lot (up to 2,000), or press Back:\n"
                                    + "the photos keep downloading while the frame plays, and join the same trip."
                                    + (warn == null ? "" : "\n\n" + warn), null);
                }
                boolean picked = false;
                while (!stopped) {
                    if (picker.isDone(s)) { picked = true; break; }
                    if (System.currentTimeMillis() > s.timeoutAtMs) break;   // code too old: show a fresh one
                    Thread.sleep(s.pollMs);
                }
                if (stopped || !picked) continue;
                show(null, "Reading your pick…", "", null);
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
            show(null, "Getting a new code…", "", null);
            namingOpen = false;
            return;
        }
        NewTrip t = toName.get(naming);
        qr.setVisibility(View.GONE);
        title.setText(toName.size() == 1 ? "A new trip" : "New trip " + (naming + 1) + " of " + toName.size());
        body.setText("");
        code.setVisibility(View.GONE);
        nameTitle.setText(Trips.label(t.start, t.end, "", t.items.size()));
        nameInfo.setText(t.items.size() + " photos.  Say or type a name (e.g. Egypt) and press OK.\n"
                + "Press Back to skip; the trip will show its dates. You can name it later in Settings → Trips to play.");
        coverRow.removeAllViews();
        for (Bitmap bmp : t.covers) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
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

    private void show(String qrText, String t, String b, String c) {
        ui.post(() -> {
            Bitmap bmp = qrText == null ? null : Qr.make(qrText, 420, DARK, INK);
            qr.setImageBitmap(bmp);
            qr.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
            title.setText(t);
            body.setText(b);
            code.setText(c == null ? "" : c);
            code.setVisibility(c == null ? View.GONE : View.VISIBLE);
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(DARK);
        root.setGravity(Gravity.CENTER);
        root.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(48);
        root.setPadding(pad, pad, pad, pad);

        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(260), dp(260));
        qp.rightMargin = dp(48);
        root.addView(qr, qp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        title = text(30, INK, Typeface.SERIF);
        body = text(18, SOFT, Typeface.SANS_SERIF);
        body.setLineSpacing(0, 1.3f);
        code = text(26, INK, Typeface.MONOSPACE);
        col.addView(title);
        // A bare View with WRAP_CONTENT takes all the space it is offered, so give spacers a fixed height.
        col.addView(spacer(16), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)));
        col.addView(body);
        col.addView(spacer(12), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));
        col.addView(code);
        queueLine = text(16, INK, Typeface.SANS_SERIF);
        queueLine.setVisibility(View.GONE);
        col.addView(spacer(12), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));
        col.addView(queueLine);

        nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        nameBox.setVisibility(View.GONE);
        coverRow = new LinearLayout(this);
        coverRow.setOrientation(LinearLayout.HORIZONTAL);
        nameBox.addView(coverRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)));
        nameTitle = text(24, INK, Typeface.SERIF);
        nameBox.addView(nameTitle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setHint("Name (optional), e.g. Egypt");
        nameField.setTextColor(DARK);               // the TV draws the text box light
        nameField.setHintTextColor(0x992E2B28);
        nameField.setTextSize(22);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.setOnEditorActionListener((v, actionId, ev) -> { saveName(false); return true; });
        nameBox.addView(nameField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        nameInfo = text(15, SOFT, Typeface.SANS_SERIF);
        nameInfo.setLineSpacing(0, 1.2f);
        nameBox.addView(nameInfo);
        col.addView(spacer(12), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)));
        col.addView(nameBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // Fill whatever width is left after the QR: a fixed width overflowed the 960dp TV screen,
        // which squeezed the QR (cutting off its left edge) and pushed the text off the right edge.
        root.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        setContentView(root);
    }

    private TextView text(int sp, int color, Typeface face) {
        TextView v = new TextView(this);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(face);
        return v;
    }

    private View spacer(int h) { View v = new View(this); v.setMinimumHeight(dp(h)); return v; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
