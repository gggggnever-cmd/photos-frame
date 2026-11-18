package com.gary.photosframe;

import android.content.Context;

import java.io.File;

/**
 * Photo storage on the TV. Photos arrive at "frame size" (fitting 1920x1440), about 0.5 MB each, so a
 * 2,000-photo trip is about 1 GB. The TCL 85" A300W has a 49 GB data area (~44 GB free). Settings →
 * Space for photos (default 20 GB) caps photos; we also never dip into RESERVE, so the TV keeps room.
 */
final class Storage {
    static final long GB = 1024L * 1024 * 1024;
    /** Stop downloading below this, so the TV itself keeps room to work. */
    static final long RESERVE_BYTES = 2 * GB;
    /** Show a "storage low" warning below this. */
    static final long LOW_BYTES = 5 * GB;
    /** Assume this per photo when estimating room left (generous for a frame-size photo). */
    static final long PHOTO_ESTIMATE_BYTES = 2L * 1024 * 1024;

    static File photoDir(Context ctx) { return new File(ctx.getFilesDir(), "photos"); }

    static long free(Context ctx) { return ctx.getFilesDir().getUsableSpace(); }

    static boolean isFull(Context ctx) { return free(ctx) < RESERVE_BYTES; }

    static boolean isLow(Context ctx) { return free(ctx) < LOW_BYTES; }

    /** Room for one more photo without dipping into the reserve. */
    static boolean hasRoomForOne(Context ctx) { return free(ctx) - PHOTO_ESTIMATE_BYTES >= RESERVE_BYTES; }

    static int photoCount(Context ctx) {
        File[] f = photoDir(ctx).listFiles((d, n) -> n.endsWith(".jpg"));
        return f == null ? 0 : f.length;
    }

    static String gb(long bytes) { return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) GB); }

    /** One calm line for the screen, or null when there is nothing to warn about. */
    static String warning(Context ctx) {
        long free = free(ctx);
        if (free < RESERVE_BYTES)
            return "TV storage is full (" + gb(free) + " free). No more photos can be added; the frame keeps showing the ones it has.";
        if (free < LOW_BYTES)
            return "TV storage is getting low (" + gb(free) + " free, room for about "
                    + Math.max(0, (free - RESERVE_BYTES) / PHOTO_ESTIMATE_BYTES) + " more photos).";
        return null;
    }
}
