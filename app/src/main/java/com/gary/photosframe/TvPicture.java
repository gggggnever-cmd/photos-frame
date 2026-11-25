package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * The TV's own picture settings while the photos show (asked 2026-11-24): calmer and print-like for the frame,
 * back to the owner's normal picture the moment anything else is on.
 *
 * Uses TCL's public library com.tcl.tv.display (TvPqManager / TvBacklightManager, the same calls the TV's
 * Settings app makes), through reflection so the app still runs on any other TV. Everything is applied with
 * EN_TCL_ACT_EXEC, which changes the picture without SAVING it: the TV's stored settings are never touched, so
 * a crash or a restart can't leave the TV stuck in the frame's look. On leaving, the values read on arrival are
 * executed again. The SELinux policy on this TV lets ordinary apps (untrusted_app) call tcl_tv_display.
 */
final class TvPicture {
    private static final String TAG = "TvPicture";
    private static final int WINDOW = 0;   // the main picture (TvSettings' getWindowId())
    private static final Handler worker;
    static {
        HandlerThread t = new HandlerThread("tv-picture");
        t.start();
        worker = new Handler(t.getLooper());
    }

    /** One picture value: how to read it, how to set it, and the frame's value (null = leave it). */
    private static final String[] INT_ITEMS = {
            // name, frame value
            "Sharpness", "0",
            "NR", "0",
            "MpegNR", "0",
            "DynamicContrast", "0",
            "AipqEnable", "0",
            "AisrEnable", "0",
            "LedMotionClarity", "0",
            "BlackStretch", "0",
            "DarkEnhance", "0",
            "ColorEnhance", "0",
            "Sdr2Hdr", "0",
    };

    private static Object pq, bl;
    private static Class<?> applyType, actType, colorType;
    private static boolean tried, available;

