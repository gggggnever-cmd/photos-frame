package com.gary.photosframe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class PrintEffectsTest {
    /** Two flat halves (sky and ground) with noise: the cut keeps a clean edge and few colours. */
    @Test public void paperCutMakesFewFlatColoursWithShadowBelowTheTopSheet() {
        int w = 600, h = 400;
        int[] px = new int[w * h];
        Random r = new Random(3);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean ground = y >= h / 2;
            int n = r.nextInt(21) - 10;
            int base = ground ? 0x3A5A2A : 0x9CC8F0;
            int rr = ((base >> 16) & 0xFF) + n, gg = ((base >> 8) & 0xFF) + n, bb = (base & 0xFF) + n;
            px[y * w + x] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }
        PrintEffects.Cut cut = PrintEffects.planCut(px, w, h);
        int[] out = new int[w * h];
        PrintEffects.cutRows(cut, out, w, h, 0, h, 0.4f, 6);
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) colours.add(out[y * w + x]);
        assertTrue("few colours, got " + colours.size(), colours.size() <= PrintEffects.CUT_COLOURS * 8);
        // Far from the edge each half is one flat colour.
        assertEquals(out[50 * w + 50], out[60 * w + 400]);
        assertEquals(out[350 * w + 50], out[380 * w + 500]);
        // The darker ground sheet sits on top of the sky, so nothing shades the sky; the ground is not shaded either
        // (its shadow would fall down-right onto sheets below it, and there are none).
        assertEquals(out[(h / 2 - 3) * w + 300], out[50 * w + 50]);
    }

    @Test public void softenKeepsFlatAreasAndToneIsMonotonic() {
        int w = 10, h = 10;
        int[] flat = new int[w * h], out = new int[w * h];
        java.util.Arrays.fill(flat, 0xFF808080);
        PrintEffects.soften(flat, out, w, h, 0.4f, 0, h);
        for (int v : out) assertEquals(0xFF808080, v);
        PrintEffects.Tone t = new PrintEffects.Tone(2f);
        for (int i = 1; i < 256; i++) assertTrue(t.lutR[i] >= t.lutR[i - 1]);
    }

    @Test public void tilesAreNormalised() {
        int[] tile = PrintEffects.tile(PrintEffects.paperHeight());
        double sum = 0;
        for (int p : tile) sum += p & 0xFF;
        assertEquals(128, sum / tile.length, 2);
    }
}
