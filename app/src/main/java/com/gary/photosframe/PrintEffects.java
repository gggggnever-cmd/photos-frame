package com.gary.photosframe;

import java.util.Arrays;
import java.util.Random;

/**
 * Pure pixel work (int ARGB arrays, no Android) for the print filter, so it can be previewed on the PC
 * (tools/PrintPreview.java). Three pieces, added 2026-11-18 after the owner compared the filter with a
 * description of Frame-TV "paper" art:
 *
 * 1. Surface relief: the paper/canvas texture tiles are lit from the top left, so fibres and weave read as
 *    tiny raised bumps with a lit side and a shadow side (not flat noise). Paper also gets long fibres.
 * 2. Ink soak: a slight softening of fine detail, as if the ink spread into the paper.
 * 3. Paper cut: the photo rebuilt from a few flat colours (like cut sheets of coloured paper), shapes
 *    simplified, each sheet casting a soft shadow down-right onto the sheet below.
 */
final class PrintEffects {
    private PrintEffects() { }

    // ---- 1. Texture heights (512 x 512, any scale; ArtTreatment normalises them into tiles) ----

    static final int N = 512;

    /** Paper: fine tooth, a few long fibres, slow cloudiness, then lit from the top left. */
    static float[] paperHeight() {
        Random rnd = new Random(7);
        float[] n1 = gaussian(rnd, N), n2 = upscale(gaussian(rnd, 256), 256, 2), n8 = upscale(gaussian(rnd, 64), 64, 8);
        normStd(n2);
        normStd(n8);
        float[] h = new float[N * N];
        for (int i = 0; i < h.length; i++) h[i] = 0.6f * n1[i] + 0.8f * n2[i] + 0.12f * n8[i];
        // Fibres: short thin strands in random directions (wrapping, so the tile still repeats seamlessly).
        for (int f = 0; f < 900; f++) {
            float x = rnd.nextFloat() * N, y = rnd.nextFloat() * N;
            double a = rnd.nextDouble() * Math.PI;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a), bend = (float) (rnd.nextGaussian() * 0.02);
            int len = 10 + rnd.nextInt(30);
            float lift = 0.9f + rnd.nextFloat();
            for (int k = 0; k < len; k++) {
                int xi = Math.floorMod(Math.round(x), N), yi = Math.floorMod(Math.round(y), N);
                h[yi * N + xi] += lift;
                x += dx; y += dy;
                float ndx = dx - bend * dy, ndy = dy + bend * dx;
                dx = ndx; dy = ndy;
            }
        }
        return lit(blur1(h), 1.6f);
    }

    /** Canvas: plain weave with uneven threads, lit from the top left. */
    static float[] canvasHeight() {
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
        return lit(h, 1.0f);
    }

    /** Height + light from the top left: the slope towards the light brightens, away from it darkens. */
    static float[] lit(float[] h, float relief) {
        float[] out = new float[N * N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            float up = h[Math.floorMod(y - 1, N) * N + Math.floorMod(x - 1, N)];
            float down = h[Math.floorMod(y + 1, N) * N + Math.floorMod(x + 1, N)];
            out[y * N + x] = 0.5f * h[y * N + x] + relief * (down - up);
        }
        return out;
    }

    private static float[] blur1(float[] h) {
        float[] out = new float[N * N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            float s = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
                s += h[Math.floorMod(y + dy, N) * N + Math.floorMod(x + dx, N)] * (dx == 0 && dy == 0 ? 4 : (dx == 0 || dy == 0) ? 2 : 1);
            out[y * N + x] = s / 16f;
        }
        return out;
    }

    static float[] gaussian(Random rnd, int n) {
        float[] a = new float[n * n];
        for (int i = 0; i < a.length; i++) a[i] = (float) rnd.nextGaussian();
        return a;
    }

    /** Wrap-around bilinear upscale of an n x n field by factor f to N x N. */
    static float[] upscale(float[] src, int n, int f) {
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

    static void normStd(float[] a) {
        double mean = 0, var = 0;
        for (float v : a) mean += v;
        mean /= a.length;
        for (float v : a) var += (v - mean) * (v - mean);
        float sd = (float) Math.sqrt(var / a.length);
        if (sd > 0) for (int i = 0; i < a.length; i++) a[i] /= sd;
    }

    /** A height field as a grey tile: mean 128, sd 25.5 (what the OVERLAY strengths were tuned for). */
    static int[] tile(float[] h) {
        double mean = 0, var = 0;
        for (float v : h) mean += v;
        mean /= h.length;
        for (float v : h) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / h.length);
        int[] px = new int[h.length];
        for (int i = 0; i < h.length; i++) {
            int v = clamp((int) Math.round(128 + 25.5 * (h[i] - mean) / sd));
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return px;
    }

    // Strengths per filter level s (0..2).
    static float paperAlpha(float s) { return 0.08f + 0.08f * s; }
    static float canvasAlpha(float s) { return 0.10f + 0.10f * s; }
    /** Ink soak: how much fine detail is softened (never more than a light touch). */
    static float soak(float s) { return Math.min(0.45f, 0.10f + 0.15f * s); }
    /** Paper cut keeps its sheets colourful: its tone stops at the Moderate level. */
    static float cutToneLevel(float s) { return Math.min(s, 0.5f); }
    static float cutShadow(float s) { return Math.min(0.45f, 0.22f + 0.10f * s); }
    static int cutReach(float s, int h) { return Math.max(3, Math.round(h * (0.004f + 0.002f * Math.min(s, 2f)))); }

    /** Tone: saturation, then a per-channel curve ending at a warm paper white and an ink black. */
    static final class Tone {
        final int[] lutR = new int[256], lutG = new int[256], lutB = new int[256];
        final int rr, rg, rb, gr, gg, gb, br, bgc, bb;
        Tone(float s) {
            // Up to Strong (s = 1) as designed. Past it colour, ink and texture keep going but paper white
            // falls slowly: the matte panel is not bright, so don't dim much more.
            float sat = 1f - 0.20f * s;
            float white = s <= 1f ? 1f - 0.18f * s : 0.82f - 0.06f * (s - 1f);
            float[] paper = {white, white * (1f - 0.02f * s), white * (1f - 0.07f * s)};
            float ink = 0.10f * s, roll = 0.12f * s;
            int[][] luts = {lutR, lutG, lutB};
            for (int i = 0; i < 256; i++) {
                double x = i / 255.0;
                double f = x - roll * Math.sin(2 * Math.PI * x) / (2 * Math.PI);
                for (int ch = 0; ch < 3; ch++)
                    luts[ch][i] = clamp((int) Math.round(255 * paper[ch] * (ink + (1 - ink) * f)));
            }
            // ColorMatrix.setSaturation weights, 1/1024 fixed point.
            float a = 1f - sat;
            rr = Math.round(1024 * (0.213f * a + sat)); rg = Math.round(1024 * 0.715f * a); rb = Math.round(1024 * 0.072f * a);
            gr = Math.round(1024 * 0.213f * a); gg = Math.round(1024 * (0.715f * a + sat)); gb = Math.round(1024 * 0.072f * a);
            br = Math.round(1024 * 0.213f * a); bgc = Math.round(1024 * 0.715f * a); bb = Math.round(1024 * (0.072f * a + sat));
        }
        void apply(int[] px, int from, int to) {
            for (int i = from; i < to; i++) {
                int c = px[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int r2 = clamp((rr * r + rg * g + rb * b + 512) >> 10);
                int g2 = clamp((gr * r + gg * g + gb * b + 512) >> 10);
                int b2 = clamp((br * r + bgc * g + bb * b + 512) >> 10);
                px[i] = 0xFF000000 | (lutR[r2] << 16) | (lutG[g2] << 8) | lutB[b2];
            }
        }
    }

    /** PC preview only: what the TV's OVERLAY texture pass does, at the given alpha. */
    static void overlay(int[] px, int w, int h, int[] tile, float alpha) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int t = tile[(y % N) * N + (x % N)] & 0xFF;
            int c = px[y * w + x], o = 0xFF000000;
            for (int sh = 16; sh >= 0; sh -= 8) {
                int b = (c >> sh) & 0xFF;
                int v = b < 128 ? 2 * b * t / 255 : 255 - 2 * (255 - b) * (255 - t) / 255;
                o |= clamp(Math.round(b + alpha * (v - b))) << sh;
            }
            px[y * w + x] = o;
        }
    }

    // ---- 2. Ink soak ----

    /** Blends rows [from, to) of px with a 3x3 blur by `amount` (0..1). Reads src, writes dst. */
    static void soften(int[] src, int[] dst, int w, int h, float amount, int from, int to) {
        int keep = Math.round(256 * (1 - amount)), mix = 256 - keep;
        for (int y = from; y < to; y++) {
            int y0 = Math.max(0, y - 1), y1 = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 1), x1 = Math.min(w - 1, x + 1);
                int r = 0, g = 0, b = 0;
                for (int yy : new int[]{y0, y, y1}) for (int xx : new int[]{x0, x, x1}) {
                    int c = src[yy * w + xx];
                    r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
                }
                int c = src[y * w + x];
                int r2 = (((c >> 16) & 0xFF) * keep + (r / 9) * mix) >> 8;
                int g2 = (((c >> 8) & 0xFF) * keep + (g / 9) * mix) >> 8;
                int b2 = ((c & 0xFF) * keep + (b / 9) * mix) >> 8;
                dst[y * w + x] = 0xFF000000 | (r2 << 16) | (g2 << 8) | b2;
            }
        }
    }

    // ---- 3. Paper cut ----

    static final int CUT_COLOURS = 16;

    /** The flat-colour plan, worked out on a small copy (fast); then drawn at full size by cutRows. */
    static final class Cut {
        final int sw, sh;              // small copy size
        final float scale;             // full / small
        final int[] labels;            // small copy: which colour sheet each pixel belongs to
        final int[] palette;           // ARGB per sheet
        final int[] layer;             // stacking order per sheet (darker = higher, closer to the viewer)
        Cut(int sw, int sh, float scale, int[] labels, int[] palette, int[] layer) {
            this.sw = sw; this.sh = sh; this.scale = scale; this.labels = labels; this.palette = palette; this.layer = layer;
        }
    }

    static Cut planCut(int[] px, int w, int h) {
        int f = Math.max(1, Math.round(Math.max(w, h) / 800f));
        int sw = Math.max(1, w / f), sh = Math.max(1, h / f);
        // Small copy by area averaging, then blurred so fine detail (hair, leaves) melts into shapes.
        float[][] c = new float[3][sw * sh];
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
            int r = 0, g = 0, b = 0, n = 0;
            for (int yy = y * f; yy < Math.min(h, y * f + f); yy++) for (int xx = x * f; xx < Math.min(w, x * f + f); xx++) {
                int p = px[yy * w + xx];
                r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF; n++;
            }
            c[0][y * sw + x] = (float) r / n; c[1][y * sw + x] = (float) g / n; c[2][y * sw + x] = (float) b / n;
        }
        float[][] orig = {c[0].clone(), c[1].clone(), c[2].clone()};
        // Edge-keeping smoothing: fine texture (leaves, feathers, ripples) melts into flat areas while the
        // outlines between different colours stay sharp (a plain blur made muddy in-between bands).
        for (int pass = 0; pass < 2; pass++) c = bilateral(c, sw, sh, 2, 18f);

        // k-means on every second pixel (k-means++ start, fixed seed so a photo always cuts the same way).
        int k = CUT_COLOURS;
        int samples = (sw * sh + 1) / 2;
        float[][] centre = new float[k][3];
        Random rnd = new Random(42);
        int first = rnd.nextInt(samples) * 2;
        for (int ch = 0; ch < 3; ch++) centre[0][ch] = c[ch][first];
        float[] dist = new float[samples];
        for (int j = 1; j < k; j++) {
            double total = 0;
            for (int s = 0; s < samples; s++) {
                int i = s * 2;
                float best = Float.MAX_VALUE;
                for (int q = 0; q < j; q++) best = Math.min(best, d2(c, i, centre[q]));
                dist[s] = best;
                total += best;
            }
            double pick = rnd.nextDouble() * total;
            int chosen = samples - 1;
            for (int s = 0; s < samples; s++) { pick -= dist[s]; if (pick <= 0) { chosen = s; break; } }
            for (int ch = 0; ch < 3; ch++) centre[j][ch] = c[ch][chosen * 2];
        }
        for (int it = 0; it < 10; it++) {
            double[][] sum = new double[k][3];
            int[] count = new int[k];
            for (int s = 0; s < samples; s++) {
                int i = s * 2, q = nearest(c, i, centre);
                for (int ch = 0; ch < 3; ch++) sum[q][ch] += c[ch][i];
                count[q]++;
            }
            for (int q = 0; q < k; q++) if (count[q] > 0) for (int ch = 0; ch < 3; ch++) centre[q][ch] = (float) (sum[q][ch] / count[q]);
        }
        int[] labels = new int[sw * sh];
        for (int i = 0; i < labels.length; i++) labels[i] = nearest(c, i, centre);
        // A simple photo needs fewer sheets: near-identical colours become one (no banding in a plain sky).
        int[] same = new int[k];
        for (int q = 0; q < k; q++) {
            same[q] = q;
            for (int p = 0; p < q; p++) {
                if (same[p] != p) continue;
                float dr = centre[q][0] - centre[p][0], dg = centre[q][1] - centre[p][1], db = centre[q][2] - centre[p][2];
                if (dr * dr + dg * dg + db * db < 12 * 12) { same[q] = p; break; }
            }
        }
        for (int i = 0; i < labels.length; i++) labels[i] = same[labels[i]];
        for (int pass = 0; pass < 2; pass++) labels = majority(labels, sw, sh, k);   // tidy speckles into shapes
        mergeSmall(labels, sw, sh, k, Math.max(8, sw * sh / 8000));                 // no confetti

        // Each sheet's colour: the average of the real (unsmoothed) photo under it, a little more vivid,
        // like coloured paper.
        double[][] sum = new double[k][3];
        int[] count = new int[k];
        for (int i = 0; i < labels.length; i++) {
            for (int ch = 0; ch < 3; ch++) sum[labels[i]][ch] += orig[ch][i];
            count[labels[i]]++;
        }
        int[] palette = new int[k];
        Integer[] byLum = new Integer[k];
        float[] lum = new float[k];
        for (int q = 0; q < k; q++) {
            float[] m = new float[3];
            for (int ch = 0; ch < 3; ch++) m[ch] = count[q] == 0 ? centre[q][ch] : (float) (sum[q][ch] / count[q]);
            float grey = 0.299f * m[0] + 0.587f * m[1] + 0.114f * m[2];
            int r = Math.round(grey + 1.2f * (m[0] - grey)), g = Math.round(grey + 1.2f * (m[1] - grey)), b = Math.round(grey + 1.2f * (m[2] - grey));
            palette[q] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            lum[q] = 0.299f * r + 0.587f * g + 0.114f * b;
            byLum[q] = q;
        }
        Arrays.sort(byLum, (a, b) -> Float.compare(lum[b], lum[a]));   // brightest first = lowest sheet
        int[] layer = new int[k];
        for (int pos = 0; pos < k; pos++) layer[byLum[pos]] = pos;
        return new Cut(sw, sh, (float) w / sw, labels, palette, layer);
    }

    /**
     * Draws rows [from, to) of the cut at full size: each pixel takes the sheet that covers most of a
     * small soft neighbourhood (smooth, clean edges), then sheets above cast a soft shadow down-right.
     * shadow: 0..1 darkness of the shadows; reach: shadow length in pixels.
     */
    static void cutRows(Cut cut, int[] out, int w, int h, int from, int to, float shadow, int reach) {
        int top = Math.max(0, from - reach);
        int rows = to - top;
        int[] layerRows = new int[w * rows];
        float[] vote = new float[cut.palette.length];
        for (int y = top; y < to; y++) {
            float sy = (y + 0.5f) / cut.scale - 0.5f;
            int yc = (int) Math.floor(sy);
            for (int x = 0; x < w; x++) {
                float sx = (x + 0.5f) / cut.scale - 0.5f;
                int xc = (int) Math.floor(sx);
                Arrays.fill(vote, 0f);
                for (int dy = -1; dy <= 2; dy++) {
                    int yy = Math.max(0, Math.min(cut.sh - 1, yc + dy));
                    float wy = Math.max(0f, 1.5f - Math.abs(sy - (yc + dy)));
                    for (int dx = -1; dx <= 2; dx++) {
                        int xx = Math.max(0, Math.min(cut.sw - 1, xc + dx));
                        float wx = Math.max(0f, 1.5f - Math.abs(sx - (xc + dx)));
                        vote[cut.labels[yy * cut.sw + xx]] += wx * wy;
                    }
                }
                int best = 0;
                for (int q = 1; q < vote.length; q++) if (vote[q] > vote[best]) best = q;
                layerRows[(y - top) * w + x] = best;
            }
        }
        for (int y = from; y < to; y++) {
            for (int x = 0; x < w; x++) {
                int q = layerRows[(y - top) * w + x];
                int me = cut.layer[q];
                float shade = 0;
                for (int d = 1; d <= reach; d++) {
                    int yy = y - d, xx = x - d;
                    if (yy < top || xx < 0) break;
                    if (cut.layer[layerRows[(yy - top) * w + xx]] > me) { shade = 1f - (float) (d - 1) / reach; break; }
                }
                int c = cut.palette[q];
                float m = 1f - shadow * shade;
                int r = Math.round(((c >> 16) & 0xFF) * m), g = Math.round(((c >> 8) & 0xFF) * m), b = Math.round((c & 0xFF) * m);
                out[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static float d2(float[][] c, int i, float[] centre) {
        float dr = c[0][i] - centre[0], dg = c[1][i] - centre[1], db = c[2][i] - centre[2];
        return 0.30f * dr * dr + 0.59f * dg * dg + 0.11f * db * db;
    }

    private static int nearest(float[][] c, int i, float[][] centre) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int q = 0; q < centre.length; q++) {
            float d = d2(c, i, centre[q]);
            if (d < bd) { bd = d; best = q; }
        }
        return best;
    }

    private static int[] majority(int[] l, int w, int h, int k) {
        int[] out = new int[l.length];
        int[] count = new int[k];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            Arrays.fill(count, 0);
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int yy = Math.max(0, Math.min(h - 1, y + dy)), xx = Math.max(0, Math.min(w - 1, x + dx));
                count[l[yy * w + xx]]++;
            }
            int best = l[y * w + x];
            for (int q = 0; q < k; q++) if (count[q] > count[best]) best = q;
            out[y * w + x] = best;
        }
        return out;
    }

    /** One pass of a bilateral filter: averages neighbours of similar colour only (sr = colour spread). */
    private static float[][] bilateral(float[][] c, int w, int h, int r, float sr) {
        float[][] out = new float[3][w * h];
        float inv = -1f / (2 * sr * sr), invD = -1f / (2 * r * r);
        // Colour weights from a table (exp is slow on the TV): index = squared colour distance / 16.
        float[] range = new float[195075 / 16 + 2];
        for (int q = 0; q < range.length; q++) range[q] = (float) Math.exp(q * 16 * inv);
        float[] spatial = new float[(2 * r + 1) * (2 * r + 1)];
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++)
            spatial[(dy + r) * (2 * r + 1) + dx + r] = (float) Math.exp((dx * dx + dy * dy) * invD);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            float r0 = c[0][i], g0 = c[1][i], b0 = c[2][i];
            float sr2 = 0, sg = 0, sb = 0, sw = 0;
            for (int dy = -r; dy <= r; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    int j = yy * w + xx;
                    float dr = c[0][j] - r0, dg = c[1][j] - g0, db = c[2][j] - b0;
                    int q = Math.min(range.length - 1, (int) (dr * dr + dg * dg + db * db) >> 4);
                    float wgt = spatial[(dy + r) * (2 * r + 1) + dx + r] * range[q];
                    sr2 += wgt * c[0][j]; sg += wgt * c[1][j]; sb += wgt * c[2][j]; sw += wgt;
                }
            }
            out[0][i] = sr2 / sw; out[1][i] = sg / sw; out[2][i] = sb / sw;
        }
        return out;
    }

    /** Regions smaller than minArea take the label most common around them. */
    private static void mergeSmall(int[] l, int w, int h, int k, int minArea) {
        int[] region = new int[l.length];
        Arrays.fill(region, -1);
        int[] stack = new int[l.length], members = new int[l.length];
        int[] around = new int[k];
        int id = 0;
        for (int start = 0; start < l.length; start++) {
            if (region[start] >= 0) continue;
            int lab = l[start], n = 0, sp = 0;
            stack[sp++] = start;
            region[start] = id;
            Arrays.fill(around, 0);
            while (sp > 0) {
                int i = stack[--sp];
                members[n++] = i;
                int x = i % w, y = i / w;
                int[] nb = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int j : nb) {
                    if (j < 0) continue;
                    if (l[j] == lab) {
                        if (region[j] < 0) { region[j] = id; stack[sp++] = j; }
                    } else {
                        around[l[j]]++;
                    }
                }
            }
            if (n < minArea) {
                int best = -1;
                for (int q = 0; q < k; q++) if (around[q] > 0 && (best < 0 || around[q] > around[best])) best = q;
                if (best >= 0) for (int m = 0; m < n; m++) l[members[m]] = best;
            }
            id++;
        }
    }

    private static float[] boxBlur(float[] a, int w, int h, int r) {
        float[] tmp = new float[a.length], out = new float[a.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float s = 0; int n = 0;
            for (int d = -r; d <= r; d++) { int xx = x + d; if (xx >= 0 && xx < w) { s += a[y * w + xx]; n++; } }
            tmp[y * w + x] = s / n;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float s = 0; int n = 0;
            for (int d = -r; d <= r; d++) { int yy = y + d; if (yy >= 0 && yy < h) { s += tmp[yy * w + x]; n++; } }
            out[y * w + x] = s / n;
        }
        return out;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }
}
