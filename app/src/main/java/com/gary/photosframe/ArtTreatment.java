package com.gary.photosframe;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The "print" filter: makes photos read as a matte print on paper or canvas rather than a glowing
 * screen. Designed for the TCL A300W's ultra-matte panel (spec: docs/print_filter_spec.md, 2026-11-18).
 *
 * 1. Baked once per slide on the loading thread: Paper cut rebuilds the photo from flat colour sheets
 *    with soft shadows; Paper and Canvas get a slight ink soak (fine detail softened). Then tone: slight
 *    desaturation and a per-channel curve that ends at a warm paper white and an ink black.
 * 2. Surface texture, drawn live over the whole composed frame with OVERLAY (paper fibres or canvas
 *    weave, lit from the top left so they read as raised), anchored to the screen.
 * 3. Gallery light: a faint top-to-bottom darkening (no radial vignette).
 * The pixel maths lives in PrintEffects (pure Java, previewable on the PC with tools/PrintPreview.java).
 *
 * Level s: 0 (Off), 0.25 (Light), 0.5 (Moderate, default), 1 (Strong), 1.5 (Extra strong), 2 (Maximum).
 * Immutable; build a new one when the level changes.
 */
final class ArtTreatment {
    private final float s;
    private final PrintEffects.Tone tone, cutTone;
    private final Paint paperPaint = new Paint(), canvasPaint = new Paint(), lightPaint = new Paint();
    private int lightH = -1;

    ArtTreatment(float strength) {
        s = Math.max(0f, Math.min(2f, strength));
        tone = new PrintEffects.Tone(s);
        cutTone = new PrintEffects.Tone(PrintEffects.cutToneLevel(s));
        PorterDuffXfermode overlay = new PorterDuffXfermode(PorterDuff.Mode.OVERLAY);
        paperPaint.setShader(new BitmapShader(Tiles.paper(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        paperPaint.setXfermode(overlay);
        paperPaint.setAlpha(Math.round(255 * PrintEffects.paperAlpha(s)));
        canvasPaint.setShader(new BitmapShader(Tiles.canvas(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        canvasPaint.setXfermode(overlay);
        canvasPaint.setAlpha(Math.round(255 * PrintEffects.canvasAlpha(s)));
    }

    boolean isOff() { return s == 0f; }

    // The TV's CPU is slow (32-bit, 4 cores): the pixel work is split across all cores by rows.
    private static final int CORES = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(CORES, r -> {
        Thread t = new Thread(r, "tone");
        t.setDaemon(true);
        return t;
    });

    private interface Rows { void run(int from, int to); }

    private static void byRows(int h, Rows job) throws Exception {
        List<Future<?>> parts = new ArrayList<>();
        int chunk = (h + CORES - 1) / CORES;
        for (int k = 0; k < CORES; k++) {
            int from = k * chunk, to = Math.min(h, from + chunk);
            if (from >= to) break;
            parts.add(POOL.submit((Callable<Void>) () -> { job.run(from, to); return null; }));
        }
        for (Future<?> f : parts) f.get();
    }

    /** Pass 1 (loading thread): returns a finished copy of the photo, or the photo itself when Off. */
    Bitmap bake(Bitmap src, int surface) {
        if (s == 0f || src == null) return src;
        int w = src.getWidth(), h = src.getHeight();
        long t0 = android.os.SystemClock.uptimeMillis();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        try {
            int[] in = px, out = new int[w * h];
            if (surface == Settings.SURFACE_CUT) {
                PrintEffects.Cut cut = PrintEffects.planCut(in, w, h);
                float shadow = PrintEffects.cutShadow(s);
                int reach = PrintEffects.cutReach(s, h);
                byRows(h, (from, to) -> PrintEffects.cutRows(cut, out, w, h, from, to, shadow, reach));
            } else {
                float soak = PrintEffects.soak(s);
                byRows(h, (from, to) -> PrintEffects.soften(in, out, w, h, soak, from, to));
            }
            PrintEffects.Tone t = surface == Settings.SURFACE_CUT ? cutTone : tone;
            byRows(h, (from, to) -> t.apply(out, w * from, w * to));
            px = out;
        } catch (Exception e) {
            return src;   // untoned rather than crash the frame
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(px, 0, w, 0, 0, w, h);
        android.util.Log.i("ArtTreatment", "bake " + w + "x" + h + (surface == Settings.SURFACE_CUT ? " paper cut" : "")
                + " in " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
        return result;
    }

    /**
     * Passes 2 and 3 (UI thread, every frame): texture over the composed frame, then gallery light.
     * canvas=true puts canvas weave over the photo rects and paper over the rest.
     */
    void drawSurface(Canvas c, int w, int h, List<RectF> photoRects, boolean canvas) {
        if (s == 0f) return;
        if (canvas && !photoRects.isEmpty()) {
            int save = c.save();
            for (RectF r : photoRects) c.clipOutRect(r);
            c.drawRect(0, 0, w, h, paperPaint);
            c.restoreToCount(save);
            for (RectF r : photoRects) c.drawRect(r, canvasPaint);
        } else {
            c.drawRect(0, 0, w, h, paperPaint);
        }
        if (lightH != h) {
            lightPaint.setShader(new LinearGradient(0, 0.20f * h, 0, h, 0x00000000,
                    Math.round(255 * Math.min(0.10f, 0.07f * s)) << 24, Shader.TileMode.CLAMP));
            lightH = h;
        }
        c.drawRect(0, 0.20f * h, w, h, lightPaint);
    }

    /** The two 512x512 grey texture tiles (mean 128, sd 25.5), generated once in code. */
    static final class Tiles {
        private static Bitmap paper, canvas;

        static synchronized Bitmap paper() {
            if (paper == null) paper = toBitmap(PrintEffects.tile(PrintEffects.paperHeight()));
            return paper;
        }

        static synchronized Bitmap canvas() {
            if (canvas == null) canvas = toBitmap(PrintEffects.tile(PrintEffects.canvasHeight()));
            return canvas;
        }

        private static Bitmap toBitmap(int[] px) {
            return Bitmap.createBitmap(px, PrintEffects.N, PrintEffects.N, Bitmap.Config.ARGB_8888);
        }
    }
}
