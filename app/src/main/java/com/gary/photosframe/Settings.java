package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;

/** Viewer settings changed on the TV's Settings page (▲). Shared by the app and the screensaver. */
final class Settings {
    // Levels past Strong (the owner, 2026-11-18) add print character; see ArtTreatment for the knee at 1.
    static final String[] ART_NAMES = {"Off", "Light", "Medium", "Strong", "Extra strong", "Strongest"};
    static final float[] ART_STRENGTHS = {0f, 0.25f, 0.5f, 1f, 1.5f, 2f};
    static final int DEFAULT_ART_LEVEL = 2;   // Moderate

    // 5, 10, 20 seconds added 2026-11-21 at the owner's request. Saved as seconds, so older choices keep.
    static final int[] INTERVAL_SECONDS = {5, 10, 20, 30, 60, 300, 600, 1800, 3600};
    static final int DEFAULT_INTERVAL = 6;    // 10 minutes

    private final SharedPreferences prefs;
    Settings(Context ctx) { prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    int artLevel() { return clamp(prefs.getInt("art_level", DEFAULT_ART_LEVEL), ART_STRENGTHS.length, DEFAULT_ART_LEVEL); }
    void setArtLevel(int l) { prefs.edit().putInt("art_level", l).apply(); }
    float artStrength() { return ART_STRENGTHS[artLevel()]; }
    String artName() { return ART_NAMES[artLevel()]; }

    // Saved as seconds ("interval_s"). Before v0.10 it was an index into {1, 5, 10, 30, 60} minutes
    // ("interval"); that old choice is carried over the first time.
    private static final int[] OLD_INTERVAL_MINUTES = {1, 5, 10, 30, 60};
    int interval() {
        int sec = prefs.getInt("interval_s", -1);
        if (sec < 0 && prefs.contains("interval")) {
            int old = prefs.getInt("interval", 2);
            sec = OLD_INTERVAL_MINUTES[old >= 0 && old < OLD_INTERVAL_MINUTES.length ? old : 2] * 60;
        }
        for (int i = 0; i < INTERVAL_SECONDS.length; i++) if (INTERVAL_SECONDS[i] == sec) return i;
        return DEFAULT_INTERVAL;
    }
    void setInterval(int i) { prefs.edit().putInt("interval_s", INTERVAL_SECONDS[i]).apply(); }
    long showMs() { return INTERVAL_SECONDS[interval()] * 1000L; }
    static String intervalName(int i) {
        int s = INTERVAL_SECONDS[i];
        if (s < 60) return s + " seconds";
        if (s == 3600) return "1 hour";
        int m = s / 60;
        return m + (m == 1 ? " minute" : " minutes");
    }

    static final String[] LOOK_NAMES = {"Full screen", "With a border"};
    static final int LOOK_FULL = 0, LOOK_FRAMED = 1;
    int look() { return clamp(prefs.getInt("look", LOOK_FULL), LOOK_NAMES.length, LOOK_FULL); }
    void setLook(int l) { prefs.edit().putInt("look", l).apply(); }

    static final String[] SURFACE_NAMES = {"Fine art paper", "Canvas", "Cut-out collage"};
    static final int SURFACE_PAPER = 0, SURFACE_CANVAS = 1, SURFACE_CUT = 2;   // Paper cut: flat colour sheets
    int surface() { return clamp(prefs.getInt("surface", SURFACE_PAPER), SURFACE_NAMES.length, SURFACE_PAPER); }
    void setSurface(int v) { prefs.edit().putInt("surface", v).apply(); }

    static final String[] ORDER_NAMES = {"Shuffle", "One day at a time"};
    static final int ORDER_SHUFFLE = 0, ORDER_DAY = 1;
    int order() { return clamp(prefs.getInt("order", ORDER_SHUFFLE), ORDER_NAMES.length, ORDER_SHUFFLE); }
    void setOrder(int v) { prefs.edit().putInt("order", v).apply(); }

    /** One day at a time: the chosen day as "yyyy:MM:dd" (the photos' own date format), or "" for any day. */
    String day() { return prefs.getString("day", ""); }
    void setDay(String d) { prefs.edit().putString("day", d).apply(); }

    /**
     * Albums to play: tokens joined by ";", each a date trip "start~end" (days as yyyy:MM:dd) or a membership
     * album "album:<id>" (see AlbumStore); "" for all. Values saved before albums have only ranges.
     */
    String trips() { return prefs.getString("trips", ""); }
    void setTrips(String t) { prefs.edit().putString("trips", t).apply(); }

    /** Space for photos on the TV; the least-recently-played trip is cleared beyond it. */
    static final int[] SPACE_GB = {5, 10, 15, 20, 30};
    static final int DEFAULT_SPACE = 3;   // 20 GB
    int space() { return clamp(prefs.getInt("space", DEFAULT_SPACE), SPACE_GB.length, DEFAULT_SPACE); }
    void setSpace(int i) { prefs.edit().putInt("space", i).apply(); }
    long spaceBytes() { return SPACE_GB[space()] * Storage.GB; }

    // Play: which photos (v0.14 added Places only and Highlights; before that a boolean "favourites_only").
    static final String[] PLAY_NAMES = {"All photos", "Favourites", "Places only", "Highlights"};
    static final int PLAY_ALL = 0, PLAY_FAV = 1, PLAY_PLACES = 2, PLAY_HIGHLIGHTS = 3;
    int play() {
        if (!prefs.contains("play")) return prefs.getBoolean("favourites_only", false) ? PLAY_FAV : PLAY_ALL;
        return clamp(prefs.getInt("play", PLAY_ALL), PLAY_NAMES.length, PLAY_ALL);
    }
    void setPlay(int v) { prefs.edit().putInt("play", v).apply(); }

    /** Highlights: how many per trip. */
    static final int DEFAULT_HIGHLIGHTS = 1;   // 30
    int highlightsIndex() { return clamp(prefs.getInt("highlights", DEFAULT_HIGHLIGHTS), Highlights.COUNTS.length, DEFAULT_HIGHLIGHTS); }
    void setHighlightsIndex(int i) { prefs.edit().putInt("highlights", i).apply(); }
    int highlightCount() { return Highlights.COUNTS[highlightsIndex()]; }

    // Turn the TV off after this many minutes with no remote while the photos or screensaver show (0 = never).
    // Asked 2026-11-22; see AutoOff.
    static final int[] AUTO_OFF_MINUTES = {0, 10, 30, 60, 120, 180, 240};
    int autoOff() { return clamp(prefs.getInt("auto_off", 0), AUTO_OFF_MINUTES.length, 0); }
    void setAutoOff(int i) { prefs.edit().putInt("auto_off", i).apply(); }
    long autoOffMs() { return AUTO_OFF_MINUTES[autoOff()] * 60_000L; }
    static String autoOffName(int i) {
        int m = AUTO_OFF_MINUTES[i];
        if (m == 0) return "Never";
        if (m < 60) return m + " minutes";
        return m == 60 ? "1 hour" : (m / 60) + " hours";
    }

    // The TV's own picture while the photos show (asked 2026-11-24; see TvPicture). 0 = leave the TV alone,
    // 1 = calm (Movie, warm, no sharpening or "AI" boosts) at the TV's own brightness, 2.. = calm at a backlight
    // of 0, 10, ... 100.
    static final int TV_PIC_OFF = 0, TV_PIC_CALM = 1, TV_PIC_COUNT = 13;
    int tvPicture() { return clamp(prefs.getInt("tv_picture", TV_PIC_CALM), TV_PIC_COUNT, TV_PIC_CALM); }
    void setTvPicture(int i) { prefs.edit().putInt("tv_picture", i).apply(); }
    /** The backlight to use, or -1 to keep the TV's own. */
    int tvBacklight() { int i = tvPicture(); return i >= 2 ? (i - 2) * 10 : -1; }
    static String tvPictureName(int i) {
        if (i == TV_PIC_OFF) return "Leave as is";
        if (i == TV_PIC_CALM) return "Calm";
        return "Calm · brightness " + (i - 2) * 10;
    }

    private static int clamp(int v, int n, int dflt) { return v >= 0 && v < n ? v : dflt; }
}
