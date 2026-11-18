package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;

/** Viewer settings changed on the TV's Settings page (▲). Shared by the app and the screensaver. */
final class Settings {
    // Levels past Strong (the owner, 2026-11-18) add print character; see ArtTreatment for the knee at 1.
    static final String[] ART_NAMES = {"Off", "Light", "Moderate", "Strong", "Extra strong", "Maximum"};
    static final float[] ART_STRENGTHS = {0f, 0.25f, 0.5f, 1f, 1.5f, 2f};
    static final int DEFAULT_ART_LEVEL = 2;   // Moderate

    static final int[] INTERVAL_SECONDS = {30, 60, 300, 600, 1800, 3600};
    static final int DEFAULT_INTERVAL = 3;    // 10 minutes

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

    static final String[] LOOK_NAMES = {"Full screen", "Framed"};
    static final int LOOK_FULL = 0, LOOK_FRAMED = 1;
    int look() { return clamp(prefs.getInt("look", LOOK_FULL), LOOK_NAMES.length, LOOK_FULL); }
    void setLook(int l) { prefs.edit().putInt("look", l).apply(); }

    static final String[] SURFACE_NAMES = {"Paper", "Canvas"};
    static final int SURFACE_PAPER = 0, SURFACE_CANVAS = 1;
    int surface() { return clamp(prefs.getInt("surface", SURFACE_PAPER), SURFACE_NAMES.length, SURFACE_PAPER); }
    void setSurface(int v) { prefs.edit().putInt("surface", v).apply(); }

    static final String[] ORDER_NAMES = {"Shuffle", "One day at a time"};
    static final int ORDER_SHUFFLE = 0, ORDER_DAY = 1;
    int order() { return clamp(prefs.getInt("order", ORDER_SHUFFLE), ORDER_NAMES.length, ORDER_SHUFFLE); }
    void setOrder(int v) { prefs.edit().putInt("order", v).apply(); }

    /** One day at a time: the chosen day as "yyyy:MM:dd" (the photos' own date format), or "" for any day. */
    String day() { return prefs.getString("day", ""); }
    void setDay(String d) { prefs.edit().putString("day", d).apply(); }

    /** Trips to play: "start~end;start~end" (days as yyyy:MM:dd), or "" for all trips. */
    String trips() { return prefs.getString("trips", ""); }
    void setTrips(String t) { prefs.edit().putString("trips", t).apply(); }

    /** Space for photos on the TV; the least-recently-played trip is cleared beyond it. */
    static final int[] SPACE_GB = {5, 10, 15, 20, 30};
    static final int DEFAULT_SPACE = 3;   // 20 GB
    int space() { return clamp(prefs.getInt("space", DEFAULT_SPACE), SPACE_GB.length, DEFAULT_SPACE); }
    void setSpace(int i) { prefs.edit().putInt("space", i).apply(); }
    long spaceBytes() { return SPACE_GB[space()] * Storage.GB; }

    // Play: which photos (v0.14 added Places only and Highlights; before that a boolean "favourites_only").
    static final String[] PLAY_NAMES = {"All photos", "Favourites only", "Places only", "Highlights"};
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

    private static int clamp(int v, int n, int dflt) { return v >= 0 && v < n ? v : dflt; }
}
