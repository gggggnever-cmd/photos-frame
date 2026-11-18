package com.gary.photosframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where photos come from: everything downloaded into files/photos by "Add photos".
 * The bundled samples are only placeholders, used until the first photos are added.
 *
 * Photos are handed out as "slides" (what one screen shows). In full-screen mode portrait photos are
 * grouped side by side: three tall phone shots, or two ordinary portraits. ◀ replays earlier slides.
 */
final class PhotoSource {
    static final float PORTRAIT = 0.9f;   // width/height below this counts as portrait
    static final float TALL = 0.66f;      // 9:16-ish phone portraits: three fit side by side
    private static final int LOOK_AHEAD = 60;
    private static final int HISTORY = 100;

    private final Context ctx;
    private final List<String> items = new ArrayList<>();
    private final Map<String, Float> aspects = new HashMap<>();
    private int next = 0;
    private boolean own;
    // Slides already shown, so ◀ can go back. pos = index of the one on screen.
    private final List<List<String>> shown = new ArrayList<>();
    private int pos = -1;

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

    /** The next slide (1-3 photo ids). group=false always gives single photos (framed look). */
    List<String> nextSlide(boolean group) {
        if (pos < shown.size() - 1) return shown.get(++pos);
        if (items.isEmpty()) return Collections.emptyList();
        if (next >= items.size()) reload();
        List<String> slide = new ArrayList<>();
        String first = items.get(next++);
        slide.add(first);
        float a = aspect(first);
        if (group && a < PORTRAIT) {
            // Pull portraits forward from the shuffled queue; landscape photos keep their turn.
            boolean tall = a < TALL;
            int want = tall ? 2 : 1;
            pullPortraits(slide, want, tall);
            if (tall && slide.size() == 1) pullPortraits(slide, 1, false);   // no other tall one: pair with any portrait
        }
        shown.add(slide);
        if (shown.size() > HISTORY) shown.remove(0);
        pos = shown.size() - 1;
        return slide;
    }

    /** The slide shown before the current one, or null at the start of the history. */
    List<String> previousSlide() {
        return pos <= 0 ? null : shown.get(--pos);
    }

    private void pullPortraits(List<String> slide, int want, boolean tallOnly) {
        int end = Math.min(items.size(), next + LOOK_AHEAD);
        for (int i = next; i < end && want > 0; i++) {
            float a = aspect(items.get(i));
            if (tallOnly ? a < TALL : a < PORTRAIT) {
                slide.add(items.remove(i));
                end--;
                i--;
                want--;
            }
        }
    }

    /** Width / height, read from the file header only (cached). */
    float aspect(String id) {
        Float a = aspects.get(id);
        if (a != null) return a;
        float v = 1.5f;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            decode(id, o);
            if (o.outWidth > 0 && o.outHeight > 0) v = (float) o.outWidth / o.outHeight;
        } catch (IOException ignored) { }
        aspects.put(id, v);
        return v;
    }

    /** Decodes a photo, downsampled so it still covers roughly maxW x maxH. */
    Bitmap load(String id, int maxW, int maxH) {
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
