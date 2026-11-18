package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;

/** Viewer settings that the remote can change. Shared by the app and the screensaver. */
final class Settings {
    static final String[] ART_NAMES = {"Off", "Light", "Moderate", "Strong"};
    static final float[] ART_STRENGTHS = {0f, 0.25f, 0.5f, 1f};
    static final int DEFAULT_ART_LEVEL = 2;   // Moderate

    private final SharedPreferences prefs;
    Settings(Context ctx) { prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    int artLevel() {
        int l = prefs.getInt("art_level", DEFAULT_ART_LEVEL);
        return l >= 0 && l < ART_STRENGTHS.length ? l : DEFAULT_ART_LEVEL;
    }

    /** Moves to the next level (wrapping Strong → Off), saves it and returns it. */
    int cycleArtLevel() {
        int next = (artLevel() + 1) % ART_STRENGTHS.length;
        prefs.edit().putInt("art_level", next).apply();
        return next;
    }

    float artStrength() { return ART_STRENGTHS[artLevel()]; }
    String artName() { return ART_NAMES[artLevel()]; }
}
