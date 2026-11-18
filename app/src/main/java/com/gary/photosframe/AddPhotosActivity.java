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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * "Add photos": sign in once with the device code, then show a QR code for the Google Photos
 * picker, wait while the user picks on their phone, and download the picks into files/photos.
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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        auth = new Auth(this);
        buildUi();
        start();
    }

    @Override protected void onDestroy() { stopped = true; super.onDestroy(); }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (canRetry && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) { start(); return true; }
        return super.onKeyDown(k, e);
    }

    private void start() {
        canRetry = false;
        if (!auth.isConfigured()) {
            show(null, "Not set up yet", "This build has no Google sign-in client (oauth_client.json).", null);
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
        if (url == null) throw new IOException("The TV isn't connected to the home network.");
        try {
            show(url, "Sign in to Google Photos (one time)",
                    "With your phone on the same Wi-Fi as the TV, scan this code.\n"
                            + "A page opens with three short steps. Or type this address:", url.replace("http://", ""));
            synchronized (lock) {
                while (!ok[0] && !stopped) lock.wait(1000);
            }
        } finally {
            server.stop();
        }
    }

    private void pick() throws IOException, InterruptedException {
        Picker picker = new Picker(auth);
        Picker.Session s = picker.create();
        String warn = Storage.warning(this);
        show(s.pickerUri, "Choose photos for the frame",
                "Scan with your phone to open Google Photos.\nSelect the photos you want (up to 2,000), then tap Done.\n"
                        + "Photos you add join the " + Storage.photoCount(this) + " already on the frame."
                        + (warn == null ? "" : "\n\n" + warn), null);
        try {
            while (!stopped && !picker.isDone(s)) {
                if (System.currentTimeMillis() > s.timeoutAtMs) throw new IOException("The picking session timed out.");
                Thread.sleep(s.pollMs);
            }
            if (stopped) return;
            show(null, "Bringing your photos to the frame…", "", null);
            List<Picker.Item> items = picker.photos(s);
            Picker.Result r = picker.download(items, Storage.photoDir(this), () -> Storage.hasRoomForOne(this),
                    (done, total) -> show(null, "Bringing your photos to the frame…",
                            done + " of " + total + "   ·   " + Storage.gb(Storage.free(this)) + " free on the TV", null));
            StringBuilder sum = new StringBuilder();
            if (r.alreadyThere > 0) sum.append(r.alreadyThere).append(" were already on the frame.\n");
            if (r.failed > 0) sum.append(r.failed).append(" could not be downloaded; pick them again later.\n");
            if (r.noRoom > 0) sum.append(r.noRoom).append(" were not added because TV storage is full.\n");
            String after = Storage.warning(this);
            if (after != null && r.noRoom == 0) sum.append(after).append("\n");
            boolean problem = r.failed > 0 || r.noRoom > 0;
            sum.append(problem ? "\nPress Back to return to the frame." : "Returning to the frame…");
            show(null, r.added == 1 ? "1 new photo added" : r.added + " new photos added", sum.toString(), null);
            int added = r.added;
            setResult(RESULT_OK, getIntent().putExtra(EXTRA_ADDED, added));
            if (!problem) ui.postDelayed(this::finish, 3000);
        } finally {
            picker.delete(s);
        }
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
        root.addView(col, new LinearLayout.LayoutParams(dp(560), LinearLayout.LayoutParams.WRAP_CONTENT));
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
