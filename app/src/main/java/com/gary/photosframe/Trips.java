package com.gary.photosframe;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Trips are found from the dates photos were taken: consecutive shooting days belong together, and a gap
 * of more than MAX_EMPTY_DAYS days without photos starts a new trip. Nothing is assigned by hand.
 *
 * Names (and "last played", "cleared from the TV") are remembered per date range in an Entry, not per
 * computed trip, so a trip keeps its name when more photos are added, when it grows by a day, or when it
 * is cleared and picked again later. A computed trip takes the entry it overlaps most.
 *
 * Days are "yyyy:MM:dd" strings (the photos' own EXIF date format).
 */
final class Trips {
    static final int MAX_EMPTY_DAYS = 2;

    /** A remembered date range: its name (may be empty), when it last played, and whether it was cleared. */
    static final class Entry {
        String start, end, name;
        long lastPlayed;
        boolean gone;        // cleared from the TV to make room (photos deleted, name kept)
        int goneCount;       // how many photos it had when cleared
        Entry(String start, String end, String name, long lastPlayed, boolean gone, int goneCount) {
            this.start = start; this.end = end; this.name = name;
            this.lastPlayed = lastPlayed; this.gone = gone; this.goneCount = goneCount;
        }
    }

    /**
     * A trip as computed from the photos on the TV right now, or a membership album's photos on the TV
     * (album set). Both play, rank highlights and get cleared for space the same way.
     */
    static final class Trip {
        final String start, end;
        final List<String> ids = new ArrayList<>();
        Entry entry;             // a date trip's remembered entry (always set after computing); null for an album
        final AlbumStore.Album album;   // null for a date trip
        Trip(String start, String end) { this(start, end, null); }
        Trip(String start, String end, AlbumStore.Album album) { this.start = start; this.end = end; this.album = album; }
        int days() { return dayNumber(end) - dayNumber(start) + 1; }
        String name() { return album != null ? album.name : entry == null ? "" : entry.name; }
        String label() { return Trips.label(start, end, name(), ids.size()); }
        long lastPlayed() { return album != null ? album.lastPlayed : entry == null ? 0 : entry.lastPlayed; }
        /** How the "Albums to play" setting names it: "album:<id>" or "start~end". */
        String key() { return album != null ? AlbumStore.token(album.id) : start + "~" + end; }
    }

    /**
     * Groups distinct days into trips (sorted oldest first). Returns [start, end] pairs.
     * Undated photos are not included; the caller keeps them as their own group.
     */
    static List<String[]> cluster(List<String> days) {
        List<String> d = new ArrayList<>();
        for (String s : days) if (s != null && !s.isEmpty()) d.add(s);
        Collections.sort(d);
        List<String[]> out = new ArrayList<>();
        String start = null, prev = null;
        for (String day : d) {
            if (day.equals(prev)) continue;
            if (start == null) start = day;
            else if (dayNumber(day) - dayNumber(prev) > MAX_EMPTY_DAYS + 1) {
                out.add(new String[]{start, prev});
                start = day;
            }
            prev = day;
        }
        if (start != null) out.add(new String[]{start, prev});
        return out;
    }

    /** Days the two ranges share (0 if none). One day of slack, since picker dates are UTC. */
    static int overlap(String aStart, String aEnd, String bStart, String bEnd) {
        int s = Math.max(dayNumber(aStart), dayNumber(bStart) - 1), e = Math.min(dayNumber(aEnd), dayNumber(bEnd) + 1);
        return Math.max(0, e - s + 1);
    }

    /** The entry overlapping [start, end] the most, or null. */
    static Entry best(List<Entry> entries, String start, String end) {
        Entry best = null;
        int most = 0;
        for (Entry e : entries) {
            int o = overlap(start, end, e.start, e.end);
            if (o > most) { most = o; best = e; }
        }
        return best;
    }

    /** "Egypt · Nov 2025", or "Nov 2025 · 12 days" when unnamed. */
    static String label(String start, String end, String name, int photos) {
        boolean named = name != null && !name.isEmpty();
        if (start == null || start.isEmpty()) return named ? name : "Undated photos";   // an album with no dated photos
        String when = months(start, end);
        if (name != null && !name.isEmpty()) return name + "  ·  " + when;
        int days = dayNumber(end) - dayNumber(start) + 1;
        return when + "  ·  " + days + (days == 1 ? " day" : " days");
    }

    /** "Nov 2025", "Oct–Nov 2025" or "Dec 2025–Jan 2026". */
    static String months(String start, String end) {
        String[] m = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        try {
            int y1 = Integer.parseInt(start.substring(0, 4)), m1 = Integer.parseInt(start.substring(5, 7));
            int y2 = Integer.parseInt(end.substring(0, 4)), m2 = Integer.parseInt(end.substring(5, 7));
            if (y1 != y2) return m[m1 - 1] + " " + y1 + "–" + m[m2 - 1] + " " + y2;
            if (m1 != m2) return m[m1 - 1] + "–" + m[m2 - 1] + " " + y1;
            return m[m1 - 1] + " " + y1;
        } catch (RuntimeException e) {
            return start;
        }
    }

    /** "2025-11-13T08:12:00Z" (picker createTime, UTC) → "2025:11:13". */
    static String dayFromCreateTime(String createTime) {
        if (createTime == null || createTime.length() < 10) return "";
        return createTime.substring(0, 4) + ":" + createTime.substring(5, 7) + ":" + createTime.substring(8, 10);
    }

    /** Days since 1970 for "yyyy:MM:dd". */
    static int dayNumber(String day) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return (int) (f.parse(day).getTime() / 86_400_000L);
        } catch (ParseException | RuntimeException e) {
            return 0;
        }
    }
}
