package com.gary.photosframe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads picked photos in the background, one batch (one Google Photos pick) after another, so the
 * owner can pick 2,000, then the next 1,500, then more, without waiting for each to arrive (asked
 * 2026-11-20). The Add photos screen hands each pick here and shows the QR code again straight away.
 *
 * Google's photo links (baseUrl) only work for 60 minutes, and a big queue takes longer than that, so the
 * pick's session is kept open until its batch is done and its list is fetched again (fresh links) before
 * each chunk once the old links are 45 minutes old. The waiting sessions are saved in
 * files/download_queue.txt, so the queue carries on after the app restarts while Google still has them.
 *
 * Each batch carries the membership album it goes into ("" = the automatic date trips). The owner may
 * choose the album on the TV after the batch was queued (setAlbum); every photo saved from then on, and
 * every one already saved, goes into it.
 */
final class DownloadQueue {
    private static final String TAG = "DownloadQueue";
    private static final long FRESH_MS = 45 * 60 * 1000;
    private static final int CHUNK = 100;

    private static final class Batch {
        final String session, keepStart, keepEnd;
        String album;                // membership album id, or "" (guarded by the class lock: the owner may change it)
        List<Picker.Item> items;     // null until listed (after a restart)
        long listedAt;
        Batch(String session, String keepStart, String keepEnd, String album, List<Picker.Item> items) {
            this.session = session; this.keepStart = keepStart; this.keepEnd = keepEnd; this.album = album; this.items = items;
            this.listedAt = items == null ? 0 : System.currentTimeMillis();
        }
    }

    private static final List<Batch> waiting = new ArrayList<>();   // guarded by the class lock
    private static Batch current;
    private static int doneInCurrent, addedTotal, alreadyTotal, failedTotal, noRoomTotal;
    private static volatile int addedCount;      // grows as photos arrive (the frame watches it)
    private static Thread worker;
    private static boolean resumed;

    /** Queues one pick. The session now belongs to the queue (it deletes it when the batch is done). */
    static void add(Context ctx, String session, String keepStart, String keepEnd, String album, List<Picker.Item> items) {
        Context app = ctx.getApplicationContext();
        synchronized (DownloadQueue.class) {
            waiting.add(new Batch(session, keepStart == null ? "" : keepStart, keepEnd == null ? "" : keepEnd,
                    album == null ? "" : album, items));
            save(app);
            startWorker(app);
        }
    }

    /**
     * The owner chose a membership album for a pick already queued (or already finished): photos still to
     * come go into it, and the ones already saved are moved into it now. Call off the UI thread.
     */
    static void setAlbum(Context ctx, String session, String album, List<Picker.Item> items) {
        if (album == null || album.isEmpty()) return;
        Context app = ctx.getApplicationContext();
        synchronized (DownloadQueue.class) {
            for (Batch b : waiting) if (b.session.equals(session)) b.album = album;
            save(app);
        }
        // After the id is set: a photo saved before this is on disk now; one saved after it reads the new id.
        File dir = Storage.photoDir(app);
        List<String> saved = new ArrayList<>();
        for (Picker.Item it : items) if (new File(dir, it.fileName()).exists()) saved.add(it.fileName());
        PhotoLibrary.get(app).addToAlbum(album, saved, true);
        Log.i(TAG, "batch " + session + " goes into album " + album + " (" + saved.size() + " photos already saved)");
    }

