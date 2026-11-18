package com.gary.photosframe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class HighlightsTest {
    private static final long DAY = 86400;

    private static Highlights.Score score(float sharp, float bright, int faces, float maxFace) {
        return new Highlights.Score(faces, maxFace, sharp, bright);
    }

    /** A 5-day trip: day 1 busy (60 scenes), the others 10 each, one scene every 10 minutes. */
    private static List<Highlights.Cand> trip(Random r) {
        List<Highlights.Cand> l = new ArrayList<>();
        int[] perDay = {60, 10, 10, 10, 10};
        int id = 0;
        for (int d = 0; d < perDay.length; d++) {
            for (int k = 0; k < perDay[d]; k++) {
                long t = d * DAY + 8 * 3600 + k * 600L;
                l.add(new Highlights.Cand("p" + id++, t, "2025:11:0" + (d + 1), false,
                        score(r.nextFloat() * 500, 0.3f + r.nextFloat() * 0.4f, 0, 0)));
            }
        }
        return l;
    }

    @Test public void picksAboutNCoversEveryDayInTimeOrder() {
        List<Highlights.Cand> c = trip(new Random(1));
        List<String> out = Highlights.select(c, 30, 5);
        assertEquals(30, out.size());
        Set<String> days = new HashSet<>();
        long last = -1;
        for (String id : out) {
            Highlights.Cand x = find(c, id);
            days.add(x.day);
            assertTrue("time order", x.taken >= last);
            last = x.taken;
        }
        assertEquals(5, days.size());
        // The busy day gets the biggest share (proportional), the quiet days at least one.
        int day1 = 0;
        for (String id : out) if (find(c, id).day.equals("2025:11:01")) day1++;
        assertTrue("busy day share " + day1, day1 >= 15);
    }

    @Test public void favouritesAlwaysIn() {
        List<Highlights.Cand> c = trip(new Random(2));
        Highlights.Cand blurryFav = new Highlights.Cand("fav", 2 * DAY + 3600, "2025:11:03", true, score(0, 0.02f, 0, 0));
        c.add(blurryFav);
        assertTrue(Highlights.select(c, 20, 5).contains("fav"));
    }

    @Test public void oneShotPerBurstAndTheSharpestOne() {
        List<Highlights.Cand> c = new ArrayList<>();
        for (int k = 0; k < 4; k++) c.add(new Highlights.Cand("b" + k, 1000 + k, "2025:11:01", false, score(k == 2 ? 900 : 10, 0.5f, 0, 0)));
        for (int k = 0; k < 40; k++) c.add(new Highlights.Cand("o" + k, 5000 + k * 600L, "2025:11:01", false, score(100, 0.5f, 0, 0)));
        List<String> out = Highlights.select(c, 10, 5);
        int burst = 0;
        for (String id : out) if (id.startsWith("b")) burst++;
        assertTrue("at most one of the burst", burst <= 1);
        assertTrue("the burst scene (4 shots) is in, as its sharpest member", out.contains("b2"));
    }

    @Test public void avoidsNearDuplicates() {
        List<Highlights.Cand> c = new ArrayList<>();
        // 30 photos 20 s apart (one scene, not a burst) and 10 spread-out photos, all equally good.
        for (int k = 0; k < 30; k++) c.add(new Highlights.Cand("n" + k, 1000 + k * 20L, "2025:11:01", false, score(100, 0.5f, 0, 0)));
        for (int k = 0; k < 10; k++) c.add(new Highlights.Cand("s" + k, 10000 + k * 1200L, "2025:11:01", false, score(100, 0.5f, 0, 0)));
        List<String> out = Highlights.select(c, 12, 5);
        assertEquals(12, out.size());
        int near = 0;
        for (String id : out) if (id.startsWith("n")) near++;
        assertTrue("near-duplicates kept few: " + near, near <= 7);
    }

    @Test public void smallTripPlaysWhole() {
        List<Highlights.Cand> c = new ArrayList<>();
        for (int k = 0; k < 8; k++) c.add(new Highlights.Cand("x" + k, k * 600L, "2025:11:01", false, null));
        assertEquals(8, Highlights.select(c, 30, 5).size());
    }

    @Test public void moreDaysThanNStillSpreads() {
        List<Highlights.Cand> c = new ArrayList<>();
        for (int d = 0; d < 40; d++) c.add(new Highlights.Cand("d" + d, d * DAY, String.format("day%02d", d), false, null));
        List<String> out = Highlights.select(c, 20, 5);
        assertEquals(20, out.size());
        assertTrue(out.contains("d0"));
    }

    @Test public void placesRule() {
        assertTrue(Highlights.isPlace(score(1, 0.5f, 0, 0)));
        assertTrue("small passer-by", Highlights.isPlace(score(1, 0.5f, 3, 0.012f)));
        assertFalse("someone posing", Highlights.isPlace(score(1, 0.5f, 1, 0.06f)));
    }

    @Test public void scoreRoundTrip() {
        Highlights.Score s = score(123.5f, 0.42f, 2, 0.031f);
        Highlights.Score back = Highlights.Score.decode(("name\t" + s.encode()).split("\t"), 1);
        assertEquals(2, back.faces);
        assertEquals(0.031f, back.maxFace, 1e-6);
        assertEquals(123.5f, back.sharp, 1e-3);
    }

    private static Highlights.Cand find(List<Highlights.Cand> c, String id) {
        for (Highlights.Cand x : c) if (x.id.equals(id)) return x;
        throw new AssertionError(id);
    }
}
