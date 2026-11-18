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
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The "print" filter: makes photos read as a matte print on paper or canvas rather than a glowing
 * screen. Designed for the TCL A300W's ultra-matte panel (spec: design notes, 2026-11-18).
 *
 * 1. Tone, baked once per slide on the loading thread: slight desaturation, then a per-channel curve
 *    that ends at a warm paper white and an ink black, with a soft toe and shoulder but mid-tone
 *    contrast kept.
 * 2. Surface texture, drawn live over the whole composed frame with OVERLAY (paper tooth or canvas
 *    weave), anchored to the screen so it stays still through the cross-fade.
 * 3. Gallery light: a faint top-to-bottom darkening (no radial vignette).
 *
 * Level s: 0 (Off), 0.25 (Light), 0.5 (Moderate, default), 1 (Strong), 1.5 (Extra strong), 2 (Maximum).
 * Immutable; build a new one
 * when the level changes.
 */
final class ArtTreatment {
    private final float s;
    private final int[] lutR = new int[256], lutG = new int[256], lutB = new int[256];
    private final float sat;
    private final Paint paperPaint = new Paint(), canvasPaint = new Paint(), lightPaint = new Paint();
    private int lightH = -1;

    ArtTreatment(float strength) {
        s = Math.max(0f, Math.min(2f, strength));
        // Up to Strong (s = 1) as designed. Past it (Extra strong 1.5, Maximum 2) colour, ink and texture
        // keep going but paper white falls slowly: the matte panel is not bright, so don't dim much more.
        sat = 1f - 0.20f * s;
        float white = s <= 1f ? 1f - 0.18f * s : 0.82f - 0.06f * (s - 1f);
        float[] paper = {white, white * (1f - 0.02f * s), white * (1f - 0.07f * s)};
        float ink = 0.10f * s, roll = 0.12f * s;
        int[][] luts = {lutR, lutG, lutB};
        for (int i = 0; i < 256; i++) {
            double x = i / 255.0;
            double f = x - roll * Math.sin(2 * Math.PI * x) / (2 * Math.PI);
            for (int ch = 0; ch < 3; ch++)
                luts[ch][i] = clamp255((int) Math.round(255 * paper[ch] * (ink + (1 - ink) * f)));
        }
        PorterDuffXfermode overlay = new PorterDuffXfermode(PorterDuff.Mode.OVERLAY);
        paperPaint.setShader(new BitmapShader(Tiles.paper(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        paperPaint.setXfermode(overlay);
        paperPaint.setAlpha(Math.round(255 * (0.08f + 0.08f * s)));
        canvasPaint.setShader(new BitmapShader(Tiles.canvas(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        canvasPaint.setXfermode(overlay);
        canvasPaint.setAlpha(Math.round(255 * (0.10f + 0.10f * s)));
    }

    boolean isOff() { return s == 0f; }

    // The TV's CPU is slow (32-bit, 4 cores): the curve lookup is split across all cores.
    private static final int CORES = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(CORES, r -> {
        Thread t = new Thread(r, "tone");
        t.setDaemon(true);
        return t;
    });

    /** Pass 1 (loading thread): returns a toned copy of the photo, or the photo itself when Off. */
    Bitmap bake(Bitmap src) {
        if (s == 0f || src == null) return src;
        int w = src.getWidth(), h = src.getHeight();
        long t0 = android.os.SystemClock.uptimeMillis();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        // Saturation (ColorMatrix.setSaturation weights, 1/1024 fixed point), then the curve lookups.
        float a = 1f - sat;
        int rr = Math.round(1024 * (0.213f * a + sat)), rg = Math.round(1024 * 0.715f * a), rb = Math.round(1024 * 0.072f * a);
        int gr = Math.round(1024 * 0.213f * a), gg = Math.round(1024 * (0.715f * a + sat)), gb = Math.round(1024 * 0.072f * a);
        int br = Math.round(1024 * 0.213f * a), bgc = Math.round(1024 * 0.715f * a), bb = Math.round(1024 * (0.072f * a + sat));
        List<Future<?>> parts = new ArrayList<>();
        int chunk = (px.length + CORES - 1) / CORES;
        for (int k = 0; k < CORES; k++) {
            int from = k * chunk, to = Math.min(px.length, from + chunk);
            parts.add(POOL.submit(() -> {
                for (int i = from; i < to; i++) {
                    int c = px[i];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    int r2 = clamp255((rr * r + rg * g + rb * b + 512) >> 10);
                    int g2 = clamp255((gr * r + gg * g + gb * b + 512) >> 10);
                    int b2 = clamp255((br * r + bgc * g + bb * b + 512) >> 10);
                    px[i] = 0xFF000000 | (lutR[r2] << 16) | (lutG[g2] << 8) | lutB[b2];
                }
            }));
        }
        try {
            for (Future<?> f : parts) f.get();
        } catch (Exception e) {
            return src;   // untoned rather than crash the frame
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        android.util.Log.i("ArtTreatment", "bake " + w + "x" + h + " in " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
        return out;
    }

    /**
     * Passes 2 and 3 (UI thread, every frame): texture over the composed frame, then gallery light.
     * canvas=true puts canvas weave over the photo rects and paper tooth over the rest.
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

    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(255, v); }

    /** The two 512x512 grey texture tiles (mean 128, sd 25.5), generated once in code. */
    static final class Tiles {
        private static final int N = 512;
        private static Bitmap paper, canvas;

        static synchronized Bitmap paper() {
            if (paper == null) {
                Random rnd = new Random(7);
                float[] n1 = gaussian(rnd, N), n2 = upscale(gaussian(rnd, 256), 256, 2), n8 = upscale(gaussian(rnd, 64), 64, 8);
                normStd(n2);
                normStd(n8);
                float[] h = new float[N * N];
                for (int i = 0; i < h.length; i++) h[i] = n1[i] + 0.8f * n2[i] + 0.12f * n8[i];
                paper = toBitmap(h);
            }
            return paper;
        }

        static synchronized Bitmap canvas() {
            if (canvas == null) {
                Random rnd = new Random(11);
                float[] m = upscale(gaussian(rnd, 64), 64, 8);
                normStd(m);
                float[][] warp = {{1.0f, -0.4f}, {0.6f, -1.0f}}, weft = {{1.0f, 0.6f}, {-0.4f, -1.0f}};
                float[] h = new float[N * N];
                for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                    boolean warpOnTop = ((x / 2 + y / 2) % 2) == 0;
                    float bump = (warpOnTop ? warp : weft)[y % 2][x % 2];
                    float unevenness = Math.max(0.3f, Math.min(1.9f, 1 + 0.45f * m[y * N + x]));
                    h[y * N + x] = bump * unevenness + 0.60f * (float) rnd.nextGaussian();
                }
                canvas = toBitmap(h);
            }
            return canvas;
        }

        private static float[] gaussian(Random rnd, int n) {
            float[] a = new float[n * n];
            for (int i = 0; i < a.length; i++) a[i] = (float) rnd.nextGaussian();
            return a;
        }

        /** Wrap-around bilinear upscale of an n x n field by factor f to N x N. */
        private static float[] upscale(float[] src, int n, int f) {
            float[] out = new float[N * N];
            for (int y = 0; y < N; y++) {
                float sy = (y + 0.5f) / f - 0.5f;
                int y0 = (int) Math.floor(sy);
                float ty = sy - y0;
                int ya = Math.floorMod(y0, n), yb = Math.floorMod(y0 + 1, n);
                for (int x = 0; x < N; x++) {
                    float sx = (x + 0.5f) / f - 0.5f;
                    int x0 = (int) Math.floor(sx);
                    float tx = sx - x0;
                    int xa = Math.floorMod(x0, n), xb = Math.floorMod(x0 + 1, n);
                    float top = src[ya * n + xa] * (1 - tx) + src[ya * n + xb] * tx;
                    float bot = src[yb * n + xa] * (1 - tx) + src[yb * n + xb] * tx;
                    out[y * N + x] = top * (1 - ty) + bot * ty;
                }
            }
            return out;
        }

        private static void normStd(float[] a) {
            double mean = 0, var = 0;
            for (float v : a) mean += v;
            mean /= a.length;
            for (float v : a) var += (v - mean) * (v - mean);
            float sd = (float) Math.sqrt(var / a.length);
            if (sd > 0) for (int i = 0; i < a.length; i++) a[i] /= sd;
        }

        private static Bitmap toBitmap(float[] h) {
            double mean = 0, var = 0;
            for (float v : h) mean += v;
            mean /= h.length;
            for (float v : h) var += (v - mean) * (v - mean);
            double sd = Math.sqrt(var / h.length);
            int[] px = new int[h.length];
            for (int i = 0; i < h.length; i++) {
                int v = clamp255((int) Math.round(128 + 25.5 * (h[i] - mean) / sd));
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            return Bitmap.createBitmap(px, N, N, Bitmap.Config.ARGB_8888);
        }
    }
}
