package com.gary.photosframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * One-off background job: photos downloaded before v0.13 were stored at 4K (~1.2 MB). The app only
 * draws 1920x1080, so they are re-saved at "frame size" (fitting 1920x1440, like new downloads),
 * keeping the EXIF capture date. Each file is replaced atomically. Runs once (marker file), slowly,
 * at low priority so the frame stays smooth.
 */
final class Shrinker {
    static final int MAX_W = 1920, MAX_H = 1440;

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED = new java.util.concurrent.atomic.AtomicBoolean();

    static void startOnce(Context ctx) {
        if (!STARTED.compareAndSet(false, true)) return;   // the app and the screensaver share one process
        File marker = new File(new File(ctx.getFilesDir(), "library"), "shrunk.done");
        if (marker.exists()) return;
        Thread t = new Thread(() -> run(ctx, marker), "shrinker");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    private static void run(Context ctx, File marker) {
        File[] files = Storage.photoDir(ctx).listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null) return;
        long t0 = SystemClock.uptimeMillis(), before = 0, after = 0;
        int done = 0;
        for (File f : files) {
            long len = f.length();
            try {
                if (shrink(f)) { done++; before += len; after += f.length(); }
            } catch (IOException | RuntimeException e) {
                Log.w("Shrinker", "skipped " + f.getName() + ": " + e);
            }
            if (done > 0 && done % 100 == 0)
                Log.i("Shrinker", done + " photos re-saved so far, " + (before - after) / 1_000_000 + " MB freed");
            SystemClock.sleep(20);   // stay gentle on the TV's CPU
        }
        Log.i("Shrinker", "done: " + done + " photos re-saved, " + before / 1_000_000 + " MB → " + after / 1_000_000
                + " MB, in " + (SystemClock.uptimeMillis() - t0) / 1000 + " s");
        try { marker.getParentFile().mkdirs(); marker.createNewFile(); } catch (IOException ignored) { }
    }

    /** Re-saves one photo at frame size if it is bigger. Returns true if it was changed. */
    static boolean shrink(File f) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0 || (w <= MAX_W && h <= MAX_H)) return false;
        float scale = Math.min((float) MAX_W / w, (float) MAX_H / h);
        int targetW = Math.round(w * scale);
        int sample = 1;
        while (w / (sample * 2) >= targetW) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        if (targetW < w / sample) { o.inScaled = true; o.inDensity = w / sample; o.inTargetDensity = targetW; }
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (b == null) return false;
        File tmp = new File(f.getParentFile(), f.getName() + ".shrink");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            b.recycle();
        }
        // Carry the capture date across: trips, bursts and "One day at a time" depend on it.
        ExifInterface src = new ExifInterface(f.getAbsolutePath());
        ExifInterface dst = new ExifInterface(tmp.getAbsolutePath());
        boolean any = false;
        for (String tag : new String[]{ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_DIGITIZED}) {
            String v = src.getAttribute(tag);
            if (v != null) { dst.setAttribute(tag, v); any = true; }
        }
        if (any) dst.saveAttributes();
        // Self-check: never replace a photo if its date didn't survive.
        String want = src.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        String got = new ExifInterface(tmp.getAbsolutePath()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        if (want != null && !want.equals(got)) {
            Log.w("Shrinker", "date check failed for " + f.getName() + ", kept the original");
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(f)) { tmp.delete(); return false; }
        return true;
    }
}
