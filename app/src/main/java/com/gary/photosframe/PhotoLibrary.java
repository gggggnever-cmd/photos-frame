package com.gary.photosframe;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Everything the TV knows about the photos on it, kept across restarts (files/library/):
 * - meta.tsv: per photo its shape (width/height) and when it was taken (from the photo's own EXIF date).
 * - hidden.txt / favourites.txt: chosen with a long press on OK.
 * - round.txt: the current round's order and how far it has got, so a restart carries on instead of
 *   reshuffling ("every photo once before any repeat" holds across restarts, updates and power-offs).
 * - trips.tsv: names of the automatic date trips; files/albums.json (AlbumStore): membership albums. A photo
 *   in an album plays as part of that album only; every other photo keeps its date trip.
 *
 * A round contains one photo per burst (photos taken within BURST_SECONDS of the burst's first); the next
 * round shows a different member of each burst. Order is Shuffle, or One day at a time (days in random
 * order, each day's photos in the order taken).
 *
 * One instance per process (the app and the screensaver share it). All methods are synchronized.
 */
final class PhotoLibrary {
    static final float PORTRAIT = 0.9f;   // width/height below this counts as portrait
    static final float TALL = 0.66f;      // 9:16-ish phone portraits: three fit side by side
    // A burst: photos taken within 5 s of the burst's FIRST photo (not chained: a slow walk with a photo
    // every few seconds must not become one burst). Measured on the owner's 1990: 1092 per round.
    static final long BURST_SECONDS = 5;
    private static final long UNKNOWN = -1;

    private static PhotoLibrary instance;

    static synchronized PhotoLibrary get(Context ctx) {
        if (instance == null) instance = new PhotoLibrary(ctx.getApplicationContext());
        return instance;
    }

    private static final class Meta {
        final float aspect;
        final long taken;      // seconds since 1970 in the camera's local time, or UNKNOWN
        final String day;      // yyyy:MM:dd, or "" when unknown
        Meta(float aspect, long taken, String day) { this.aspect = aspect; this.taken = taken; this.day = day; }
    }

    private final Context ctx;
    private final File dir;
    private final Random random = new Random();
    private final Map<String, Meta> meta = new HashMap<>();          // by file name
    private final Set<String> hidden = new HashSet<>(), favourites = new HashSet<>();
    // Photo checker results (scores.tsv) and the owner's own corrections for Places only (places.txt).
    private final Map<String, Highlights.Score> scores = new HashMap<>();   // by file name
    private final Map<String, Boolean> placeMarks = new HashMap<>();        // by file name: true = a place
    private final List<String> photos = new ArrayList<>();            // ids ("file:<path>" or "asset:<path>")
    private boolean own;

    // The current round.
    private final List<String> round = new ArrayList<>();             // ids in showing order
    private int next;                                                  // index of the next one to show
    private int roundNumber;
    private int roundOrder = -1;
    private int roundPlay;                                             // Play choice the round was built for
    private int roundUse;                                              // what it actually plays (after fallbacks)
    private int roundHighlights;                                       // highlights per trip it was built for
    private boolean rebuild;                                           // highlights changed: new round next time
    private String roundDay = "";                                     // chosen day the round was built for
    private String roundTrips = "";                                   // trips the round was built for ("" = all)

    // Trips (see Trips.java): remembered entries (names, last played, cleared) and the trips computed now.
    private final List<Trips.Entry> entries = new ArrayList<>();
    private final List<Trips.Trip> trips = new ArrayList<>();
    private final Map<String, Trips.Trip> tripOf = new HashMap<>();   // file name → trip or album
    private long tripsWritten;

    // Membership albums (AlbumStore): their photos leave the date trips and play as one album.
    private final AlbumStore albums;
    private final List<Trips.Trip> albumGroups = new ArrayList<>();   // each album's photos on the TV now
    private final Map<AlbumStore.Album, Set<String>> albumDays = new HashMap<>();   // days its photos were taken
    private long albumsWritten;
    private boolean albumsDirty;
    private long roundStart;                                           // wall clock ms when the round began

    private PhotoLibrary(Context ctx) {
        this.ctx = ctx;
        dir = new File(ctx.getFilesDir(), "library");
        dir.mkdirs();
        readMeta();
        readSet("hidden.txt", hidden);
        readSet("favourites.txt", favourites);
        readScores();
        readEntries();
        albums = new AlbumStore(new File(ctx.getFilesDir(), "albums.json"));
        scanFiles();
        readRound();          // restore the saved round (and fit new photos into it) before anything writes it
    }

    /** Rescans the photo folder (after adding photos): reads dates of new photos, fits them into the round. */
    synchronized void refresh() {
        scanFiles();
        reconcileRound();
    }

    private void scanFiles() {
        photos.clear();
        File[] files = Storage.photoDir(ctx).listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".jpg")) photos.add("file:" + f.getAbsolutePath());
        own = !photos.isEmpty();
        if (!own) {
            try {
                String[] names = ctx.getAssets().list("photos");
                if (names != null) for (String n : names) photos.add("asset:photos/" + n);
            } catch (IOException ignored) { }
        }
        long t0 = SystemClock.uptimeMillis();
        int added = 0;
        for (String id : photos) if (!meta.containsKey(name(id))) { meta.put(name(id), readMetaFromFile(id)); added++; }
        if (added > 0) {
            writeMeta();
            Log.i("PhotoLibrary", "read dates of " + added + " photos in " + (SystemClock.uptimeMillis() - t0) + " ms");
        }
        computeTrips();
    }

    // ---- Trips and albums ----

    private void computeTrips() {
        trips.clear();
        tripOf.clear();
        albumGroups.clear();
        albumDays.clear();
        if (!own) return;
        // Photos in a membership album belong to it only; the rest form the date trips exactly as before.
        List<String> loose = new ArrayList<>();
        Map<AlbumStore.Album, List<String>> inAlbum = new LinkedHashMap<>();
        for (String id : photos) {
            AlbumStore.Album a = albums.albumOf(name(id));
            if (a == null) { loose.add(id); continue; }
            List<String> l = inAlbum.get(a);
            if (l == null) inAlbum.put(a, l = new ArrayList<>());
            l.add(id);
        }
        List<String> days = new ArrayList<>();
        for (String id : loose) days.add(dayOf(id));
        for (String[] r : Trips.cluster(days)) trips.add(new Trips.Trip(r[0], r[1]));
        int t = 0;
        List<String> sorted = new ArrayList<>(loose);
        sorted.sort(Comparator.comparing(this::dayOf));
        for (String id : sorted) {
            String d = dayOf(id);
            if (d.isEmpty()) continue;
            while (t < trips.size() - 1 && d.compareTo(trips.get(t).end) > 0) t++;
            trips.get(t).ids.add(id);
            tripOf.put(name(id), trips.get(t));
        }
        boolean changed = false;
        for (Trips.Trip trip : trips) {
            Trips.Entry e = Trips.best(entries, trip.start, trip.end);
            if (e == null) {
                e = new Trips.Entry(trip.start, trip.end, "", 0, false, 0);
                entries.add(e);
                changed = true;
            } else if (e.gone || trip.start.compareTo(e.start) < 0 || trip.end.compareTo(e.end) > 0) {
                if (trip.start.compareTo(e.start) < 0) e.start = trip.start;
                if (trip.end.compareTo(e.end) > 0) e.end = trip.end;
                e.gone = false;
                changed = true;
            }
            trip.entry = e;
        }
        if (changed) writeEntries();

        // Albums are dated by their own photos (for the list's order and label), like trips.
        boolean albumsChanged = false;
        for (Map.Entry<AlbumStore.Album, List<String>> en : inAlbum.entrySet()) {
            AlbumStore.Album a = en.getKey();
            List<String> ids = en.getValue();
            ids.sort(Comparator.comparingLong(this::takenOf));
            Set<String> seen = new HashSet<>();
            String start = "", end = "";
            for (String id : ids) {
                String d = dayOf(id);
                if (d.isEmpty()) continue;
                seen.add(d);
                if (start.isEmpty() || d.compareTo(start) < 0) start = d;
                if (end.isEmpty() || d.compareTo(end) > 0) end = d;
            }
            Trips.Trip g = new Trips.Trip(start, end, a);
            g.ids.addAll(ids);
            for (String id : ids) tripOf.put(name(id), g);
            albumGroups.add(g);
            albumDays.put(a, seen);
            if (a.gone || !start.equals(a.start) || !end.equals(a.end)) {
                a.gone = false;            // its photos are back (picked again)
                a.start = start;
                a.end = end;
                albumsChanged = true;
            }
        }
        if (albumsChanged) saveAlbums();
    }

    /** True when the photo belongs to one of the chosen trips or albums (or nothing is chosen). */
    private boolean inChosenTrips(String id, String chosen) {
        if (chosen.isEmpty()) return true;
        Trips.Trip t = tripOf.get(name(id));
        if (t == null) return false;
        if (t.album != null) return AlbumStore.chosenAlbum(chosen, t.album.id);
        return AlbumStore.chosenRange(chosen, t.start, t.end);
    }

    /**
     * For "Albums to play": date trips and membership albums on the TV (newest first), then the ones cleared
     * to make room. Each row: {start, end, label, photo count, "gone" or "", key, "album" or "trip"}, where
     * key is how the setting names it ("start~end" or "album:<id>").
     */
    synchronized List<String[]> tripRows() {
        List<String[]> on = new ArrayList<>(), gone = new ArrayList<>();
        for (Trips.Trip t : trips)
            on.add(new String[]{t.start, t.end, t.label(), String.valueOf(t.ids.size()), "", t.key(), "trip"});
        for (Trips.Trip g : albumGroups)
            on.add(new String[]{g.start, g.end, g.label(), String.valueOf(g.ids.size()), "", g.key(), "album"});
        for (Trips.Entry e : entries) if (e.gone)
            gone.add(new String[]{e.start, e.end, Trips.label(e.start, e.end, e.name, e.goneCount), String.valueOf(e.goneCount),
                    "gone", e.start + "~" + e.end, "trip"});
        for (AlbumStore.Album a : albums.all()) if (a.gone)
            gone.add(new String[]{a.start, a.end, Trips.label(a.start, a.end, a.name, a.goneCount), String.valueOf(a.goneCount),
                    "gone", AlbumStore.token(a.id), "album"});
        Comparator<String[]> newestFirst = (a, b) -> b[0].compareTo(a[0]);
        on.sort(newestFirst);
        gone.sort(newestFirst);
        on.addAll(gone);
        return on;
    }

    /** The trip or album a photo belongs to: {start, end, label, key}, or null (an undated photo in no album). */
    synchronized String[] tripOfPhoto(String id) {
        Trips.Trip t = tripOf.get(name(id));
        return t == null ? null : new String[]{t.start, t.end, t.label(), t.key()};
    }

    /** Names the remembered trip that best matches [start, end] (creating it if new). Empty name clears it. */
    synchronized void nameTrip(String start, String end, String newName) {
        Trips.Entry e = Trips.best(entries, start, end);
        if (e == null) {
            e = new Trips.Entry(start, end, "", 0, false, 0);
            entries.add(e);
        }
        e.name = newName == null ? "" : newName.trim();
        writeEntries();
    }

    /** Renames by key ("start~end" or "album:<id>"). An album keeps its old name when the new one is empty. */
    synchronized void rename(String key, String newName) {
        String album = AlbumStore.albumId(key);
        if (album != null) {
            if (albums.rename(album, newName)) saveAlbums();
            return;
        }
        String[] r = key.split("~");
        if (r.length == 2) nameTrip(r[0], r[1], newName);
    }

    /**
     * Where a pick with these dates would go by default: the album or trip it overlaps most, as
     * {key, label, overlap days, album id or "", "named" or "", start, end}, or null when nothing overlaps.
     * An album counts the days it really has photos on (an album gathered over years must not swallow
     * every new pick); a trip counts its whole date range. Albums win a tie.
     */
    synchronized String[] bestMatch(String start, String end) {
        String[] best = null;
        int most = 0;
        for (Trips.Trip t : trips) {
            int o = Trips.overlap(start, end, t.start, t.end);
            if (o > most) { most = o; best = new String[]{t.key(), t.label(), String.valueOf(o), "", t.name().isEmpty() ? "" : "named", t.start, t.end}; }
        }
        for (Trips.Entry e : entries) {
            if (!e.gone) continue;   // a trip cleared for space comes back with its name when picked again
            int o = Trips.overlap(start, end, e.start, e.end);
            if (o > most) {
                most = o;
                best = new String[]{e.start + "~" + e.end, Trips.label(e.start, e.end, e.name, e.goneCount), String.valueOf(o), "",
                        e.name.isEmpty() ? "" : "named", e.start, e.end};
            }
        }
        for (Trips.Trip g : albumGroups) {
            int o = 0;
            for (String d : albumDays.get(g.album)) if (Trips.overlap(start, end, d, d) > 0) o++;
            if (o > 0 && o >= most) { most = o; best = new String[]{g.key(), g.album.name, String.valueOf(o), g.album.id, "named", g.start, g.end}; }
        }
        return best;
    }

    /** Every membership album for the Add photos choice: {id, name, photo count}, newest first. */
    synchronized List<String[]> albumChoices() {
        List<AlbumStore.Album> list = new ArrayList<>(albums.all());
        list.sort((a, b) -> Long.compare(b.created, a.created));
        List<String[]> out = new ArrayList<>();
        for (AlbumStore.Album a : list) out.add(new String[]{a.id, a.name, String.valueOf(a.files.size())});
        return out;
    }

    /**
     * What the phone's "Add to an album" lists (2026-11-25): membership albums and NAMED date trips (an unnamed
     * "Nov 2016 · 5 days" isn't something the owner would pick by name). {key, name, photo count, label}, newest first.
     */
    synchronized List<String[]> namedChoices() {
        List<Trips.Trip> all = new ArrayList<>(albumGroups);
        for (Trips.Trip t : trips) if (!t.name().isEmpty()) all.add(t);
        all.sort((a, b) -> b.start.compareTo(a.start));
        List<String[]> out = new ArrayList<>();
        for (Trips.Trip t : all) out.add(new String[]{t.key(), t.name(), String.valueOf(t.ids.size()), t.label()});
        return out;
    }

    /**
     * Turns a named date trip into a membership album of the same name holding its photos, so photos from OTHER
     * dates can join it (a date trip is only a date range). Returns the album id, or null if no such trip.
     */
    synchronized String albumFromTrip(String key) {
        for (Trips.Trip t : trips) {
            if (!t.key().equals(key)) continue;
            String name = t.name().isEmpty() ? Trips.label(t.start, t.end, "", t.ids.size()) : t.name();
            String id = createAlbum(name);
            List<String> files = new ArrayList<>();
            for (String p : t.ids) files.add(name(p));
            addToAlbum(id, files, true);
            Log.i("PhotoLibrary", "trip " + key + " is now album " + id + " (" + files.size() + " photos)");
            return id;
        }
        return null;
    }

    /** A new album with this name (or the existing one of that name). Returns its id. */
    synchronized String createAlbum(String name) {
        AlbumStore.Album a = albums.named(name);
        if (a == null) a = albums.create(name, System.currentTimeMillis());
        saveAlbums();
        return a.id;
    }

    /**
     * Puts photo files in an album (out of any other); they leave the date trips. Called per photo while a
     * batch downloads, so the file is written at most every few seconds unless saveNow.
     */
    synchronized void addToAlbum(String albumId, Collection<String> fileNames, boolean saveNow) {
        if (albums.add(albumId, fileNames) > 0) {
            albumsDirty = true;
            Set<String> names = new HashSet<>(fileNames);
            for (String id : photos) if (names.contains(name(id))) { computeTrips(); break; }   // already on the TV: regroup now
        }
        if (albumsDirty && (saveNow || System.currentTimeMillis() - albumsWritten > 5000)) saveAlbums();
    }

    synchronized void flushAlbums() { if (albumsDirty) saveAlbums(); }

    /**
     * One-time import (adb test extra): every photo file that arrived between from and to (lastModified, ms)
     * goes into the album of this name, created if needed. Returns {photos in the range, newly added}.
     */
    synchronized int[] importAlbum(String name, long from, long to) {
        List<String> names = new ArrayList<>();
        File[] files = Storage.photoDir(ctx).listFiles();
        if (files != null) for (File f : files) {
            long m = f.lastModified();
            if (f.getName().endsWith(".jpg") && m >= from && m <= to) names.add(f.getName());
        }
        AlbumStore.Album a = albums.named(name);
        if (a == null) a = albums.create(name, System.currentTimeMillis());
        int added = albums.add(a.id, names);
        saveAlbums();
        scanFiles();
        reconcileRound();
        Log.i("AlbumImport", "album \"" + a.name + "\" (" + a.id + "): " + names.size() + " photos arrived in the range, "
                + added + " newly added, " + a.files.size() + " in the album now");
        return new int[]{names.size(), added};
    }

    private void saveAlbums() {
        albums.save();
        albumsWritten = System.currentTimeMillis();
        albumsDirty = false;
    }

    private void markPlayed(String id) {
        Trips.Trip t = tripOf.get(name(id));
        if (t == null) return;
        long now = System.currentTimeMillis();
        if (t.album != null) {
            t.album.lastPlayed = now;
            if (now - albumsWritten > 10 * 60_000L) saveAlbums();   // keep writes rare
            return;
        }
        if (t.entry == null) return;
        t.entry.lastPlayed = now;
        if (now - tripsWritten > 10 * 60_000L) writeEntries();   // keep writes rare
    }

    /** Total size of the photos on the TV, in bytes. */
    synchronized long photoBytes() {
        long sum = 0;
        for (String id : photos) if (id.startsWith("file:")) sum += new File(id.substring(5)).length();
        return sum;
    }

    /**
     * Clears the least-recently-played trip or album from the TV to make room: deletes its photos and
     * remembers it as "gone" (name and album membership kept, so picking them again brings it back).
     * Never touches trips overlapping [keepStart, keepEnd], the album keepAlbum (the batch downloading),
     * or the chosen trips and albums. Returns the bytes freed (0 if nothing could be cleared).
     */
    synchronized long clearLeastPlayedTrip(String keepStart, String keepEnd, String keepAlbum, String chosen) {
        Trips.Trip victim = null;
        List<Trips.Trip> all = new ArrayList<>(trips);
        all.addAll(albumGroups);
        for (Trips.Trip t : all) {
            if (t.album != null && t.album.id.equals(keepAlbum)) continue;
            if (t.album == null && keepStart != null && Trips.overlap(t.start, t.end, keepStart, keepEnd) > 0) continue;
            if (!chosen.isEmpty() && !t.ids.isEmpty() && inChosenTrips(t.ids.get(0), chosen)) continue;
            if (victim == null || t.lastPlayed() < victim.lastPlayed()) victim = t;
        }
        if (victim == null) return 0;
        long freed = 0;
        for (String id : victim.ids) {
            File f = new File(id.substring(5));
            long len = f.length();
            if (f.delete()) freed += len;
            favourites.remove(name(id));
        }
        if (victim.album != null) {
            victim.album.gone = true;
            victim.album.goneCount = victim.ids.size();
            saveAlbums();
        } else {
            victim.entry.gone = true;
            victim.entry.goneCount = victim.ids.size();
            writeEntries();
        }
        writeSet("favourites.txt", favourites);
        Log.i("PhotoLibrary", "cleared " + (victim.album != null ? "album " : "trip ") + victim.label() + " to make room: "
                + victim.ids.size() + " photos, " + freed / 1_000_000 + " MB");
        scanFiles();
        reconcileRound();
        return freed;
    }

    synchronized boolean hasOwnPhotos() { return own; }

    synchronized float aspect(String id) {
        Meta m = meta.get(name(id));
        return m == null ? 1.5f : m.aspect;
    }

    /**
     * The next photo of the round, starting a new round when this one is used up or the order/play
     * settings changed. Null when there are no photos to show.
     */
    synchronized String take(Settings s) {
        boolean high = s.play() == Settings.PLAY_HIGHLIGHTS;
        if (rebuild || roundPlay != s.play() || next >= round.size() || !s.trips().equals(roundTrips)
                || (high && roundHighlights != s.highlightCount())
                || (!high && roundOrder != s.order())
                || (!high && s.order() == Settings.ORDER_DAY && !s.day().equals(roundDay))) newRound(s);
        if (next >= round.size()) return null;
        String id = round.get(next++);
        writeRound();
        markPlayed(id);
        Log.i("PhotoLibrary", "showing " + next + " of " + round.size() + " in round " + roundNumber + " (" + dayOf(id) + ")");
        return id;
    }

    /**
     * Moves up to `want` more portraits from later in the round to be shown now beside `first`
     * (in One day at a time, only from the same day). Landscape photos keep their turn.
     */
    synchronized List<String> pullPortraits(String first, int want, boolean tallOnly) {
        List<String> out = new ArrayList<>();
        boolean high = roundUse == Settings.PLAY_HIGHLIGHTS;
        String day = !high && roundOrder == Settings.ORDER_DAY ? dayOf(first) : null;
        Trips.Trip trip = high ? tripOf.get(name(first)) : null;
        for (int i = next; i < round.size() && out.size() < want; i++) {
            String id = round.get(i);
            float a = aspect(id);
            if (!(tallOnly ? a < TALL : a < PORTRAIT)) continue;
            if (day != null && !day.equals(dayOf(id))) continue;
            if (high && tripOf.get(name(id)) != trip) continue;   // a trip's story stays with its own photos
            round.remove(i);
            round.add(next++, id);
            out.add(id);
        }
        if (!out.isEmpty()) writeRound();
        return out;
    }

    /** The days photos were taken on (oldest first) with how many photos each has, for the Day setting. */
    synchronized List<String[]> days(int play) {
        Map<String, Integer> count = new HashMap<>();
        int use = effectivePlay(play);
        for (String id : photos) {
            if (!eligible(id, use == Settings.PLAY_HIGHLIGHTS ? Settings.PLAY_ALL : use)) continue;
            String d = dayOf(id);
            if (d.isEmpty()) continue;
            Integer c = count.get(d);
            count.put(d, c == null ? 1 : c + 1);
        }
        List<String> keys = new ArrayList<>(count.keySet());
        Collections.sort(keys);
        List<String[]> out = new ArrayList<>();
        for (String k : keys) out.add(new String[]{k, String.valueOf(count.get(k))});
        return out;
    }

    // ---- Favourites and hidden ----

    synchronized boolean isFavourite(String id) { return favourites.contains(name(id)); }

    /** Returns the new state. */
    synchronized boolean toggleFavourite(String id) {
        String n = name(id);
        boolean now = !favourites.remove(n);
        if (now) favourites.add(n);
        writeSet("favourites.txt", favourites);
        return now;
    }

    synchronized int favouriteCount() { return favourites.size(); }

    synchronized void hide(String id) {
        hidden.add(name(id));
        writeSet("hidden.txt", hidden);
        reconcileRound();
    }

    synchronized boolean isHidden(String id) { return hidden.contains(name(id)); }

    synchronized int hiddenCount() { return hidden.size(); }

    synchronized void unhideAll() {
        Set<String> back = new HashSet<>(hidden);
        hidden.clear();
        writeSet("hidden.txt", hidden);
        if (roundUse == Settings.PLAY_HIGHLIGHTS) { rebuild = true; return; }
        // Put the returned photos into the rest of this round at random places.
        for (String id : photos) if (back.contains(name(id)) && eligible(id, roundUse)) insertLater(id);
        writeRound();
    }

    // ---- Places only and Highlights (photo checker results) ----

    /** Photos not checked yet (the photo checker works through these). */
    synchronized List<String> unscored() {
        List<String> out = new ArrayList<>();
        for (String id : photos) {
            if (!id.startsWith("file:")) continue;
            Highlights.Score s = scores.get(name(id));
            if (s == null || s.body < 0) out.add(id);   // never checked, or checked before the person finder
        }
        return out;
    }

    /** The checker's earlier result for a photo (to reuse faces/sharpness when only the person finder is new). */
    synchronized Highlights.Score score(String id) { return scores.get(name(id)); }

    /** {checked, total} for the Settings page while the checker runs. */
    synchronized int[] checkProgress() {
        int done = 0, total = 0;
        for (String id : photos) if (id.startsWith("file:")) {
            total++;
            Highlights.Score s = scores.get(name(id));
            if (s != null && s.body >= 0) done++;
        }
        return new int[]{done, total};
    }

    /** The checker reports a batch. New places join a Places-only round; Highlights are rebuilt when done. */
    synchronized void addScores(Map<String, Highlights.Score> batch, boolean finished) {
        for (Map.Entry<String, Highlights.Score> e : batch.entrySet()) scores.put(name(e.getKey()), e.getValue());
        writeScores();
        if (roundUse == Settings.PLAY_PLACES) {
            Set<String> inRound = new HashSet<>(round);
            for (String id : batch.keySet()) if (!inRound.contains(id) && eligible(id, Settings.PLAY_PLACES)) insertLater(id);
            writeRound();
        } else if (roundPlay == Settings.PLAY_PLACES && placeCount() > 0) {
            rebuild = true;    // was playing everything until the first places were known
        }
        if (finished && roundUse == Settings.PLAY_HIGHLIGHTS && !batch.isEmpty()) rebuild = true;
    }

    /** True = counts as a place, false = has people, null = not checked yet. The owner's own mark wins. */
    synchronized Boolean isPlace(String id) { return place(id); }

    private Boolean place(String id) {
        String n = name(id);
        Boolean mark = placeMarks.get(n);
        if (mark != null) return mark;
        Highlights.Score s = scores.get(n);
        return s == null ? null : Highlights.isPlace(s);
    }

    /** Photo menu: flips whether a photo counts as a place. Returns the new state. */
    synchronized boolean togglePlace(String id) {
        Boolean now = place(id);
        boolean next = now == null || !now;
        placeMarks.put(name(id), next);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : placeMarks.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue() ? 1 : 0).append('\n');
        writeText("places.txt", sb.toString());
        return next;
    }

    synchronized int placeCount() {
        int n = 0;
        for (String id : photos) if (!hidden.contains(name(id)) && Boolean.TRUE.equals(place(id))) n++;
        return n;
    }

    // ---- Rounds ----

    private boolean eligible(String id, int play) {
        String n = name(id);
        if (hidden.contains(n)) return false;
        if (!inChosenTrips(id, roundTrips)) return false;
        if (play == Settings.PLAY_FAV) return favourites.contains(n);
        if (play == Settings.PLAY_PLACES) return Boolean.TRUE.equals(place(id));
        return true;
    }

    /** The Play choice, falling back to all photos when it would show nothing (no favourites, no places yet). */
    private int effectivePlay(int play) {
        if (play == Settings.PLAY_FAV && favourites.isEmpty()) return Settings.PLAY_ALL;
        if (play == Settings.PLAY_PLACES) {
            for (String id : photos) if (eligible(id, Settings.PLAY_PLACES)) return play;
            return Settings.PLAY_ALL;
        }
        return play;
    }

    /** Highlights round: each trip's best photos in the order taken; the trips in random order. */
    private List<String> highlightRound(List<String> pool, int perTrip) {
        Map<Trips.Trip, List<Highlights.Cand>> byTrip = new LinkedHashMap<>();
        for (String id : pool) {
            Trips.Trip t = tripOf.get(name(id));
            if (t == null) continue;                                    // undated: not part of any trip
            List<Highlights.Cand> l = byTrip.get(t);
            if (l == null) byTrip.put(t, l = new ArrayList<>());
            l.add(new Highlights.Cand(id, takenOf(id), dayOf(id), favourites.contains(name(id)), scores.get(name(id))));
        }
        List<Trips.Trip> order = new ArrayList<>(byTrip.keySet());
        Collections.shuffle(order, random);
        List<String> out = new ArrayList<>();
        for (Trips.Trip t : order) {
            List<String> picked = Highlights.select(byTrip.get(t), perTrip, BURST_SECONDS);
            Log.i("PhotoLibrary", "highlights of " + t.label() + ": " + picked.size() + " of " + byTrip.get(t).size());
            out.addAll(picked);
        }
        return out;
    }

    private void newRound(Settings s) {
        rebuild = false;
        roundTrips = s.trips();
        boolean any = false;
        for (String id : photos) if (inChosenTrips(id, roundTrips)) { any = true; break; }
        if (!any) roundTrips = "";                                     // chosen trips not on the TV: play all
        roundPlay = s.play();
        roundHighlights = s.highlightCount();
        int use = effectivePlay(roundPlay);
        List<String> pool = new ArrayList<>();
        for (String id : photos) if (eligible(id, use == Settings.PLAY_HIGHLIGHTS ? Settings.PLAY_ALL : use)) pool.add(id);
        roundNumber++;
        if (use == Settings.PLAY_HIGHLIGHTS) {
            List<String> story = highlightRound(pool, roundHighlights);
            if (!story.isEmpty()) {
                round.clear();
                round.addAll(story);
                finishRound(s, use, pool.size());
                return;
            }
            use = Settings.PLAY_ALL;                                    // no dated photos: play everything
        }
        List<String> reps = burstRepresentatives(pool, roundNumber);
        round.clear();
        String chosen = s.order() == Settings.ORDER_DAY ? s.day() : "";
        if (!chosen.isEmpty()) {
            // One chosen day, in the order taken; it starts again (with other burst members) when done.
            List<String> day = new ArrayList<>();
            for (String id : reps) if (chosen.equals(dayOf(id))) day.add(id);
            if (!day.isEmpty()) {
                day.sort(Comparator.comparingLong(this::takenOf));
                reps = day;
            } else {
                chosen = "";   // nothing left on that day (all hidden?): fall back to any day
            }
        }
        if (s.order() == Settings.ORDER_DAY && !chosen.isEmpty()) {
            round.addAll(reps);
        } else if (s.order() == Settings.ORDER_DAY) {
            Map<String, List<String>> byDay = new LinkedHashMap<>();
            for (String id : reps) {
                String d = dayOf(id);
                List<String> l = byDay.get(d);
                if (l == null) byDay.put(d, l = new ArrayList<>());
                l.add(id);
            }
            List<String> days = new ArrayList<>(byDay.keySet());
            Collections.shuffle(days, random);
            for (String d : days) {
                List<String> l = byDay.get(d);
                if (d.isEmpty()) Collections.shuffle(l, random);
                else l.sort(Comparator.comparingLong(this::takenOf));
                round.addAll(l);
            }
        } else {
            round.addAll(reps);
            Collections.shuffle(round, random);
        }
        finishRound(s, use, pool.size());
    }

    private void finishRound(Settings s, int use, int poolSize) {
        next = 0;
        roundUse = use;
        roundDay = s.order() == Settings.ORDER_DAY ? s.day() : "";
        roundStart = System.currentTimeMillis();
        roundOrder = s.order();
        Log.i("PhotoLibrary", "new round " + roundNumber + ": " + poolSize + " photos, " + round.size()
                + " in the round, order=" + Settings.ORDER_NAMES[s.order()] + ", play=" + Settings.PLAY_NAMES[use]
                + (use != roundPlay ? " (asked " + Settings.PLAY_NAMES[roundPlay] + ")" : ""));
        writeRound();
    }

    /** One photo per burst; which member rotates with the round number. Undated photos stand alone. */
    private List<String> burstRepresentatives(List<String> pool, int roundNo) {
        List<String> dated = new ArrayList<>(), out = new ArrayList<>();
        for (String id : pool) if (takenOf(id) == UNKNOWN) out.add(id); else dated.add(id);
        dated.sort(Comparator.comparingLong(this::takenOf));
        int i = 0;
        while (i < dated.size()) {
            int j = i + 1;
            long start = takenOf(dated.get(i));
            while (j < dated.size() && takenOf(dated.get(j)) - start <= BURST_SECONDS) j++;
            out.add(dated.get(i + Math.floorMod(roundNo, j - i)));
            i = j;
        }
        return out;
    }

    /** Fits the saved round to the photos that exist now: drop gone/hidden ones, add new ones later in the round. */
    private void reconcileRound() {
        Set<String> present = new HashSet<>(photos);
        for (int i = round.size() - 1; i >= 0; i--) {
            String id = round.get(i);
            if (!present.contains(id) || hidden.contains(name(id))) {
                round.remove(i);
                if (i < next) next--;
            }
        }
        if (roundOrder >= 0) {
            // Photos downloaded after this round began join the rest of it. (Older photos not in the round
            // are burst siblings or were filtered out on purpose; they come in later rounds.)
            Set<String> inRound = new HashSet<>(round);
            for (String id : photos) {
                if (inRound.contains(id) || !eligible(id, roundUse) || !id.startsWith("file:")) continue;
                if (new File(id.substring(5)).lastModified() <= roundStart) continue;
                if (roundUse == Settings.PLAY_HIGHLIGHTS) { rebuild = true; break; }   // pick highlights again
                insertLater(id);
            }
        }
        writeRound();
    }

    private void insertLater(String id) {
        int at = next + random.nextInt(round.size() - next + 1);
        round.add(at, id);
    }

    // ---- Dates ----

    private long takenOf(String id) {
        Meta m = meta.get(name(id));
        return m == null ? UNKNOWN : m.taken;
    }

    private String dayOf(String id) {
        Meta m = meta.get(name(id));
        return m == null ? "" : m.day;
    }

    private Meta readMetaFromFile(String id) {
        float aspect = 1.5f;
        long taken = UNKNOWN;
        String day = "";
        try (InputStream in = open(id)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            if (o.outWidth > 0 && o.outHeight > 0) aspect = (float) o.outWidth / o.outHeight;
        } catch (IOException ignored) { }
        try (InputStream in = open(id)) {
            ExifInterface exif = new ExifInterface(in);
            String dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (dt == null) dt = exif.getAttribute(ExifInterface.TAG_DATETIME);
            if (dt != null && dt.length() >= 19) {
                SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));   // keep the camera's local wall time
                taken = f.parse(dt.substring(0, 19)).getTime() / 1000;
                day = dt.substring(0, 10);
            }
        } catch (IOException | ParseException | RuntimeException ignored) { }
        return new Meta(aspect, taken, day);
    }

    private InputStream open(String id) throws IOException {
        if (id.startsWith("file:")) return new FileInputStream(id.substring(5));
        return ctx.getAssets().open(id.substring(6));
    }

    private static String name(String id) {
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    // ---- Saving ----

    private void readMeta() {
        for (String line : readLines("meta.tsv")) {
            String[] p = line.split("\t", -1);
            if (p.length < 4) continue;
            try { meta.put(p[0], new Meta(Float.parseFloat(p[1]), Long.parseLong(p[2]), p[3])); } catch (NumberFormatException ignored) { }
        }
    }

    private void writeMeta() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Meta> e : meta.entrySet())
            sb.append(e.getKey()).append('\t').append(e.getValue().aspect).append('\t')
              .append(e.getValue().taken).append('\t').append(e.getValue().day).append('\n');
        writeText("meta.tsv", sb.toString());
    }

    /** scores.tsv: name, faces, largest face, sharpness, brightness, tallest person (missing in older files). places.txt: name, 1 (a place) / 0 (people). */
    private void readScores() {
        for (String line : readLines("scores.tsv")) {
            String[] p = line.split("\t", -1);
            if (p.length < 5) continue;
            try { scores.put(p[0], Highlights.Score.decode(p, 1)); } catch (NumberFormatException ignored) { }
        }
        for (String line : readLines("places.txt")) {
            String[] p = line.split("\t", -1);
            if (p.length >= 2) placeMarks.put(p[0], "1".equals(p[1]));
        }
    }

    private void writeScores() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Highlights.Score> e : scores.entrySet())
            sb.append(e.getKey()).append('\t').append(e.getValue().encode()).append('\n');
        writeText("scores.tsv", sb.toString());
    }

    /** trips.tsv: start, end, name, lastPlayed, gone (0/1), goneCount. */
    private void readEntries() {
        for (String line : readLines("trips.tsv")) {
            String[] p = line.split("\t", -1);
            if (p.length < 6) continue;
            try {
                entries.add(new Trips.Entry(p[0], p[1], p[2], Long.parseLong(p[3]), "1".equals(p[4]), Integer.parseInt(p[5])));
            } catch (NumberFormatException ignored) { }
        }
    }

    private void writeEntries() {
        StringBuilder sb = new StringBuilder();
        for (Trips.Entry e : entries)
            sb.append(e.start).append('\t').append(e.end).append('\t').append(e.name.replace('\t', ' ').replace('\n', ' '))
              .append('\t').append(e.lastPlayed).append('\t').append(e.gone ? "1" : "0").append('\t').append(e.goneCount).append('\n');
        writeText("trips.tsv", sb.toString());
        tripsWritten = System.currentTimeMillis();
    }

    private void readSet(String file, Set<String> into) {
        for (String line : readLines(file)) if (!line.isEmpty()) into.add(line);
    }

    private void writeSet(String file, Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) sb.append(s).append('\n');
        writeText(file, sb.toString());
    }

    /** round.txt: "next roundNumber order favOnly roundStart roundDay" then one file name per line. */
    private void readRound() {
        List<String> lines = readLines("round.txt");
        if (lines.isEmpty()) return;
        String[] h = lines.get(0).split(" ");
        if (h.length < 4) return;
        Map<String, String> byName = new HashMap<>();
        for (String id : photos) byName.put(name(id), id);
        round.clear();
        for (int i = 1; i < lines.size(); i++) {
            String id = byName.get(lines.get(i));
            if (id != null) round.add(id);
        }
        try {
            next = Math.min(round.size(), Integer.parseInt(h[0]));
            roundNumber = Integer.parseInt(h[1]);
            roundOrder = Integer.parseInt(h[2]);
            // h[3] was "true"/"false" (favourites only) before v0.14, now the Play choice number.
            roundPlay = "true".equals(h[3]) ? Settings.PLAY_FAV : "false".equals(h[3]) ? Settings.PLAY_ALL : Integer.parseInt(h[3]);
            roundUse = h.length > 7 ? Integer.parseInt(h[7]) : roundPlay;
            roundHighlights = h.length > 8 ? Integer.parseInt(h[8]) : 0;
            roundStart = h.length > 4 ? Long.parseLong(h[4]) : System.currentTimeMillis();
            roundDay = h.length > 5 && !h[5].equals("-") ? h[5] : "";
            roundTrips = h.length > 6 && !h[6].equals("-") ? h[6] : "";
        } catch (NumberFormatException e) {
            round.clear();
            next = 0;
            roundOrder = -1;
        }
        reconcileRound();
    }

    private void writeRound() {
        if (!own) return;
        StringBuilder sb = new StringBuilder();
        sb.append(next).append(' ').append(roundNumber).append(' ').append(roundOrder).append(' ')
          .append(roundPlay).append(' ').append(roundStart).append(' ')
          .append(roundDay.isEmpty() ? "-" : roundDay).append(' ')
          .append(roundTrips.isEmpty() ? "-" : roundTrips).append(' ')
          .append(roundUse).append(' ').append(roundHighlights).append('\n');
        for (String id : round) sb.append(name(id)).append('\n');
        writeText("round.txt", sb.toString());
    }

    private List<String> readLines(String file) {
        List<String> out = new ArrayList<>();
        File f = new File(dir, file);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (IOException ignored) { }
        return out;
    }

    /** Write to a temp file and rename, so a power cut never leaves a half-written file. */
    private void writeText(String file, String text) {
        File tmp = new File(dir, file + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(text);
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(new File(dir, file))) tmp.delete();
    }
}
