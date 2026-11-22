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
 * Settings → Albums to play: date trips and membership albums in one list (the owner thinks of them all as
 * albums, 2026-11-22). "All albums" or any number ticked (mixed together).
 * ▲▼ move, OK ticks/unticks, hold OK renames, Back saves and closes.
 * Ones cleared to make room are listed greyed as "removed to make room" (pick them again to bring them back).
 * The choice is saved as "start~end" for a date trip and "album:<id>" for an album, joined by ";".
 */
final class TripPanel extends LinearLayout {
    interface Host {
        List<String[]> tripRows();          // {start, end, label, count, "gone"|"", key, "album"|"trip"}
        void tripsChosen(String chosen);    // "start~end;album:<id>;..." or "" for all
        void renameTrip(String key, String currentLabel);
        void closeTrips();
    }

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
        setPadding(dp(28), dp(22), dp(28), dp(20));
        setBackground(Ui.card(ctx));
        addView(Ui.title(ctx, "Albums to play", Ui.TITLE));
        TextView sub = Ui.text(ctx, "Ticked albums play together.", Ui.HINT, Ui.SOFT);
        sub.setPadding(0, dp(5), 0, 0);
        addView(sub);
        addView(Ui.gap(ctx, 10));
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);   // rows fade out at the top and bottom: there is more to scroll to
        scroll.setFadingEdgeLength(dp(28));
        list = new LinearLayout(ctx);
        list.setOrientation(VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));   // room for the focused row to grow without being cut off
        scroll.addView(list);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(350)));
        addView(Ui.gap(ctx, 12));
        addView(Ui.keys(ctx, "▲▼", "Move", "OK", "Tick", "Hold OK", "Rename", "Back", "Done"));
    }

    int cardWidth() { return dp(620); }

    /** Every way of showing the card (open, coming back from another card) fades it in. */
    @Override public void setVisibility(int visibility) {
        boolean was = getVisibility() == VISIBLE;
        super.setVisibility(visibility);
        if (visibility == VISIBLE && !was) Ui.fadeIn(this);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Opens with the current choice ("" = all). */
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
            for (int i = 0; i < trips.size(); i++) if (trips.get(i)[5].equals(t[5]) && chosen.get(i)) c = true;
            keep.add(c);
        }
        trips = fresh;
        chosen.clear();
        chosen.addAll(keep);
        build();
        refresh();
    }

    boolean onKey(int code, boolean longPress) {
        int n = trips.size() + 1;   // row 0 = All albums
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: focus = Math.max(0, focus - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: focus = Math.min(n - 1, focus + 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (focus == 0) {
                    for (int i = 0; i < chosen.size(); i++) chosen.set(i, false);
                } else if (longPress) {
                    String[] t = trips.get(focus - 1);
                    host.renameTrip(t[5], t[2]);
                    return true;
                } else if (!"gone".equals(trips.get(focus - 1)[4])) {
                    chosen.set(focus - 1, !chosen.get(focus - 1));
                }
                break;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU:
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < trips.size(); i++)
                    if (chosen.get(i)) sb.append(sb.length() == 0 ? "" : ";").append(trips.get(i)[5]);
                host.tripsChosen(sb.toString());
                host.closeTrips();
                return true;
        }
        refresh();
        return true;
    }

    /** Albums match by id; date trips by overlapping dates, so a trip that grew still counts as ticked. */
    private static boolean contains(String chosen, String[] row) {
        String id = AlbumStore.albumId(row[5]);
        return id != null ? AlbumStore.chosenAlbum(chosen, id) : AlbumStore.chosenRange(chosen, row[0], row[1]);
    }

    private void build() {
        list.removeAllViews();
        rows.clear();
        ticks.clear();
        addRow("All albums", "", false);
        for (String[] t : trips) addRow(t[2], "gone".equals(t[4]) ? "removed to make room" : t[3] + " photos", "gone".equals(t[4]));
    }

    private void addRow(String label, String info, boolean gone) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(14), dp(9));
        TextView tick = Ui.text(getContext(), "", 13, Ui.DARK);
        tick.setGravity(Gravity.CENTER);
        tick.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        LayoutParams tp = new LayoutParams(dp(24), dp(24));
        tp.rightMargin = dp(14);
        row.addView(tick, tp);
        row.addView(Ui.text(getContext(), label, Ui.ROW, gone ? Ui.FAINT : Ui.INK), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(getContext(), info, 15, gone ? Ui.FAINT : Ui.SOFT));
        rows.add(row);
        ticks.add(tick);
        list.addView(row);
    }

    private void refresh() {
        boolean any = false;
        for (boolean c : chosen) any |= c;
        tick(ticks.get(0), !any);
        for (int i = 0; i < trips.size(); i++) tick(ticks.get(i + 1), chosen.get(i));
        for (int i = 0; i < rows.size(); i++) Ui.focus(rows.get(i), i == focus);
        View f = rows.get(focus);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, f.getTop() - dp(120))));
    }

    /** A round tick box: filled, with a tick, when on; an empty ring when off. */
    private void tick(TextView box, boolean on) {
        box.setText(on ? "✓" : "");
        GradientDrawable d = Ui.shape(getContext(), on ? Ui.ACCENT : 0x00000000, 12);
        if (!on) d.setStroke(Math.max(2, dp(1)), Ui.FAINT);
        box.setBackground(d);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
