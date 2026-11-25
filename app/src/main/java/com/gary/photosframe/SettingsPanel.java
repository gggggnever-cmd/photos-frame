package com.gary.photosframe;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
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
        int placeCount(); int[] checkProgress();   // Places only: how many; {checked, total} photos
        void openTrips();
        void youtubeCorner();   // shows the phone code for the YouTube corner
        java.util.List<String[]> tripRows();   // {start, end, label, count, "gone"|"", key, "album"|"trip"}
        java.util.List<String[]> days();   // {yyyy:MM:dd, count}, oldest first
    }

    private static final int ROW_TRIP = 0, ROW_INTERVAL = 1, ROW_ART = 2, ROW_SURFACE = 3, ROW_LOOK = 4,
            ROW_ORDER = 5, ROW_DAY = 6, ROW_PLAY = 7, ROW_HIGHLIGHTS = 8, ROW_SPACE = 9, ROW_HIDDEN = 10,
            ROW_ADD = 11, ROW_YOUTUBE = 12, ROW_AUTO_OFF = 13, ROW_TV_PIC = 14, ROWS = 15;

    private final Settings settings;
    private final Host host;
    private final TextView[] labels = new TextView[ROWS], values = new TextView[ROWS];
    private TextView collageNote;
    private final LinearLayout[] rows = new LinearLayout[ROWS];
    private int focus = ROW_INTERVAL;

    SettingsPanel(Context ctx, Settings settings, Host host) {
        super(ctx);
        this.settings = settings;
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(dp(30), dp(18), dp(30), dp(16));
        setBackground(Ui.card(ctx));

        // The title and the remote's keys share one line: up to 13 rows leave little height to spare.
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.title(ctx, "Settings", 26), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.keys(ctx, "▲▼", "Move", "◀ ▶", "Change", "Back", "Close"));
        addView(head);
        addView(Ui.gap(ctx, 8));
        String[] names = {"Albums to play", "Change photo every", "Print look", "Style", "Picture size", "Photo order", "   Which day",
                "Photos to show", "   Best photos per trip", "Space for photos", "Hidden photos", "Add more photos", "YouTube corner", "TV off if remote unused", "TV picture for photos"};
        for (int i = 0; i < ROWS; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(6), dp(14), dp(6));
            labels[i] = Ui.text(ctx, names[i], 17, Ui.INK);
            values[i] = Ui.text(ctx, "", 17, Ui.SOFT);
            values[i].setGravity(Gravity.END);
            row.addView(labels[i], new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.addView(values[i], new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            rows[i] = row;
            addView(row);
        }
        collageNote = Ui.text(ctx, "Cut-out collage rebuilds the photo from flat colours, like layered paper. Some detail is lost.",
                Ui.HINT, Ui.SOFT);
        collageNote.setPadding(dp(14), dp(8), dp(14), 0);
        addView(collageNote);
        refresh();
    }

    /** The card's fixed width; the frame stays visible around it. */
    int cardWidth() { return dp(600); }

    /** Every way of showing the card (open, coming back from another card) fades it in. */
    @Override public void setVisibility(int visibility) {
        boolean was = getVisibility() == VISIBLE;
        super.setVisibility(visibility);
        if (visibility == VISIBLE && !was) Ui.fadeIn(this);
    }

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
                else if (focus == ROW_YOUTUBE) host.youtubeCorner();
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
            int n = Settings.INTERVAL_SECONDS.length;   // no wrap: 5 seconds … 1 hour
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
            int n = Settings.PLAY_NAMES.length;
            settings.setPlay(Math.max(0, Math.min(n - 1, settings.play() + dir)));
        } else if (focus == ROW_HIGHLIGHTS) {
            int n = Highlights.COUNTS.length;
            settings.setHighlightsIndex(Math.max(0, Math.min(n - 1, settings.highlightsIndex() + dir)));
        } else if (focus == ROW_AUTO_OFF) {
            int n = Settings.AUTO_OFF_MINUTES.length;
            settings.setAutoOff(Math.max(0, Math.min(n - 1, settings.autoOff() + dir)));
            return;   // nothing on screen changes
        } else if (focus == ROW_TV_PIC) {
            settings.setTvPicture(Math.max(0, Math.min(Settings.TV_PIC_COUNT - 1, settings.tvPicture() + dir)));
            TvPicture.frameOn(getContext());   // the TV's picture changes at once
            return;
        } else if (focus == ROW_LOOK) {
            int n = Settings.LOOK_NAMES.length;
            settings.setLook(Math.max(0, Math.min(n - 1, settings.look() + dir)));
        } else {
            return;
        }
        host.settingsChanged();
    }

    private void refresh() {
        values[ROW_INTERVAL].setText(arrows(ROW_INTERVAL, Settings.intervalName(settings.interval()),
                settings.interval(), Settings.INTERVAL_SECONDS.length));
        values[ROW_ART].setText(arrows(ROW_ART, settings.artName(), settings.artLevel(), Settings.ART_STRENGTHS.length));
        String style = settings.artLevel() == 0 ? "Off (Print look is off)" : Settings.SURFACE_NAMES[settings.surface()];
        values[ROW_SURFACE].setText(arrows(ROW_SURFACE, style, settings.surface(), Settings.SURFACE_NAMES.length));
        if (collageNote != null) collageNote.setVisibility(settings.artLevel() > 0 && settings.surface() == Settings.SURFACE_CUT ? VISIBLE : GONE);
        values[ROW_LOOK].setText(arrows(ROW_LOOK, Settings.LOOK_NAMES[settings.look()], settings.look(), Settings.LOOK_NAMES.length));
        values[ROW_TRIP].setText(tripSummary() + (focus == ROW_TRIP ? "  ·  OK to choose" : ""));
        values[ROW_SPACE].setText(arrows(ROW_SPACE, Settings.SPACE_GB[settings.space()] + " GB", settings.space(), Settings.SPACE_GB.length));
        values[ROW_ORDER].setText(arrows(ROW_ORDER, Settings.ORDER_NAMES[settings.order()], settings.order(), Settings.ORDER_NAMES.length));
        rows[ROW_DAY].setVisibility(shown(ROW_DAY) ? VISIBLE : GONE);
        if (shown(ROW_DAY)) {
            java.util.List<String[]> days = host.days();
            int i = dayIndex(days);
            values[ROW_DAY].setText(arrows(ROW_DAY, i == 0 ? "Any day" : dayLabel(days.get(i - 1)), i, days.size() + 1));
        }
        int p = settings.play();
        String play = Settings.PLAY_NAMES[p];
        if (p == Settings.PLAY_FAV) play += host.favouriteCount() == 0 ? " (none picked yet)" : " (" + host.favouriteCount() + ")";
        if (p == Settings.PLAY_PLACES || p == Settings.PLAY_HIGHLIGHTS) {
            int[] c = host.checkProgress();
            if (c[1] > 0 && c[0] < c[1]) play += " (sorting " + (100 * c[0] / c[1]) + "%)";
            else if (p == Settings.PLAY_PLACES) play += " (" + host.placeCount() + ")";
        }
        values[ROW_PLAY].setText(arrows(ROW_PLAY, play, p, Settings.PLAY_NAMES.length));
        for (int r : new int[]{ROW_ORDER, ROW_DAY, ROW_HIGHLIGHTS}) rows[r].setVisibility(shown(r) ? VISIBLE : GONE);
        values[ROW_HIGHLIGHTS].setText(arrows(ROW_HIGHLIGHTS, String.valueOf(settings.highlightCount()), settings.highlightsIndex(), Highlights.COUNTS.length));
        int hidden = host.hiddenCount();
        values[ROW_HIDDEN].setText(hidden == 0 ? "none" : hidden + (focus == ROW_HIDDEN ? "  ·  OK brings them back" : ""));
        values[ROW_ADD].setText(focus == ROW_ADD ? "press OK" : "");
        values[ROW_AUTO_OFF].setText(arrows(ROW_AUTO_OFF, Settings.autoOffName(settings.autoOff()), settings.autoOff(), Settings.AUTO_OFF_MINUTES.length));
        values[ROW_TV_PIC].setText(arrows(ROW_TV_PIC, Settings.tvPictureName(settings.tvPicture()), settings.tvPicture(), Settings.TV_PIC_COUNT));
        values[ROW_YOUTUBE].setText(focus == ROW_YOUTUBE ? "OK for the phone code" : "");
        // The row the remote is on: a soft pill, a touch larger, and its value lit in the accent colour.
        for (int i = 0; i < ROWS; i++) {
            Ui.focus(rows[i], i == focus);
            values[i].setTextColor(i == focus ? Ui.ACCENT : Ui.SOFT);
        }
    }

    /** "All albums", the one chosen album's or trip's name, or "3 albums together". */
    private String tripSummary() {
        String chosen = settings.trips();
        if (chosen.isEmpty()) return "All albums";
        String[] tokens = AlbumStore.tokens(chosen);
        if (tokens.length > 1) return tokens.length + " albums together";
        for (String[] t : host.tripRows()) {
            String id = AlbumStore.albumId(t[5]);
            boolean match = id != null ? AlbumStore.chosenAlbum(chosen, id) : AlbumStore.chosenRange(chosen, t[0], t[1]);
            if (match) return t[2].replace("  ·  ", " · ");
        }
        return "1 album";
    }

    /** Day shows only in One day at a time; Highlights play as a story, so Order and Day give way to the count. */
    private boolean shown(int row) {
        boolean high = settings.play() == Settings.PLAY_HIGHLIGHTS;
        if (row == ROW_HIGHLIGHTS) return high;
        if (row == ROW_ORDER) return !high;
        if (row == ROW_DAY) return !high && settings.order() == Settings.ORDER_DAY;
        return true;
    }

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

    /** The value; on the row the remote is on it also gets the ◀ ▶ that say which ways it can still go. */
    private CharSequence arrows(int row, String v, int i, int n) {
        return row == focus ? Ui.arrows(v, i > 0, i < n - 1) : v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