    /** After a restart: carries on with picks that were still waiting. Safe to call more than once. */
    static void resume(Context ctx) {
        Context app = ctx.getApplicationContext();
        synchronized (DownloadQueue.class) {
            if (resumed) return;
            resumed = true;
            try {
                File f = file(app);
                if (!f.exists()) return;
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    String[] p = line.split("\t", -1);
                    // Lines saved before albums (2026-11-22) have no 4th field: the date trips, as then.
                    if (p.length >= 3 && !p[0].isEmpty()) waiting.add(new Batch(p[0], p[1], p[2], p.length > 3 ? p[3] : "", null));
                }
            } catch (IOException e) {
                Log.w(TAG, "couldn't read the saved queue: " + e);
            }
            if (!waiting.isEmpty()) startWorker(app);
        }
    }

    /** Photos added by the queue since the app started (the frame rescans when this changes). */
    static int addedCount() { return addedCount; }

    static synchronized boolean busy() { return current != null || !waiting.isEmpty(); }

    /** One line for the screen, e.g. "Downloading 340 of 2,000 · 1,500 more waiting", or null when idle. */
    static synchronized String status() {
        if (!busy()) return null;
        int later = 0;
        boolean unknown = false;
        for (Batch b : waiting) { if (b.items == null) unknown = true; else later += b.items.size(); }
        StringBuilder s = new StringBuilder();
        if (current != null && current.items != null)
            s.append("Downloading ").append(String.format("%,d", doneInCurrent)).append(" of ")
                    .append(String.format("%,d", current.items.size()));
        else s.append("Getting ready to download");
        if (later > 0) s.append("  ·  ").append(String.format("%,d", later)).append(" more waiting");
        else if (unknown || !waiting.isEmpty()) s.append("  ·  more waiting");
        return s.toString();
    }

    /** What happened so far, for the Add photos screen ("" if nothing unusual). */
    static synchronized String problems() {
        StringBuilder s = new StringBuilder();
        if (alreadyTotal > 0) s.append(String.format("%,d", alreadyTotal)).append(" were already on the frame. ");
        if (failedTotal > 0) s.append(String.format("%,d", failedTotal)).append(" didn't arrive; pick them again another time. ");
        if (noRoomTotal > 0) s.append(String.format("%,d", noRoomTotal)).append(" didn't fit: the space for photos is full (Settings → Space for photos). ");
        return s.toString().trim();
    }

    static synchronized int addedTotal() { return addedTotal; }

    /** Starts a fresh report when the Add photos screen opens with nothing downloading. */
    static synchronized void resetReport() {
        if (busy()) return;
        addedTotal = alreadyTotal = failedTotal = noRoomTotal = 0;
    }

    private static void startWorker(Context app) {
        if (worker != null) return;
        worker = new Thread(() -> work(app), "download-queue");
        worker.start();
    }

    private static void work(Context app) {
        Auth auth = new Auth(app);
        Picker picker = new Picker(auth);
        while (true) {
            Batch b;
            synchronized (DownloadQueue.class) {
                if (waiting.isEmpty()) { current = null; worker = null; return; }
                b = waiting.get(0);
                current = b;
                doneInCurrent = 0;
            }
            try {
                run(app, picker, b);
            } catch (Exception e) {
                Log.w(TAG, "batch " + b.session + " stopped: " + e);
                synchronized (DownloadQueue.class) {
                    if (b.items != null) failedTotal += Math.max(0, b.items.size() - doneInCurrent);
                }
            }
            PhotoLibrary.get(app).flushAlbums();
            picker.delete(new Picker.Session(b.session));
            synchronized (DownloadQueue.class) {
                waiting.remove(b);
                current = null;
                save(app);
            }
        }
    }

    private static void run(Context app, Picker picker, Batch b) throws IOException {
        if (b.items == null || stale(b)) relist(picker, b);
        PhotoLibrary library = PhotoLibrary.get(app);
        Settings settings = new Settings(app);
        final long budget = settings.spaceBytes();
        final long[] used = {library.photoBytes()};
        Picker.RoomCheck room = new Picker.RoomCheck() {
            @Override public boolean hasRoom() {
                while (used[0] + Storage.PHOTO_ESTIMATE_BYTES > budget) {
                    long freed = library.clearLeastPlayedTrip(b.keepStart, b.keepEnd, albumOf(b), settings.trips());
                    if (freed <= 0) return false;
                    used[0] -= freed;
                }
                return Storage.hasRoomForOne(app);
            }
            @Override public void added(long bytes) { used[0] += bytes; addedCount++; }
        };
        int total = b.items.size();
        File dir = Storage.photoDir(app);
        for (int from = 0; from < total; from += CHUNK) {
            if (stale(b)) relist(picker, b);
            List<Picker.Item> chunk = b.items.subList(from, Math.min(total, from + CHUNK));
            final int base = from;
            Picker.Result r = picker.download(chunk, dir, room, (done, n) -> {
                String album;
                synchronized (DownloadQueue.class) { doneInCurrent = base + done; album = b.album; }
                // Each photo joins the batch's album as soon as it is saved (or found already there).
                // Outside the queue's lock: the library has its own.
                String name = chunk.get(done - 1).fileName();
                if (!album.isEmpty() && new File(dir, name).exists())
                    library.addToAlbum(album, Collections.singletonList(name), false);
            });
            library.flushAlbums();
            synchronized (DownloadQueue.class) {
                addedTotal += r.added;
                alreadyTotal += r.alreadyThere;
                failedTotal += r.failed;
                if (r.noRoom > 0) { noRoomTotal += r.noRoom + (total - from - chunk.size()); return; }
            }
        }
        Log.i(TAG, "batch done: " + total + " photos");
    }

    private static synchronized String albumOf(Batch b) { return b.album; }

    private static boolean stale(Batch b) { return System.currentTimeMillis() - b.listedAt > FRESH_MS; }

    /** Fetches the pick's list again for fresh links, keeping the original order. */
    private static void relist(Picker picker, Batch b) throws IOException {
        List<Picker.Item> fresh = picker.photos(new Picker.Session(b.session));
        if (b.items != null) {
            Map<String, Picker.Item> byId = new HashMap<>();
            for (Picker.Item it : fresh) byId.put(it.id, it);
            List<Picker.Item> keep = new ArrayList<>();
            for (Picker.Item it : b.items) { Picker.Item f = byId.get(it.id); keep.add(f == null ? it : f); }
            fresh = keep;
        }
        synchronized (DownloadQueue.class) {
            b.items = fresh;
            b.listedAt = System.currentTimeMillis();
        }
        Log.i(TAG, "fresh links for " + fresh.size() + " photos");
    }

    private static File file(Context app) { return new File(app.getFilesDir(), "download_queue.txt"); }

    private static void save(Context app) {
        StringBuilder s = new StringBuilder();
        // session, keepStart, keepEnd, album id ("" = date trips; older lines have only the first three)
        for (Batch b : waiting)
            s.append(b.session).append('\t').append(b.keepStart).append('\t').append(b.keepEnd).append('\t').append(b.album).append('\n');
        File f = file(app), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "couldn't save the queue: " + e);
            return;
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }
}