    private static synchronized boolean load() {
        if (tried) return available;
        tried = true;
        try {
            pq = Class.forName("com.tcl.tv.pq.TvPqManager").getMethod("getInstance").invoke(null);
            bl = Class.forName("com.tcl.tv.pq.TvBacklightManager").getMethod("getInstance").invoke(null);
            applyType = Class.forName("com.tcl.tv.pq.EnApplyType");
            actType = Class.forName("com.tcl.tv.pq.EnTclActType");
            colorType = Class.forName("com.tcl.tv.pq.EnColorTmpType");
            available = pq != null && bl != null;
        } catch (Throwable t) {
            Log.i(TAG, "TCL picture library not available: " + t);
        }
        return available;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object en(Class<?> c, String name) { return Enum.valueOf((Class) c, name); }

    private static Method find(Object o, String name, int args) {
        for (Method m : o.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == args) return m;
        return null;
    }

    private static int get(String item) throws Exception {
        if (item.equals("PictureMode")) return (Integer) find(pq, "getPictureMode", 0).invoke(pq);
        if (item.equals("Backlight")) return (Integer) find(bl, "getBacklight", 0).invoke(bl);
        if (item.equals("DBCLevel")) return (Integer) find(bl, "getDBCLevel", 0).invoke(bl);
        if (item.equals("LightSensor")) return (Integer) find(bl, "getLightSensorOnoff", 0).invoke(bl);
        return (Integer) find(pq, "get" + item, 1).invoke(pq, WINDOW);
    }

    /** Executes (doesn't save) one value. */
    private static boolean exec(String item, int v) throws Exception {
        Object apply = en(applyType, "EN_APPLY_CURRENT"), act = en(actType, "EN_TCL_ACT_EXEC");
        Object r;
        switch (item) {
            case "PictureMode": return execMode(v, false);
            case "Backlight": r = find(bl, "setBacklight", 3).invoke(bl, apply, v, act); break;
            case "DBCLevel": r = find(bl, "setDBCLevel", 3).invoke(bl, apply, v, act); break;
            case "LightSensor": r = find(bl, "setLightSensorOnoff", 2).invoke(bl, v, act); break;
            case "ColorTemperature":
                Object ct = colorType.getEnumConstants()[v];
                r = find(pq, "setColorTemperature", 4).invoke(pq, WINDOW, apply, ct, act); break;
            default: r = find(pq, "set" + item, 4).invoke(pq, WINDOW, apply, v, act);
        }
        return Boolean.TRUE.equals(r);
    }

    static final int MOVIE = 3;   // getPictureMode(): 0 Standard, 1 Vivid, 2 Mild, 3 Movie ("Studio"), 4 Sport…
    private static final long RESTORE_DELAY_MS = 1500;   // the frame handing over to its screensaver isn't a leave
    private static final Runnable restoreLater = () -> restoreNow();
    private static Context app;

    /**
     * The photos are showing (frame or screensaver), or the setting changed: the calm picture, per Settings.
     * On first arrival the TV's own picture mode and backlight are remembered for frameOff().
     */
    static void frameOn(Context ctx) {
        app = ctx.getApplicationContext();
        RoomLight.start(app);
        worker.removeCallbacks(restoreLater);
        worker.removeCallbacks(autoTick);
        worker.postDelayed(autoTick, 5_000);   // the room's first readings arrive within a few seconds
        worker.post(() -> {
            if (!load()) return;
            Settings s = new Settings(app);
            if (s.tvPicture() == Settings.TV_PIC_OFF) { restoreNow(); return; }
            SharedPreferences p = prefs(app);
            try {
                boolean fresh = !p.getBoolean("applied", false);   // the TV's own picture is showing now
                if (fresh) {
                    p.edit().putBoolean("applied", true).putInt("tv_mode", get("PictureMode"))
                            .putInt("tv_backlight", get("Backlight")).putInt("tv_sensor", get("LightSensor")).commit();
                }
                StringBuilder b = new StringBuilder("photos on: mode " + MOVIE);
                // Selecting Movie loads its saved values, so they can be read as the photos' starting point.
                if (get("PictureMode") != MOVIE) { exec("PictureMode", MOVIE); fresh = true; }
                exec("ColorTemperature", Settings.TV_COLOUR_TEMP[s.tvColour()]);
                for (int i = 0; i < INT_ITEMS.length; i += 2) exec(INT_ITEMS[i], Integer.parseInt(INT_ITEMS[i + 1]));
                // Contrast: the Movie mode's own value (read once per visit), scaled down for the dimmest settings.
                if (fresh || !p.contains("photo_contrast")) p.edit().putInt("photo_contrast", get("Contrast")).commit();
                exec("LightSensor", 1);   // the TV's own light sensor on: it feeds RoomLight (Auto brightness)
                lastBright = applyBrightness(s, p);
                b.append(", ").append(Settings.TV_COLOUR_NAMES[s.tvColour()]).append(", no boosts, brightness ")
                        .append(lastBright == Settings.TV_OWN ? "TV's own" : String.valueOf(lastBright))
                        .append(s.tvAuto() ? " (auto, room " + RoomLight.lux() + " lux)" : "");
                Log.i(TAG, b.toString());
            } catch (Throwable t) { Log.w(TAG, "couldn't set the photo picture: " + cause(t)); }
        });
    }

    private static int lastBright = Integer.MIN_VALUE;

    /** Worker thread: backlight and contrast for the brightness now. Returns that brightness. */
    private static int applyBrightness(Settings s, SharedPreferences p) throws Exception {
        int bright = s.tvBrightnessNow();
        int contrast = p.getInt("photo_contrast", 100) * s.tvContrastPercent() / 100;
        exec("Contrast", contrast);
        int backlight = s.tvBacklight();
        exec("Backlight", backlight >= 0 ? backlight : p.getInt("tv_backlight", 0));
        return bright;
    }

    /** Auto brightness: every 30 s while the photos show, follow the room if its light moved to another step. */
    private static final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (app == null || !load()) return;
            SharedPreferences p = prefs(app);
            Settings s = new Settings(app);
            if (!p.getBoolean("applied", false) || s.tvPicture() == Settings.TV_PIC_OFF) return;
            worker.postDelayed(this, 30_000);
            if (!s.tvAuto() || s.tvBrightnessNow() == lastBright) return;
            try {
                int was = lastBright;
                lastBright = applyBrightness(s, p);
                Log.i(TAG, "auto brightness " + was + " -> " + lastBright + " (room " + RoomLight.lux() + " lux)");
            } catch (Throwable t) { Log.w(TAG, "auto brightness failed: " + cause(t)); }
        }
    };

    /** The photos are gone: the TV's own picture again, shortly (frameOn in time cancels it). */
    static void frameOff(Context ctx) {
        app = ctx.getApplicationContext();
        worker.removeCallbacks(restoreLater);
        worker.postDelayed(restoreLater, RESTORE_DELAY_MS);
    }

    /** Worker thread: re-selects the TV's own picture mode (which reloads its saved values) and backlight. */
    private static void restoreNow() {
        if (app == null || !load()) return;
        SharedPreferences p = prefs(app);
        if (!p.getBoolean("applied", false)) return;
        try {
            int mode = p.getInt("tv_mode", 1), backlight = p.getInt("tv_backlight", 0);
            // The mode is also saved: it's the TV's own choice anyway, and saving it keeps the photos' Movie
            // from coming back after a restart or an input change.
            execMode(mode, true);
            exec("Backlight", backlight);
            exec("LightSensor", p.getInt("tv_sensor", 1));
            worker.removeCallbacks(autoTick);
            p.edit().putBoolean("applied", false).commit();
            Log.i(TAG, "photos off: TV picture back to mode " + mode + ", backlight " + backlight);
        } catch (Throwable t) { Log.w(TAG, "couldn't restore the TV picture: " + cause(t)); }
    }

    /** Selects a picture mode (which loads that mode's saved values); save = also make it the TV's stored mode. */
    private static boolean execMode(int mode, boolean save) throws Exception {
        Object apply = en(applyType, "EN_APPLY_CURRENT"), act = en(actType, save ? "EN_TCL_ACT_EXEC_SAVE" : "EN_TCL_ACT_EXEC");
        Object field = en(Class.forName("com.tcl.tv.pq.EnPicModeFieldType"), "EN_PICTURE_MODE_FIELD_NORMAL");
        return Boolean.TRUE.equals(find(pq, "setPictureMode", 5).invoke(pq, WINDOW, apply, mode, field, act));
    }

    /** adb test only: the picture mode to go back to when the photos leave (if they're showing now). */
    static void testTvMode(Context ctx, int mode) {
        worker.post(() -> {
            SharedPreferences p = prefs(ctx.getApplicationContext());
            if (p.getBoolean("applied", false)) p.edit().putInt("tv_mode", mode).commit();
            Log.i(TAG, "test: TV mode to restore = " + p.getInt("tv_mode", -1) + " (applied " + p.getBoolean("applied", false) + ")");
        });
    }

    /** All the values this class touches, as "name=value ..." (for logs and tests). */
    static void dump(Context ctx) {
        worker.post(() -> {
            if (!load()) return;
            StringBuilder b = new StringBuilder("now:");
            for (String item : allItems()) {
                try { b.append(' ').append(item).append('=').append(get(item)); }
                catch (Throwable t) { b.append(' ').append(item).append("=ERR(").append(cause(t)).append(')'); }
            }
            b.append(" | pictureMode=");
            try { b.append(find(pq, "getPictureMode", 0).invoke(pq)); } catch (Throwable t) { b.append("ERR"); }
            b.append(" | colorTypes=");
            for (Object o : colorType.getEnumConstants()) b.append(((Enum<?>) o).ordinal()).append(':').append(o).append(',');
            Log.i(TAG, b.toString());
        });
    }

    /** adb test only: sets one value (executed, not saved) and logs the result. */
    static void testSet(String item, int v) {
        worker.post(() -> {
            if (!load()) return;
            try {
                int before = get(item);
                boolean ok = exec(item, v);
                Log.i(TAG, "test set " + item + ": " + before + " -> " + v + " ok=" + ok + " now=" + get(item));
            } catch (Throwable t) { Log.w(TAG, "test set " + item + " failed: " + cause(t)); }
        });
    }

    private static String[] allItems() {
        String[] out = new String[INT_ITEMS.length / 2 + 4];
        out[0] = "Backlight"; out[1] = "DBCLevel"; out[2] = "LightSensor"; out[3] = "ColorTemperature";
        for (int i = 0; i < INT_ITEMS.length; i += 2) out[4 + i / 2] = INT_ITEMS[i];
        return out;
    }

    private static String cause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.toString();
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("tvpicture", Context.MODE_PRIVATE); }
}
