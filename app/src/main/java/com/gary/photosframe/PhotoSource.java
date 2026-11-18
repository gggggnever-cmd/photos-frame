package com.gary.photosframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where photos come from: everything downloaded into files/photos by "Add photos".
 * The bundled samples are only placeholders, used until the first photos are added.
 */
final class PhotoSource {
    private final Context ctx;
    private final List<String> items = new ArrayList<>();
    private int next = 0;
    private boolean own;

    PhotoSource(Context ctx) {
        this.ctx = ctx;
        reload();
    }

    void reload() {
        items.clear();
        File dir = Storage.photoDir(ctx);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".jpg")) items.add("file:" + f.getAbsolutePath());
        own = !items.isEmpty();
        if (!own) {
            try {
                String[] names = ctx.getAssets().list("photos");
                if (names != null) for (String n : names) items.add("asset:photos/" + n);
            } catch (IOException ignored) { }
        }
        Collections.shuffle(items);
        next = 0;
    }

    boolean isEmpty() { return items.isEmpty(); }
    boolean hasOwnPhotos() { return own; }

    /** Decodes the next photo, downsampled to roughly fit maxW x maxH. */
    Bitmap next(int maxW, int maxH) {
        if (items.isEmpty()) return null;
        if (next >= items.size()) reload();
        String id = items.get(next++);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            decode(id, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return decode(id, o);
        } catch (IOException e) {
            return null;
        }
    }

    private Bitmap decode(String id, BitmapFactory.Options o) throws IOException {
        if (id.startsWith("file:")) return BitmapFactory.decodeFile(id.substring(5), o);
        try (InputStream in = ctx.getAssets().open(id.substring(6))) {
            return BitmapFactory.decodeStream(in, null, o);
        }
    }
}
