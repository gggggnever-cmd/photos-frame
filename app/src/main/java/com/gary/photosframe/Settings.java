package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;

/** Viewer settings changed on the TV's Settings page (▲). Shared by the app and the screensaver. */
final class Settings {
    static final String[] ART_NAMES = {"Off", "Light", "Moderate", "Strong"};
    static final float[] ART_STRENGTHS = {0f, 0.25f, 0.5f, 1f};
    static final int DEFAULT_ART_LEVEL = 2;   // Moderate

    static final int[] INTERVAL_MINUTES = {1, 5, 10, 30, 60};
    static final int DEFAULT_INTERVAL = 2;    // 10 minutes

    private final SharedPreferences prefs;
    Settings(Context ctx) { prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    int artLevel() { return clamp(prefs.getInt("art_level", DEFAULT_ART_LEVEL), ART_STRENGTHS.length, DEFAULT_ART_LEVEL); }
    void setArtLevel(int l) { prefs.edit().putInt("art_level", l).apply(); }
    float artStrength() { return ART_STRENGTHS[artLevel()]; }
    String artName() { return ART_NAMES[artLevel()]; }

    int interval() { return clamp(prefs.getInt("interval", DEFAULT_INTERVAL), INTERVAL_MINUTES.length, DEFAULT_INTERVAL); }
    void setInterval(int i) { prefs.edit().putInt("interval", i).apply(); }
    long showMs() { return INTERVAL_MINUTES[interval()] * 60_000L; }
    static String intervalName(int i) { int m = INTERVAL_MINUTES[i]; return m == 60 ? "1 hour" : m + (m == 1 ? " minute" : " minutes"); }

    static final String[] LOOK_NAMES = {"Full screen", "Framed"};
    static final int LOOK_FULL = 0, LOOK_FRAMED = 1;
    int look() { return clamp(prefs.getInt("look", LOOK_FULL), LOOK_NAMES.length, LOOK_FULL); }
    void setLook(int l) { prefs.edit().putInt("look", l).apply(); }

    static final String[] SURFACE_NAMES = {"Paper", "Canvas"};
    static final int SURFACE_PAPER = 0, SURFACE_CANVAS = 1;
    int surface() { return clamp(prefs.getInt("surface", SURFACE_PAPER), SURFACE_NAMES.length, SURFACE_PAPER); }
    void setSurface(int v) { prefs.edit().putInt("surface", v).apply(); }

    private static int clamp(int v, int n, int dflt) { return v >= 0 && v < n ? v : dflt; }
}
