package com.gary.photosframe;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one look shared by everything drawn over the photos (2026-11-22): colours, type sizes, the dark
 * glass card, the focused-row pill, key hints and the QR tile. Screens build their views through these
 * so they stay one family; the phone page (assets/corner_phone.html) uses the same palette.
 * Sizes suit the TV: about 960 x 540 dp, read from the sofa, so nothing here is smaller than 13 sp.
 */
final class Ui {
    private Ui() { }

    // Warm off-white on a deep warm dark. The matte screen turns near-black muddy, so the dark is a brown-grey.
    static final int BG = 0xFF1D1A18;        // a full screen of our own (Add photos)
    static final int GLASS = 0xEB1F1C1A;     // a card over the photos: dark, slightly see-through
    static final int LINE = 0x24F1EDE4;      // hairline round a card
    static final int INK = 0xFFF1EDE4;       // main text
    static final int SOFT = 0xBFF1EDE4;      // supporting text
    static final int FAINT = 0x80F1EDE4;     // hints, things that are off
    static final int ACCENT = 0xFFE6C08A;    // values and the things to notice
    static final int DARK = 0xFF1D1A18;      // text on a light surface, and the QR's squares
    static final int PAPER = 0xFFFAF7F1;     // light surfaces: the QR tile, text boxes
    static final int FOCUS = 0x2EF1EDE4;     // the pill behind the row the remote is on

    // Type scale, in sp.
    static final float TITLE = 28, ROW = 18, BODY = 17, HINT = 14, LABEL = 13;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.SANS_SERIF);
        v.setIncludeFontPadding(false);
        return v;
    }

    /** A card's or screen's heading, in the serif the frame uses for names. */
    static TextView title(Context c, CharSequence s, float sp) {
        TextView v = text(c, s, sp, INK);
        v.setTypeface(Typeface.SERIF);
        return v;
    }

    /** The small spaced capitals above a title. */
    static TextView eyebrow(Context c, String s) {
        TextView v = text(c, s.toUpperCase(java.util.Locale.UK), LABEL, FAINT);
        v.setLetterSpacing(0.16f);
        return v;
    }

    /** A rounded shape in one colour. */
    static GradientDrawable shape(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** The dark glass card. A plain see-through colour: blur would cost too much on this TV's processor. */
    static GradientDrawable card(Context c) {
        GradientDrawable d = shape(c, GLASS, 22);
        d.setStroke(Math.max(1, dp(c, 1)), LINE);
        return d;
    }

    /** Makes a short message a small glass pill (the toasts and corner notes over the photos). */
    static void pill(TextView v) {
        Context c = v.getContext();
        v.setBackground(shape(c, GLASS, 20));
        v.setPadding(dp(c, 20), dp(c, 9), dp(c, 20), dp(c, 10));
        v.setIncludeFontPadding(false);
    }

    /** A fixed-height gap. (A bare View with WRAP_CONTENT takes all the space it is offered.) */
    static View gap(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }

    /** Marks the row the remote is on: a soft pill behind it, and it grows a touch. */
    static void focus(View row, boolean on) {
        row.setBackground(on ? shape(row.getContext(), FOCUS, 12) : null);
        float s = on ? 1.025f : 1f;
        // Always aim at the target: comparing getScaleX() missed a grow that hadn't started yet when the
        // remote moved on quickly, so passed rows stayed enlarged (seen on the TV, 2026-11-22).
        row.animate().cancel();
        row.animate().scaleX(s).scaleY(s).setDuration(140);
    }

    /** A card or screen arriving: fades in and rises a little. */
    static void fadeIn(View v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 14));
        v.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator());
    }

    /** A value with the ◀ ▶ that say it can change, drawn small and quiet beside it. */
    static CharSequence arrows(CharSequence value, boolean left, boolean right) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (left) quiet(b, "◀   ");
        b.append(value);
        if (right) quiet(b, "   ▶");
        return b;
    }

    private static void quiet(SpannableStringBuilder b, String s) {
        int at = b.length();
        b.append(s);
        b.setSpan(new RelativeSizeSpan(0.62f), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(SOFT), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * The remote's hints as a quiet row: keys("▲▼", "Move", "Back", "Close") draws each key in a small
     * outlined cap with what it does beside it.
     */
    static LinearLayout keys(Context c, String... keyThenWhat) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i + 1 < keyThenWhat.length; i += 2) {
            TextView key = text(c, keyThenWhat[i], LABEL, SOFT);
            GradientDrawable cap = shape(c, 0x00000000, 7);
            cap.setStroke(Math.max(1, dp(c, 1)), 0x59F1EDE4);
            key.setBackground(cap);
            key.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) kp.leftMargin = dp(c, 20);
            row.addView(key, kp);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
            wp.leftMargin = dp(c, 8);
            row.addView(text(c, keyThenWhat[i + 1], HINT, FAINT), wp);
        }
        return row;
    }

    /** One numbered step: the number in a ring, the words beside it. */
    static LinearLayout step(Context c, int number, CharSequence words) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = text(c, String.valueOf(number), 15, ACCENT);
        n.setGravity(Gravity.CENTER);
        n.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        GradientDrawable ring = shape(c, 0x00000000, 15);
        ring.setStroke(Math.max(2, dp(c, 1.5f)), 0x99E6C08A);
        n.setBackground(ring);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30));
        np.rightMargin = dp(c, 14);
        row.addView(n, np);
        TextView t = text(c, words, 19, INK);
        t.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.topMargin = dp(c, 3);   // lines the first line of words up with the number
        row.addView(t, tp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(c, 14);
        row.setLayoutParams(rp);
        return row;
    }

    /**
     * The clean light tile a QR code sits on. The code is made in DARK on PAPER so its own quiet border
     * runs into the tile, which adds more quiet space around it: easier for a phone camera across the room.
     */
    static FrameLayout qrTile(Context c, ImageView qr, int codeDp) {
        FrameLayout tile = new FrameLayout(c);
        tile.setBackground(shape(c, PAPER, 20));
        int pad = dp(c, 14);
        tile.setPadding(pad, pad, pad, pad);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(qr, new FrameLayout.LayoutParams(dp(c, codeDp), dp(c, codeDp)));
        return tile;
    }

    /** A light text box for the TV keyboard (the names of trips). */
    static void field(android.widget.EditText f) {
        Context c = f.getContext();
        f.setBackground(shape(c, PAPER, 12));
        f.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        f.setTextColor(DARK);
        f.setHintTextColor(0x801D1A18);
        f.setTextSize(22);
    }
}
