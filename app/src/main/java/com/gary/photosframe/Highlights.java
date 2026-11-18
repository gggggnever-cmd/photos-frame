package com.gary.photosframe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure logic (no Android) for the owner's two "which photos" ideas, so it can be tested on the PC:
 *
 * Places only: a photo counts as a place when nobody is its subject: no face, or only small faces
 * (passers-by in a street scene). There is no face recognition, so "us" and strangers look the same.
 *
 * Highlights: the best N photos of a trip, as a short story in the order taken. Google gives no "best of"
 * data, so the TV's own signals are used: favourites (always in), how many shots the owner took of a scene
 * (a burst means it mattered; the best member of the burst is used), sharpness and exposure (ranked within
 * the trip, so a dim trip isn't punished), people in the photo (moments, not just scenery), every day of the
 * trip represented in proportion to how much was shot that day, and no two photos from the same minute or so.
 */
final class Highlights {
    /** Largest face height as a share of the photo's long side from which a person is "in" the photo. */
    static final float PERSON_FACE = 0.025f;
    /** Two highlights closer together than this are near-duplicates (avoided unless nothing else is left). */
    static final long NEAR_SECONDS = 90;
    static final int[] COUNTS = {20, 30, 50, 100};

    /** What the photo checker measured for one photo. */
    static final class Score {
        final int faces;
        final float maxFace;    // largest face height / photo's long side (0 if none)
        final float sharp;      // variance of the Laplacian on a ~960 px grey copy (bigger = sharper)
        final float bright;     // mean brightness 0..1
        Score(int faces, float maxFace, float sharp, float bright) {
            this.faces = faces; this.maxFace = maxFace; this.sharp = sharp; this.bright = bright;
        }
        String encode() { return faces + "\t" + maxFace + "\t" + sharp + "\t" + bright; }
        static Score decode(String[] p, int from) {
            return new Score(Integer.parseInt(p[from]), Float.parseFloat(p[from + 1]),
                    Float.parseFloat(p[from + 2]), Float.parseFloat(p[from + 3]));
        }
        boolean hasPerson() { return faces > 0 && maxFace >= PERSON_FACE; }
    }

    static boolean isPlace(Score s) { return !s.hasPerson(); }

    /** One photo of a trip offered to the selection. score may be null (not checked yet). */
    static final class Cand {
        final String id, day;
        final long taken;
        final boolean favourite;
        final Score score;
        Cand(String id, long taken, String day, boolean favourite, Score score) {
            this.id = id; this.taken = taken; this.day = day; this.favourite = favourite; this.score = score;
        }
    }

    /**
     * Picks about n highlights from one trip (favourites are always in, even beyond n).
     * Candidates must be dated and not hidden. Returns ids in the order taken.
     */
    static List<String> select(List<Cand> cands, int n, long burstSeconds) {
        List<Cand> sorted = new ArrayList<>(cands);
        sorted.sort(Comparator.comparingLong(c -> c.taken));

        // Sharpness is ranked within the trip (0..1), so one blurry-ish phone doesn't sink a whole trip.
        List<Float> sharps = new ArrayList<>();
        for (Cand c : sorted) if (c.score != null) sharps.add(c.score.sharp);
        Collections.sort(sharps);

        // Bursts (anchored, like the round): keep the best member and remember how many shots the scene got.
        Map<Cand, Double> quality = new HashMap<>();
        List<Cand> reps = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i + 1;
            while (j < sorted.size() && sorted.get(j).taken - sorted.get(i).taken <= burstSeconds) j++;
            Cand best = null;
            double bestQ = -1;
            for (int k = i; k < j; k++) {
                Cand c = sorted.get(k);
                double q = quality(c, sharps) + Math.min(1.0, 0.35 * log2(j - i)) + (c.favourite ? 10 : 0);
                if (q > bestQ) { bestQ = q; best = c; }
            }
            reps.add(best);
            quality.put(best, bestQ);
            i = j;
        }
        if (reps.size() <= n) return ids(reps);

        // Every day gets a share in proportion to how many scenes were shot that day (at least one each).
        Map<String, List<Cand>> byDay = new LinkedHashMap<>();
        for (Cand c : reps) {
            List<Cand> l = byDay.get(c.day);
            if (l == null) byDay.put(c.day, l = new ArrayList<>());
            l.add(c);
        }
        List<String> days = new ArrayList<>(byDay.keySet());
        Map<String, Integer> quota = new HashMap<>();
        if (days.size() >= n) {
            for (int k = 0; k < n; k++) quota.put(days.get((int) ((long) k * days.size() / n)), 1);   // spread out
        } else {
            int extra = n - days.size();
            int rest = reps.size() - days.size();
            Map<String, Double> remainder = new HashMap<>();
            int given = 0;
            for (String d : days) {
                double share = rest == 0 ? 0 : (double) extra * (byDay.get(d).size() - 1) / rest;
                int whole = (int) Math.floor(share);
                quota.put(d, 1 + whole);
                remainder.put(d, share - whole);
                given += whole;
            }
            List<String> byRemainder = new ArrayList<>(days);
            byRemainder.sort((a, b) -> Double.compare(remainder.get(b), remainder.get(a)));
            for (int k = 0; k < extra - given && k < byRemainder.size(); k++)
                quota.put(byRemainder.get(k), quota.get(byRemainder.get(k)) + 1);
        }

        Comparator<Cand> best = (a, b) -> Double.compare(quality.get(b), quality.get(a));
        Set<Cand> picked = new HashSet<>();
        List<Cand> leftovers = new ArrayList<>();
        int open = 0;
        for (String d : days) {
            List<Cand> l = new ArrayList<>(byDay.get(d));
            l.sort(best);
            int q = quota.containsKey(d) ? quota.get(d) : 0;
            int got = 0;
            for (Cand c : l) if (c.favourite) { picked.add(c); got++; }          // favourites: always in
            got += fill(l, q - got, picked, true);
            got += fill(l, q - got, picked, false);                                // allow near-duplicates if needed
            if (got < q) open += q - got;
            for (Cand c : l) if (!picked.contains(c)) leftovers.add(c);
        }
        // Days that had fewer scenes than their share: give the rest to the best photos anywhere in the trip.
        leftovers.sort(best);
        int added = fill(leftovers, open, picked, true);
        fill(leftovers, open - added, picked, false);

        List<Cand> out = new ArrayList<>(picked);
        out.sort(Comparator.comparingLong(c -> c.taken));
        return ids(out);
    }

    /** Adds up to `want` from l (best first) to picked; with avoidNear, skips photos close in time to a pick. */
    private static int fill(List<Cand> l, int want, Set<Cand> picked, boolean avoidNear) {
        int got = 0;
        for (Cand c : l) {
            if (got >= want) break;
            if (picked.contains(c)) continue;
            if (avoidNear && nearAny(c, picked)) continue;
            picked.add(c);
            got++;
        }
        return got;
    }

    private static boolean nearAny(Cand c, Set<Cand> picked) {
        for (Cand p : picked) if (Math.abs(p.taken - c.taken) < NEAR_SECONDS) return true;
        return false;
    }

    /** 0..~1.9: sharpness rank, sensible exposure, and people in the photo. Unchecked photos score middling. */
    static double quality(Cand c, List<Float> sortedSharps) {
        Score s = c.score;
        if (s == null) return 0.5 + 0.5 * 0.75;
        double rank = sortedSharps.size() <= 1 ? 0.5 : rankOf(sortedSharps, s.sharp);
        double exposure;
        if (s.bright < 0.2f) exposure = Math.max(0, (s.bright - 0.05) / 0.15);
        else if (s.bright > 0.8f) exposure = Math.max(0, (0.95 - s.bright) / 0.15);
        else exposure = 1;
        return rank + 0.5 * exposure + (s.hasPerson() ? 0.4 : 0);
    }

    private static double rankOf(List<Float> sorted, float v) {
        int lo = 0, hi = sorted.size();
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (sorted.get(mid) < v) lo = mid + 1; else hi = mid; }
        return (double) lo / (sorted.size() - 1);
    }

    private static double log2(int x) { return Math.log(x) / Math.log(2); }

    private static List<String> ids(List<Cand> l) {
        List<String> out = new ArrayList<>();
        for (Cand c : l) out.add(c.id);
        return out;
    }
}
