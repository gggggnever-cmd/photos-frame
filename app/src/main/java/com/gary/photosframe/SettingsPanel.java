package com.gary.photosframe;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The on-TV Settings card (▲ on the frame), in two levels (2026-11-25; one list of 17 rows had no order):
 * a short first page of groups, each showing what it is set to, and one page per group.
 * ▲▼ choose a row, OK opens a group or does the row's action, ◀▶ change a value, Back goes up one page,
 * then closes. Changes are saved at once and the frame behind updates live. See docs/settings_redesign.md.
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

    private static final int HOME = 0, PLAYS = 1, LOOKS = 2, TV = 3, PHOTOS = 4;
    private static final String[] PAGE_NAMES = {"Settings", "What plays", "How it looks", "TV screen", "Photos on the TV"};

    // Rows in the order they are drawn. The first four open the page with the same number + 1.
    private static final int GO_PLAYS = 0, GO_LOOKS = 1, GO_TV = 2, GO_PHOTOS = 3, ROW_YOUTUBE = 4,
            ROW_TRIP = 5, ROW_PLAY = 6, ROW_HIGHLIGHTS = 7, ROW_ORDER = 8, ROW_DAY = 9, ROW_INTERVAL = 10,
            ROW_LOOK = 11, ROW_ART = 12, ROW_SURFACE = 13,
            ROW_TV_PIC = 14, ROW_TV_BRIGHT = 15, ROW_TV_COLOUR = 16, ROW_AUTO_OFF = 17,
            ROW_ADD = 18, ROW_HIDDEN = 19, ROW_SPACE = 20, ROWS = 21;
    private static final int[] PAGE_OF = {HOME, HOME, HOME, HOME, HOME,
            PLAYS, PLAYS, PLAYS, PLAYS, PLAYS, PLAYS, LOOKS, LOOKS, LOOKS, TV, TV, TV, TV, PHOTOS, PHOTOS, PHOTOS};
    private static final String[] NAMES = {"What plays", "How it looks", "TV screen", "Photos on the TV", "YouTube corner",
            "Albums to play", "Photos to show", "Best photos per trip", "Photo order", "Which day", "Change photo every",
            "Picture size", "Print look", "Style",
            "TV picture for photos", "Brightness", "Colour", "TV off if remote unused",
            "Add more photos", "Hidden photos", "Space for photos"};
    private static final int MOST_ROWS = 5;   // the longest page; every page is given this height so the card never jumps

    private final Settings settings;
    private final Host host;
    private final TextView[] values = new TextView[ROWS];
    private final LinearLayout[] rows = new LinearLayout[ROWS];
    private final LinearLayout foot, list;
    private final TextView title, collageNote;
    private int page = HOME, focus = GO_PLAYS;

    SettingsPanel(Context ctx, Settings settings, Host host) {
        super(ctx);
        this.settings = settings;
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(dp(28), dp(22), dp(28), dp(20));
        setBackground(Ui.card(ctx));

        title = Ui.title(ctx, "", 26);   // which page this is
        addView(title);
        addView(Ui.gap(ctx, 12));

        list = new LinearLayout(ctx);
        list.setOrientation(VERTICAL);
        for (int i = 0; i < ROWS; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // Rows that depend on the row above (Which day, Brightness…) sit a step in.
            row.setPadding(dp(dependent(i) ? 32 : 14), dp(10), dp(14), dp(10));
            TextView label = Ui.text(ctx, NAMES[i], Ui.ROW, Ui.INK);
            // The first page's values are a summary of a whole group, so they are a size smaller.
            values[i] = Ui.text(ctx, "", i <= GO_PHOTOS ? 15 : Ui.BODY, Ui.SOFT);
            values[i].setGravity(Gravity.END);
            values[i].setSingleLine(true);                       // a long album name is cut with …, never wrapped
            values[i].setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            LayoutParams vp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            vp.leftMargin = dp(20);
            row.addView(values[i], vp);
            rows[i] = row;
            list.addView(row);
        }
        collageNote = Ui.text(ctx, "Cut-out collage rebuilds the photo from flat colours, like layered paper. Some detail is lost.",
                Ui.HINT, Ui.SOFT);
        collageNote.setPadding(dp(14), dp(8), dp(14), 0);
        list.addView(collageNote);
        list.setMinimumHeight(dp(MOST_ROWS * 42));   // a row is about 42 dp: 18 sp of text + 10 dp above and below
        addView(list);
        addView(Ui.gap(ctx, 12));
        // The remote's keys sit under the rows, as on the albums card; they change with the page.
        foot = new LinearLayout(ctx);
        addView(foot);
        show(HOME, GO_PLAYS);
    }

    /** The card's fixed width; the frame stays visible around it. */
    int cardWidth() { return dp(640); }

    /** Every way of showing the card (open, coming back from another card) fades it in. */
    @Override public void setVisibility(int visibility) {
        boolean was = getVisibility() == VISIBLE;
        super.setVisibility(visibility);
        if (visibility == VISIBLE && !was) Ui.fadeIn(this);
    }

    /** Always opens on the first page, on "What plays": the group used most. */
    void open() { show(HOME, GO_PLAYS); setVisibility(VISIBLE); }

    /** Re-reads values (after the Trip chooser closes). */
    void update() { refresh(); }
    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Handles a remote key while the card is open. Always consumes it. held: the key is repeating. */
    boolean onKey(int code, boolean held) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        // A held OK or Back must not run on through the next page (OK opens a page, then would act on its first row).
        if (held && (ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU)) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: do focus = (focus + ROWS - 1) % ROWS; while (!shown(focus)); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: do focus = (focus + 1) % ROWS; while (!shown(focus)); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: change(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focus <= GO_PHOTOS) { show(focus + 1, -1); return true; }   // ▶ opens a group too
                change(+1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (focus <= GO_PHOTOS) { show(focus + 1, -1); return true; }
                else if (focus == ROW_ADD) host.addPhotos();
                else if (focus == ROW_YOUTUBE) host.youtubeCorner();
                else if (focus == ROW_TRIP) host.openTrips();
                else if (focus == ROW_HIDDEN) { if (host.hiddenCount() > 0) host.unhideAll(); }
                else change(+1);
                break;
            case KeyEvent.KEYCODE_BACK:
                if (page == HOME) host.close();
                else { show(HOME, page - 1); return true; }   // up one page, back on the group it came from
                break;
            case KeyEvent.KEYCODE_MENU: host.close(); break;
        }
        refresh();
        return true;
    }

    /** Switches page. focusRow -1 = the page's first row. */
    private void show(int newPage, int focusRow) {
        boolean moved = newPage != page;
        page = newPage;
        focus = focusRow;
        if (focus < 0) for (focus = 0; !shown(focus); focus++) { }
        if (page == HOME) {
            title.setText(PAGE_NAMES[HOME]);
        } else {
            // "Settings › What plays": the quiet first part says where Back leads.
            SpannableStringBuilder b = new SpannableStringBuilder(PAGE_NAMES[HOME] + "  ›  ");
            b.setSpan(new RelativeSizeSpan(0.7f), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(Ui.FAINT), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(PAGE_NAMES[page]);
            title.setText(b);
        }
        foot.removeAllViews();
        foot.addView(page == HOME
                ? Ui.keys(getContext(), "▲▼", "Move", "OK", "Open", "Back", "Close")
                : Ui.keys(getContext(), "▲▼", "Move", "◀ ▶", "Change", "Back", "Settings"));
        refresh();
        if (moved && isOpen()) Ui.fadeIn(list);
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
        } else if (focus == ROW_TV_PIC || focus == ROW_TV_BRIGHT || focus == ROW_TV_COLOUR) {
            if (focus == ROW_TV_PIC) settings.setTvPicture(Math.max(0, Math.min(Settings.TV_PIC_NAMES.length - 1, settings.tvPicture() + dir)));
            else if (focus == ROW_TV_BRIGHT) settings.setTvBrightness(Math.max(0, Math.min(Settings.TV_BRIGHTNESS.length - 1, settings.tvBrightness() + dir)));
            else settings.setTvColour(Math.max(0, Math.min(Settings.TV_COLOUR_NAMES.length - 1, settings.tvColour() + dir)));
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
        for (int i = 0; i < ROWS; i++) rows[i].setVisibility(shown(i) ? VISIBLE : GONE);
        collageNote.setVisibility(page == LOOKS && settings.artLevel() > 0 && settings.surface() == Settings.SURFACE_CUT ? VISIBLE : GONE);
        if (page == HOME) {
            // Each group says what it is set to, so most questions are answered without opening anything.
            values[GO_PLAYS].setText(tripSummary() + ",  " + Settings.PLAY_NAMES[settings.play()] + ",  " + Settings.intervalName(settings.interval()));
            values[GO_LOOKS].setText(Settings.LOOK_NAMES[settings.look()] + ",  print look " + settings.artName());
            values[GO_TV].setText(tvSummary());
            int hidden = host.hiddenCount();
            values[GO_PHOTOS].setText((hidden > 0 ? hidden + " hidden,  " : "") + "space " + Settings.SPACE_GB[settings.space()] + " GB");
            values[ROW_YOUTUBE].setText(focus == ROW_YOUTUBE ? "OK for the phone code" : "");
        } else if (page == PLAYS) {
            values[ROW_TRIP].setText(tripSummary() + (focus == ROW_TRIP ? "  ·  OK to choose" : ""));
            int p = settings.play();
            String play = Settings.PLAY_NAMES[p];
            if (p == Settings.PLAY_FAV) play += host.favouriteCount() == 0 ? " (none picked yet)" : " (" + host.favouriteCount() + ")";
            if (p == Settings.PLAY_PLACES || p == Settings.PLAY_HIGHLIGHTS) {
                int[] c = host.checkProgress();
                if (c[1] > 0 && c[0] < c[1]) play += " (sorting " + (100 * c[0] / c[1]) + "%)";
                else if (p == Settings.PLAY_PLACES) play += " (" + host.placeCount() + ")";
            }
            values[ROW_PLAY].setText(arrows(ROW_PLAY, play, p, Settings.PLAY_NAMES.length));
            values[ROW_HIGHLIGHTS].setText(arrows(ROW_HIGHLIGHTS, String.valueOf(settings.highlightCount()), settings.highlightsIndex(), Highlights.COUNTS.length));
            values[ROW_ORDER].setText(arrows(ROW_ORDER, Settings.ORDER_NAMES[settings.order()], settings.order(), Settings.ORDER_NAMES.length));
            if (shown(ROW_DAY)) {
                java.util.List<String[]> days = host.days();
                int i = dayIndex(days);
                values[ROW_DAY].setText(arrows(ROW_DAY, i == 0 ? "Any day" : dayLabel(days.get(i - 1)), i, days.size() + 1));
            }
            values[ROW_INTERVAL].setText(arrows(ROW_INTERVAL, Settings.intervalName(settings.interval()),
                    settings.interval(), Settings.INTERVAL_SECONDS.length));
        } else if (page == LOOKS) {
            values[ROW_LOOK].setText(arrows(ROW_LOOK, Settings.LOOK_NAMES[settings.look()], settings.look(), Settings.LOOK_NAMES.length));
            values[ROW_ART].setText(arrows(ROW_ART, settings.artName(), settings.artLevel(), Settings.ART_STRENGTHS.length));
            String style = settings.artLevel() == 0 ? "Off (Print look is off)" : Settings.SURFACE_NAMES[settings.surface()];
            values[ROW_SURFACE].setText(arrows(ROW_SURFACE, style, settings.surface(), Settings.SURFACE_NAMES.length));
        } else if (page == TV) {
            values[ROW_TV_PIC].setText(arrows(ROW_TV_PIC, Settings.TV_PIC_NAMES[settings.tvPicture()], settings.tvPicture(), Settings.TV_PIC_NAMES.length));
            values[ROW_TV_BRIGHT].setText(arrows(ROW_TV_BRIGHT, Settings.tvBrightnessName(settings.tvBrightness()), settings.tvBrightness(), Settings.TV_BRIGHTNESS.length));
            values[ROW_TV_COLOUR].setText(arrows(ROW_TV_COLOUR, Settings.TV_COLOUR_NAMES[settings.tvColour()], settings.tvColour(), Settings.TV_COLOUR_NAMES.length));
            values[ROW_AUTO_OFF].setText(arrows(ROW_AUTO_OFF, Settings.autoOffName(settings.autoOff()), settings.autoOff(), Settings.AUTO_OFF_MINUTES.length));
        } else {
            values[ROW_ADD].setText(focus == ROW_ADD ? "press OK" : "");
            int hidden = host.hiddenCount();
            values[ROW_HIDDEN].setText(hidden == 0 ? "none" : hidden + (focus == ROW_HIDDEN ? "  ·  OK brings them back" : ""));
            values[ROW_SPACE].setText(arrows(ROW_SPACE, Settings.SPACE_GB[settings.space()] + " GB", settings.space(), Settings.SPACE_GB.length));
        }
        // The row the remote is on: a soft pill, a touch larger, and its value lit in the accent colour.
        for (int i = 0; i < ROWS; i++) {
            Ui.focus(rows[i], i == focus);
            values[i].setTextColor(i == focus ? Ui.ACCENT : Ui.SOFT);
        }
    }

    /** "Brightness Auto (now -40),  Warm" plus the auto-off time when one is set; "Left as is" without the preset. */
    private String tvSummary() {
        String s = settings.tvPicture() == Settings.TV_PIC_ON
                ? "Brightness " + Settings.tvBrightnessName(settings.tvBrightness()) + ",  " + Settings.TV_COLOUR_NAMES[settings.tvColour()]
                : "Picture left as is";
        if (settings.autoOff() > 0) s += ",  off after " + Settings.autoOffName(settings.autoOff());
        return s;
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

    /**
     * Only the open page's rows show. Within it: Day shows only in One day at a time; Highlights play as a
     * story, so Order and Day give way to the count; Brightness and Colour belong to the Photo preset.
     */
    private boolean shown(int row) {
        if (PAGE_OF[row] != page) return false;
        if (row == ROW_TV_BRIGHT || row == ROW_TV_COLOUR) return settings.tvPicture() == Settings.TV_PIC_ON;
        boolean high = settings.play() == Settings.PLAY_HIGHLIGHTS;
        if (row == ROW_HIGHLIGHTS) return high;
        if (row == ROW_ORDER) return !high;
        if (row == ROW_DAY) return !high && settings.order() == Settings.ORDER_DAY;
        return true;
    }

    /** Rows that only exist because of the row above them. */
    private static boolean dependent(int row) {
        return row == ROW_HIGHLIGHTS || row == ROW_DAY || row == ROW_TV_BRIGHT || row == ROW_TV_COLOUR;
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
