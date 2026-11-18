package com.gary.photosframe;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The on-TV Settings page (▲ on the frame). ▲▼ choose a row, ◀▶ change it, OK on "Add photos" opens it,
 * Back closes. Changes are saved at once and the frame behind updates live.
 */
final class SettingsPanel extends LinearLayout {
    interface Host {
        void settingsChanged(); void addPhotos(); void close();
        int hiddenCount(); int favouriteCount(); void unhideAll();
    }

    private static final int INK = 0xFFF1EDE4, SOFT = 0xB3F1EDE4, ACCENT = 0xFFE8C9A0;
    private static final int ROW_INTERVAL = 0, ROW_ART = 1, ROW_SURFACE = 2, ROW_LOOK = 3, ROW_ORDER = 4,
            ROW_PLAY = 5, ROW_HIDDEN = 6, ROW_ADD = 7, ROWS = 8;

    private final Settings settings;
    private final Host host;
    private final TextView[] labels = new TextView[ROWS], values = new TextView[ROWS];
    private final LinearLayout[] rows = new LinearLayout[ROWS];
    private int focus = ROW_INTERVAL;

    SettingsPanel(Context ctx, Settings settings, Host host) {
        super(ctx);
        this.settings = settings;
        this.host = host;
        setOrientation(VERTICAL);
        int pad = dp(32);
        setPadding(pad, dp(28), pad, dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21E1C1A);              // a shade darker than the wall so the card stands out
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), 0x40F1EDE4);
        setBackground(bg);

        TextView title = text("Settings", 28, INK, Typeface.SERIF);
        addView(title);
        addView(space(14));
        String[] names = {"Change photo every", "Print filter", "Print surface", "Look", "Order", "Play",
                "Hidden photos", "Add photos…"};
        for (int i = 0; i < ROWS; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            labels[i] = text(names[i], 19, INK, Typeface.SANS_SERIF);
            values[i] = text("", 19, ACCENT, Typeface.SANS_SERIF);
            values[i].setGravity(Gravity.END);
            row.addView(labels[i], new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.addView(values[i], new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            rows[i] = row;
            addView(row);
        }
        addView(space(14));
        addView(text("▲▼ choose   ·   ◀ ▶ change   ·   Back to close", 14, SOFT, Typeface.SANS_SERIF));
        refresh();
    }

    /** The card's fixed width; the frame stays visible around it. */
    int cardWidth() { return dp(560); }

    void open() { focus = ROW_INTERVAL; refresh(); setVisibility(VISIBLE); }
    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Handles a remote key while the page is open. Always consumes it. */
    boolean onKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: focus = (focus + ROWS - 1) % ROWS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: focus = (focus + 1) % ROWS; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: change(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: change(+1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (focus == ROW_ADD) host.addPhotos();
                else if (focus == ROW_HIDDEN) { if (host.hiddenCount() > 0) host.unhideAll(); }
                else change(+1);
                break;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU: host.close(); break;
        }
        refresh();
        return true;
    }

    private void change(int dir) {
        if (focus == ROW_INTERVAL) {
            int n = Settings.INTERVAL_SECONDS.length;   // no wrap: 30 seconds … 1 hour
            settings.setInterval(Math.max(0, Math.min(n - 1, settings.interval() + dir)));
        } else if (focus == ROW_ART) {
            int n = Settings.ART_STRENGTHS.length;
            settings.setArtLevel(Math.max(0, Math.min(n - 1, settings.artLevel() + dir)));
        } else if (focus == ROW_SURFACE) {
            int n = Settings.SURFACE_NAMES.length;
            settings.setSurface(Math.max(0, Math.min(n - 1, settings.surface() + dir)));
        } else if (focus == ROW_ORDER) {
            int n = Settings.ORDER_NAMES.length;
            settings.setOrder(Math.max(0, Math.min(n - 1, settings.order() + dir)));
        } else if (focus == ROW_PLAY) {
            settings.setFavouritesOnly(dir > 0);
        } else if (focus == ROW_LOOK) {
            int n = Settings.LOOK_NAMES.length;
            settings.setLook(Math.max(0, Math.min(n - 1, settings.look() + dir)));
        } else {
            return;
        }
        host.settingsChanged();
    }

    private void refresh() {
        values[ROW_INTERVAL].setText(arrows(Settings.intervalName(settings.interval()),
                settings.interval(), Settings.INTERVAL_SECONDS.length));
        values[ROW_ART].setText(arrows(settings.artName(), settings.artLevel(), Settings.ART_STRENGTHS.length));
        values[ROW_SURFACE].setText(arrows(Settings.SURFACE_NAMES[settings.surface()], settings.surface(), Settings.SURFACE_NAMES.length));
        values[ROW_LOOK].setText(arrows(Settings.LOOK_NAMES[settings.look()], settings.look(), Settings.LOOK_NAMES.length));
        values[ROW_ORDER].setText(arrows(Settings.ORDER_NAMES[settings.order()], settings.order(), Settings.ORDER_NAMES.length));
        int fav = settings.favouritesOnly() ? 1 : 0;
        String play = Settings.PLAY_NAMES[fav];
        if (fav == 1) play += host.favouriteCount() == 0 ? " (none yet)" : " (" + host.favouriteCount() + ")";
        values[ROW_PLAY].setText(arrows(play, fav, 2));
        int hidden = host.hiddenCount();
        values[ROW_HIDDEN].setText(hidden == 0 ? "none" : hidden + (focus == ROW_HIDDEN ? "  ·  OK to show again" : ""));
        values[ROW_ADD].setText(focus == ROW_ADD ? "press OK" : "");
        for (int i = 0; i < ROWS; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(8));
            d.setColor(i == focus ? 0x33F1EDE4 : 0x00000000);
            rows[i].setBackground(d);
        }
    }

    private static String arrows(String v, int i, int n) {
        return (i > 0 ? "◀  " : "    ") + v + (i < n - 1 ? "  ▶" : "    ");
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView v = new TextView(getContext());
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(face);
        return v;
    }

    private View space(int h) {
        View v = new View(getContext());
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
