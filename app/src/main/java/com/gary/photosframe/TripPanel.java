package com.gary.photosframe;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings → Trip: choose which trips play. "All trips" or any number of ticked trips (mixed together).
 * ▲▼ move, OK ticks/unticks, hold OK renames, Back saves and closes.
 * Trips cleared to make room are listed greyed as "not on the TV" (pick them again to bring them back).
 */
final class TripPanel extends LinearLayout {
    interface Host {
        List<String[]> tripRows();          // {start, end, label, count, "gone"|""}
        void tripsChosen(String chosen);    // "start~end;..." or "" for all
        void renameTrip(String start, String end, String currentLabel);
        void closeTrips();
    }

    private static final int INK = 0xFFF1EDE4, SOFT = 0xB3F1EDE4, DIM = 0x66F1EDE4, ACCENT = 0xFFE8C9A0;

    private final Host host;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final List<LinearLayout> rows = new ArrayList<>();
    private final List<TextView> ticks = new ArrayList<>();
    private List<String[]> trips = new ArrayList<>();
    private final List<Boolean> chosen = new ArrayList<>();
    private int focus;

    TripPanel(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(dp(28), dp(24), dp(28), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21E1C1A);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), 0x40F1EDE4);
        setBackground(bg);
        addView(text("Trips to play", 26, INK, Typeface.SERIF));
        View gap = new View(ctx);
        addView(gap, new LayoutParams(1, dp(8)));
        scroll = new ScrollView(ctx);
        list = new LinearLayout(ctx);
        list.setOrientation(VERTICAL);
        scroll.addView(list);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(380)));
        View gap2 = new View(ctx);
        addView(gap2, new LayoutParams(1, dp(8)));
        addView(text("▲▼ choose   ·   OK tick / untick (ticked trips mix)   ·   hold OK to rename   ·   Back to finish",
                13, SOFT, Typeface.SANS_SERIF));
    }

    int cardWidth() { return dp(620); }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Opens with the current choice ("" = all trips). */
    void open(String current) {
        trips = host.tripRows();
        chosen.clear();
        for (String[] t : trips) chosen.add(!current.isEmpty() && contains(current, t));
        build();
        focus = 0;
        setVisibility(VISIBLE);
        refresh();
    }

    /** Re-reads labels (after a rename) keeping the ticks. */
    void reload() {
        List<String[]> fresh = host.tripRows();
        List<Boolean> keep = new ArrayList<>();
        for (String[] t : fresh) {
            boolean c = false;
            for (int i = 0; i < trips.size(); i++) if (trips.get(i)[0].equals(t[0]) && chosen.get(i)) c = true;
            keep.add(c);
        }
        trips = fresh;
        chosen.clear();
        chosen.addAll(keep);
        build();
        refresh();
    }

    boolean onKey(int code, boolean longPress) {
        int n = trips.size() + 1;   // row 0 = All trips
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: focus = Math.max(0, focus - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: focus = Math.min(n - 1, focus + 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (focus == 0) {
                    for (int i = 0; i < chosen.size(); i++) chosen.set(i, false);
                } else if (longPress) {
                    String[] t = trips.get(focus - 1);
                    host.renameTrip(t[0], t[1], t[2]);
                    return true;
                } else if (!"gone".equals(trips.get(focus - 1)[4])) {
                    chosen.set(focus - 1, !chosen.get(focus - 1));
                }
                break;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU:
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < trips.size(); i++)
                    if (chosen.get(i)) sb.append(sb.length() == 0 ? "" : ";").append(trips.get(i)[0]).append('~').append(trips.get(i)[1]);
                host.tripsChosen(sb.toString());
                host.closeTrips();
                return true;
        }
        refresh();
        return true;
    }

    private static boolean contains(String chosen, String[] trip) {
        for (String range : chosen.split(";")) {
            String[] r = range.split("~");
            if (r.length == 2 && Trips.overlap(trip[0], trip[1], r[0], r[1]) > 0) return true;
        }
        return false;
    }

    private void build() {
        list.removeAllViews();
        rows.clear();
        ticks.clear();
        addRow("All trips", "", false);
        for (String[] t : trips) addRow(t[2], "gone".equals(t[4]) ? "not on the TV" : t[3] + " photos", "gone".equals(t[4]));
    }

    private void addRow(String label, String info, boolean gone) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(12), dp(8));
        TextView tick = text("", 18, ACCENT, Typeface.SANS_SERIF);
        row.addView(tick, new LayoutParams(dp(30), LayoutParams.WRAP_CONTENT));
        row.addView(text(label, 18, gone ? DIM : INK, Typeface.SANS_SERIF), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text(info, 15, gone ? DIM : SOFT, Typeface.SANS_SERIF));
        rows.add(row);
        ticks.add(tick);
        list.addView(row);
    }

    private void refresh() {
        boolean any = false;
        for (boolean c : chosen) any |= c;
        ticks.get(0).setText(any ? "" : "✓");
        for (int i = 0; i < trips.size(); i++) ticks.get(i + 1).setText(chosen.get(i) ? "✓" : "");
        for (int i = 0; i < rows.size(); i++) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(8));
            d.setColor(i == focus ? 0x33F1EDE4 : 0x00000000);
            rows.get(i).setBackground(d);
        }
        View f = rows.get(focus);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, f.getTop() - dp(120))));
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
