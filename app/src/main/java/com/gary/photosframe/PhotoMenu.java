package com.gary.photosframe;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The small menu opened by holding OK on the frame: favourite or hide the photo on screen.
 * With 2-3 photos side by side, ◀ ▶ chooses which one (it is outlined on screen).
 * ▲▼ choose an action, OK does it, Back closes.
 */
final class PhotoMenu extends LinearLayout {
    interface Host {
        boolean isFavourite(String id);
        void favourite(String id);
        void hide(String id);
        void highlight(int index);
        String tripLabel(String id);        // "Nov 2025 · 12 days", or null
        void renameTripOf(String id);
        void closeMenu();
    }

    private static final int INK = 0xFFF1EDE4, SOFT = 0xB3F1EDE4, ACCENT = 0xFFE8C9A0;
    private static final int ROW_PHOTO = 0, ROW_FAV = 1, ROW_HIDE = 2, ROW_TRIP = 3, ROW_CANCEL = 4, ROWS = 5;
    private static final String[] POSITIONS_2 = {"Left", "Right"}, POSITIONS_3 = {"Left", "Middle", "Right"};

    private final Host host;
    private final LinearLayout[] rows = new LinearLayout[ROWS];
    private final TextView[] values = new TextView[ROWS];
    private final TextView hint;
    private List<String> photos;
    private int chosen, focus;

    PhotoMenu(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(dp(28), dp(24), dp(28), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21E1C1A);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), 0x40F1EDE4);
        setBackground(bg);
        addView(text("This photo", 24, INK, Typeface.SERIF));
        View gap = new View(ctx);
        addView(gap, new LayoutParams(1, dp(10)));
        String[] names = {"Photo", "Favourite", "Hide this photo", "Name this trip", "Cancel"};
        for (int i = 0; i < ROWS; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            TextView label = text(names[i], 18, INK, Typeface.SANS_SERIF);
            values[i] = text("", 18, ACCENT, Typeface.SANS_SERIF);
            row.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.addView(values[i], new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            rows[i] = row;
            addView(row);
        }
        View gap2 = new View(ctx);
        addView(gap2, new LayoutParams(1, dp(10)));
        hint = text("", 13, SOFT, Typeface.SANS_SERIF);
        addView(hint);
    }

    int cardWidth() { return dp(420); }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Opens for the photos on screen (left to right). Does nothing if there are none. */
    void open(List<String> onScreen) {
        if (onScreen.isEmpty()) return;
        photos = onScreen;
        chosen = 0;
        focus = ROW_FAV;
        rows[ROW_PHOTO].setVisibility(photos.size() > 1 ? VISIBLE : GONE);
        hint.setText(photos.size() > 1 ? "◀ ▶ which photo   ·   ▲▼ choose   ·   OK" : "▲▼ choose   ·   OK   ·   Back to close");
        setVisibility(VISIBLE);
        refresh();
    }

    void close() {
        setVisibility(GONE);
        host.highlight(-1);
    }

    boolean onKey(int code) {
        int first = photos.size() > 1 ? ROW_PHOTO : ROW_FAV;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: focus = Math.max(first, focus - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: focus = Math.min(ROW_CANCEL, focus + 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (photos.size() > 1) chosen = Math.max(0, chosen - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (photos.size() > 1) chosen = Math.min(photos.size() - 1, chosen + 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                String id = photos.get(chosen);
                if (focus == ROW_FAV) host.favourite(id);
                else if (focus == ROW_HIDE) host.hide(id);
                host.closeMenu();
                if (focus == ROW_TRIP) host.renameTripOf(id);
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU:
                host.closeMenu();
                return true;
        }
        refresh();
        return true;
    }

    private void refresh() {
        String[] pos = photos.size() == 2 ? POSITIONS_2 : POSITIONS_3;
        values[ROW_PHOTO].setText(photos.size() > 1 ? "◀  " + pos[Math.min(chosen, pos.length - 1)] + "  ▶" : "");
        boolean fav = host.isFavourite(photos.get(chosen));
        values[ROW_FAV].setText(fav ? "♥ on (OK to remove)" : "♡ off");
        String trip = host.tripLabel(photos.get(chosen));
        values[ROW_TRIP].setText(trip == null ? "" : trip.replace("  ·  ", " · "));
        for (int i = 0; i < ROWS; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(8));
            d.setColor(i == focus ? 0x33F1EDE4 : 0x00000000);
            rows[i].setBackground(d);
        }
        host.highlight(photos.size() > 1 ? chosen : -1);
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView v = new TextView(getContext());
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(face);
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
