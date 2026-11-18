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
    private final List<String> photos = new ArrayList<>();            // ids ("file:<path>" or "asset:<path>")
    private boolean own;

    // The current round.
    private final List<String> round = new ArrayList<>();             // ids in showing order
    private int next;                                                  // index of the next one to show
    private int roundNumber;
    private int roundOrder = -1;
    private boolean roundFavOnly;
    private String roundDay = "";                                     // chosen day the round was built for
    private long roundStart;                                           // wall clock ms when the round began

    private PhotoLibrary(Context ctx) {
        this.ctx = ctx;
        dir = new File(ctx.getFilesDir(), "library");
        dir.mkdirs();
        readMeta();
        readSet("hidden.txt", hidden);
        readSet("favourites.txt", favourites);
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
        if (roundOrder != s.order() || roundFavOnly != s.favouritesOnly() || next >= round.size()
                || (s.order() == Settings.ORDER_DAY && !s.day().equals(roundDay))) newRound(s);
        if (next >= round.size()) return null;
        String id = round.get(next++);
        writeRound();
        Log.i("PhotoLibrary", "showing " + next + " of " + round.size() + " in round " + roundNumber + " (" + dayOf(id) + ")");
        return id;
    }

    /**
     * Moves up to `want` more portraits from later in the round to be shown now beside `first`
     * (in One day at a time, only from the same day). Landscape photos keep their turn.
     */
    synchronized List<String> pullPortraits(String first, int want, boolean tallOnly) {
        List<String> out = new ArrayList<>();
        String day = roundOrder == Settings.ORDER_DAY ? dayOf(first) : null;
        for (int i = next; i < round.size() && out.size() < want; i++) {
            String id = round.get(i);
            float a = aspect(id);
            if (!(tallOnly ? a < TALL : a < PORTRAIT)) continue;
            if (day != null && !day.equals(dayOf(id))) continue;
            round.remove(i);
            round.add(next++, id);
            out.add(id);
        }
        if (!out.isEmpty()) writeRound();
        return out;
    }

    /** The days photos were taken on (oldest first) with how many photos each has, for the Day setting. */
    synchronized List<String[]> days(boolean favOnly) {
        Map<String, Integer> count = new HashMap<>();
        for (String id : photos) {
            if (!eligible(id, favOnly && !favourites.isEmpty())) continue;
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
        // Put the returned photos into the rest of this round at random places.
        for (String id : photos) if (back.contains(name(id)) && eligible(id, roundFavOnly)) insertLater(id);
        writeRound();
    }

    // ---- Rounds ----

    private boolean eligible(String id, boolean favOnly) {
        String n = name(id);
        if (hidden.contains(n)) return false;
        return !favOnly || favourites.contains(n);
    }

    private void newRound(Settings s) {
        boolean favOnly = s.favouritesOnly() && !favourites.isEmpty();   // no favourites yet: play everything
        List<String> pool = new ArrayList<>();
        for (String id : photos) if (eligible(id, favOnly)) pool.add(id);
        roundNumber++;
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
        next = 0;
        roundDay = s.order() == Settings.ORDER_DAY ? s.day() : "";
        roundStart = System.currentTimeMillis();
        Log.i("PhotoLibrary", "new round " + roundNumber + ": " + pool.size() + " photos, " + round.size()
                + " after merging bursts, order=" + Settings.ORDER_NAMES[s.order()] + ", favouritesOnly=" + favOnly);
        roundOrder = s.order();
        roundFavOnly = s.favouritesOnly();
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
                if (inRound.contains(id) || !eligible(id, roundFavOnly) || !id.startsWith("file:")) continue;
                if (new File(id.substring(5)).lastModified() > roundStart) insertLater(id);
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
            roundFavOnly = Boolean.parseBoolean(h[3]);
            roundStart = h.length > 4 ? Long.parseLong(h[4]) : System.currentTimeMillis();
            roundDay = h.length > 5 && !h[5].equals("-") ? h[5] : "";
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
          .append(roundFavOnly).append(' ').append(roundStart).append(' ')
          .append(roundDay.isEmpty() ? "-" : roundDay).append('\n');
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
