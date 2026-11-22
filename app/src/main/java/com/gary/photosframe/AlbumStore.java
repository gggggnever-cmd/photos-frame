package com.gary.photosframe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Albums by membership (the owner, 2026-11-22: trips and albums are one idea). An album is an id, a name
 * and the set of photo file names the owner put in it. A photo belongs to one album at most; photos in no
 * album keep the automatic date trips (Trips.java).
 *
 * Saved as albums.json, written to a temp file and moved into place so a power cut never leaves half a
 * file. Plain Java (no Android), so it is tested on the PC. Not thread-safe: PhotoLibrary owns it and
 * calls it under its own lock.
 *
 * The "Albums to play" setting mixes date ranges and albums in one string: "start~end;album:<id>;...".
 */
final class AlbumStore {
    static final String TOKEN = "album:";

    static final class Album {
        final String id;
        String name;
        final long created;
        long lastPlayed;
        boolean gone;          // cleared from the TV to make room (photos deleted, membership kept)
        int goneCount;         // how many photos it had when cleared
        String start = "", end = "";   // the dates of its photos on the TV ("yyyy:MM:dd"), "" if none dated
        final Set<String> files = new LinkedHashSet<>();
        Album(String id, String name, long created) { this.id = id; this.name = name; this.created = created; }
    }

    private final File file;
    private final List<Album> albums = new ArrayList<>();
    private final Map<String, Album> byFile = new HashMap<>();

    AlbumStore(File file) {
        this.file = file;
        load();
    }

    List<Album> all() { return albums; }

    Album get(String id) {
        for (Album a : albums) if (a.id.equals(id)) return a;
        return null;
    }

    /** The album with this name (ignoring case), or null. */
    Album named(String name) {
        for (Album a : albums) if (a.name.equalsIgnoreCase(name.trim())) return a;
        return null;
    }

    /** The album a photo file is in, or null. */
    Album albumOf(String fileName) { return byFile.get(fileName); }

    /** A new, empty album. Ids are short and use no spaces or separators, so they fit in a setting token. */
    Album create(String name, long now) {
        String base = "a" + Long.toString(now, 36), id = base;
        for (int i = 2; get(id) != null; i++) id = base + "-" + i;
        Album a = new Album(id, name.trim(), now);
        // Counts as just played, so a new album isn't the first thing cleared when space runs out.
        a.lastPlayed = now;
        albums.add(a);
        return a;
    }

    /** Puts the files in the album, taking them out of any other (one album per photo). Returns how many moved. */
    int add(String id, Collection<String> names) {
        Album a = get(id);
        if (a == null) return 0;
        int moved = 0;
        for (String n : names) {
            Album was = byFile.get(n);
            if (was == a) continue;
            if (was != null) was.files.remove(n);
            a.files.add(n);
            byFile.put(n, a);
            moved++;
        }
        return moved;
    }

    boolean rename(String id, String name) {
        Album a = get(id);
        if (a == null || name == null || name.trim().isEmpty()) return false;
        a.name = name.trim();
        return true;
    }

    // ---- The "Albums to play" setting ----

    static String token(String id) { return TOKEN + id; }

    /** The album id in a token like "album:a1b2", or null for a date range ("start~end"). */
    static String albumId(String token) { return token.startsWith(TOKEN) ? token.substring(TOKEN.length()) : null; }

    static String[] tokens(String chosen) {
        if (chosen == null || chosen.isEmpty()) return new String[0];
        List<String> out = new ArrayList<>();
        for (String t : chosen.split(";")) if (!t.trim().isEmpty()) out.add(t.trim());
        return out.toArray(new String[0]);
    }

    /** True when the setting ticks this album. */
    static boolean chosenAlbum(String chosen, String id) {
        for (String t : tokens(chosen)) if (id.equals(albumId(t))) return true;
        return false;
    }

    /** True when the setting ticks a date range overlapping [start, end] (album tokens never match a range). */
    static boolean chosenRange(String chosen, String start, String end) {
        for (String t : tokens(chosen)) {
            if (albumId(t) != null) continue;
            String[] r = t.split("~");
            if (r.length == 2 && Trips.overlap(start, end, r[0], r[1]) > 0) return true;
        }
        return false;
    }

    // ---- Saving ----

    private void load() {
        albums.clear();
        byFile.clear();
        if (!file.exists()) return;
        try {
            Object root = TinyJson.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            Object list = ((Map<?, ?>) root).get("albums");
            if (!(list instanceof List)) return;
            for (Object o : (List<?>) list) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                String id = str(m.get("id"));
                if (id.isEmpty() || get(id) != null) continue;
                Album a = new Album(id, str(m.get("name")), num(m.get("created")));
                a.lastPlayed = num(m.get("lastPlayed"));
                a.gone = Boolean.TRUE.equals(m.get("gone"));
                a.goneCount = (int) num(m.get("goneCount"));
                a.start = str(m.get("start"));
                a.end = str(m.get("end"));
                Object files = m.get("files");
                if (files instanceof List) for (Object f : (List<?>) files) if (f instanceof String && !byFile.containsKey(f)) {
                    a.files.add((String) f);
                    byFile.put((String) f, a);
                }
                albums.add(a);
            }
        } catch (IOException | RuntimeException e) {
            // A damaged file must not stop the frame: the photos simply fall back to their date trips.
            System.err.println("AlbumStore: couldn't read " + file + ": " + e);
        }
    }

    /** Writes albums.json atomically. Returns false if it couldn't be written. */
    boolean save() {
        StringBuilder b = new StringBuilder("{\"version\":1,\"albums\":[");
        for (int i = 0; i < albums.size(); i++) {
            Album a = albums.get(i);
            if (i > 0) b.append(',');
            b.append("\n{\"id\":").append(TinyJson.quote(a.id))
             .append(",\"name\":").append(TinyJson.quote(a.name))
             .append(",\"created\":").append(a.created)
             .append(",\"lastPlayed\":").append(a.lastPlayed)
             .append(",\"gone\":").append(a.gone)
             .append(",\"goneCount\":").append(a.goneCount)
             .append(",\"start\":").append(TinyJson.quote(a.start))
             .append(",\"end\":").append(TinyJson.quote(a.end))
             .append(",\"files\":[");
            boolean first = true;
            for (String f : a.files) { b.append(first ? "" : ",").append(TinyJson.quote(f)); first = false; }
            b.append("]}");
        }
        b.append("\n]}\n");
        File tmp = new File(file.getPath() + ".tmp");
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(tmp.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
    }

    private static String str(Object o) { return o instanceof String ? (String) o : ""; }
    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
}
