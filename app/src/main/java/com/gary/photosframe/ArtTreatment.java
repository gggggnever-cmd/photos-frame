package com.gary.photosframe;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * The "art" look applied to every photo as it is drawn into the frame: a matte print rather than
 * a glowing screen. Lower, softer light; gentler contrast (lifted blacks, rolled-off whites);
 * slightly quieter colour with a touch of warmth; a faint gallery-light falloff; and a very fine
 * paper grain. Everything is photo-preserving: no blur, no painterly effects.
 */
final class ArtTreatment {
    /** 0 = untouched photo, 0.5 = moderate (default), 1 = strong. The single knob to tune. */
    static final float STRENGTH = 0.5f;

    private final float s;
    private final ColorMatrixColorFilter filter;
    private final Paint grainPaint = new Paint();
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ArtTreatment() { this(STRENGTH); }

    ArtTreatment(float strength) {
        s = Math.max(0f, Math.min(1f, strength));
        filter = new ColorMatrixColorFilter(matrix(s));
        grainPaint.setShader(new BitmapShader(grainTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        grainPaint.setAlpha(Math.round(255 * 0.05f * s));
    }

    /** Colour/tone pass: set on the Paint used to draw the photo bitmap. */
    ColorMatrixColorFilter colorFilter() { return s == 0f ? null : filter; }

    /** Light and surface pass: drawn over the photo area after the photo itself. */
    void drawSurface(Canvas c, RectF photo) {
        if (s == 0f) return;
        // Gallery light: centre stays as is, edges fall off gently.
        float r = (float) Math.hypot(photo.width(), photo.height()) / 2f;
        vignettePaint.setShader(new RadialGradient(photo.centerX(), photo.centerY() - photo.height() * 0.08f, r,
                new int[]{0x00000000, 0x00000000, argb(0.22f * s)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(photo, vignettePaint);
        // Fine matte paper grain.
        c.drawRect(photo, grainPaint);
    }

    /** Pure function of strength so it is easy to reason about (and mirrored in tools/preview.py). */
    static float[] coefficients(float s) {
        float contrast = 1f - 0.30f * s;   // 0.85 at moderate
        float sat = 1f - 0.28f * s;        // 0.86
        float gain = 1f - 0.16f * s;       // 0.92: lower overall light
        float warmR = 1f + 0.03f * s, warmB = 1f - 0.05f * s;
        return new float[]{contrast, sat, gain, warmR, warmB};
    }

    private static ColorMatrix matrix(float s) {
        float[] k = coefficients(s);
        float contrast = k[0], sat = k[1], gain = k[2], warmR = k[3], warmB = k[4];
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(sat);
        // Contrast around mid-grey: lifts blacks and lowers whites (the "matte" tone curve).
        float t = 128f * (1f - contrast);
        m.postConcat(new ColorMatrix(new float[]{
                contrast, 0, 0, 0, t,
                0, contrast, 0, 0, t,
                0, 0, contrast, 0, t,
                0, 0, 0, 1, 0}));
        m.postConcat(new ColorMatrix(new float[]{
                gain * warmR, 0, 0, 0, 0,
                0, gain, 0, 0, 0,
                0, 0, gain * warmB, 0, 0,
                0, 0, 0, 1, 0}));
        return m;
    }

    private static Bitmap grainTile() {
        int n = 256;
        int[] px = new int[n * n];
        Random rnd = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int v = 128 + (int) (rnd.nextGaussian() * 40);
            v = Math.max(0, Math.min(255, v));
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    private static int argb(float alpha) { return Math.round(255 * alpha) << 24; }
}
