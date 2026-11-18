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
        void openTrips();
        java.util.List<String[]> tripRows();   // {start, end, label, count, "gone"|""}
        java.util.List<String[]> days();   // {yyyy:MM:dd, count}, oldest first
    }

    private static final int INK = 0xFFF1EDE4, SOFT = 0xB3F1EDE4, ACCENT = 0xFFE8C9A0;
    private static final int ROW_TRIP = 0, ROW_INTERVAL = 1, ROW_ART = 2, ROW_SURFACE = 3, ROW_LOOK = 4,
            ROW_ORDER = 5, ROW_DAY = 6, ROW_PLAY = 7, ROW_SPACE = 8, ROW_HIDDEN = 9, ROW_ADD = 10, ROWS = 11;

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
        setPadding(pad, dp(18), pad, dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21E1C1A);              // a shade darker than the wall so the card stands out
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), 0x40F1EDE4);
        setBackground(bg);

        TextView title = text("Settings", 24, INK, Typeface.SERIF);
        addView(title);
        addView(space(8));
        String[] names = {"Trip", "Change photo every", "Print filter", "Print surface", "Look", "Order", "   Day",
                "Play", "Space for photos", "Hidden photos", "Add photos…"};
        for (int i = 0; i < ROWS; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(5), dp(14), dp(5));
            labels[i] = text(names[i], 17, INK, Typeface.SANS_SERIF);
            values[i] = text("", 17, ACCENT, Typeface.SANS_SERIF);
            values[i].setGravity(Gravity.END);
            row.addView(labels[i], new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.addView(values[i], new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            rows[i] = row;
            addView(row);
        }
        addView(space(8));
        addView(text("▲▼ choose   ·   ◀ ▶ change   ·   Back to close", 14, SOFT, Typeface.SANS_SERIF));
        refresh();
    }

    /** The card's fixed width; the frame stays visible around it. */
    int cardWidth() { return dp(560); }

    void open() { focus = ROW_TRIP; refresh(); setVisibility(VISIBLE); }

    /** Re-reads values (after the Trip chooser closes). */
    void update() { refresh(); }
    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Handles a remote key while the page is open. Always consumes it. */
    boolean onKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: do focus = (focus + ROWS - 1) % ROWS; while (!shown(focus)); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: do focus = (focus + 1) % ROWS; while (!shown(focus)); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: change(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: change(+1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (focus == ROW_ADD) host.addPhotos();
                else if (focus == ROW_TRIP) host.openTrips();
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
        } else if (focus == ROW_TRIP) {
            host.openTrips();
            return;
        } else if (focus == ROW_SPACE) {
            int n = Settings.SPACE_GB.length;
            settings.setSpace(Math.max(0, Math.min(n - 1, settings.space() + dir)));
        } else if (focus == ROW_ORDER) {
            int n = Settings.ORDER_NAMES.length;
            settings.setOrder(Math.max(0, Math.min(n - 1, settings.order() + dir)));
        } else if (focus == ROW_DAY) {
            java.util.List<String[]> days = host.days();
            int i = dayIndex(days) + dir;                 // 0 = Any day, 1.. = the days, oldest first
            i = Math.max(0, Math.min(days.size(), i));
            settings.setDay(i == 0 ? "" : days.get(i - 1)[0]);
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
        values[ROW_TRIP].setText(tripSummary() + (focus == ROW_TRIP ? "  ·  OK" : ""));
        values[ROW_SPACE].setText(arrows(Settings.SPACE_GB[settings.space()] + " GB", settings.space(), Settings.SPACE_GB.length));
        values[ROW_ORDER].setText(arrows(Settings.ORDER_NAMES[settings.order()], settings.order(), Settings.ORDER_NAMES.length));
        rows[ROW_DAY].setVisibility(shown(ROW_DAY) ? VISIBLE : GONE);
        if (shown(ROW_DAY)) {
            java.util.List<String[]> days = host.days();
            int i = dayIndex(days);
            values[ROW_DAY].setText(arrows(i == 0 ? "Any day" : dayLabel(days.get(i - 1)), i, days.size() + 1));
        }
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

    /** "All trips", the one chosen trip's name, or "3 trips mixed". */
    private String tripSummary() {
        String chosen = settings.trips();
        if (chosen.isEmpty()) return "All trips";
        String[] ranges = chosen.split(";");
        if (ranges.length > 1) return ranges.length + " trips mixed";
        String[] r = ranges[0].split("~");
        for (String[] t : host.tripRows())
            if (r.length == 2 && Trips.overlap(t[0], t[1], r[0], r[1]) > 0) return t[2].replace("  ·  ", " · ");
        return "1 trip";
    }

    private boolean shown(int row) { return row != ROW_DAY || settings.order() == Settings.ORDER_DAY; }

    /** 0 for Any day (or a day that no longer has photos), else 1 + its position in the list. */
    private int dayIndex(java.util.List<String[]> days) {
        String d = settings.day();
        for (int i = 0; i < days.size(); i++) if (days.get(i)[0].equals(d)) return i + 1;
        return 0;
    }

    /** "2026:10:11" + count → "Sat 11 Oct · 96 photos". */
    private static String dayLabel(String[] day) {
        String label = day[0];
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy:MM:dd", java.util.Locale.US);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK);
            label = out.format(in.parse(day[0]));
        } catch (java.text.ParseException ignored) { }
        return label + "  ·  " + day[1] + " photos";
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
