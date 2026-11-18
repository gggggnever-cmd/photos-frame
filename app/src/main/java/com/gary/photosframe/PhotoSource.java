package com.gary.photosframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hands out "slides" (what one screen shows) from the PhotoLibrary's round. In full-screen mode
 * portrait photos are grouped side by side: three tall phone shots, or two ordinary portraits
 * (in One day at a time, only from the same day). Keeps the last slides so ◀ can go back.
 * Not thread-safe: FrameView calls it only from its "slides" thread.
 */
final class PhotoSource {
    private static final int HISTORY = 100;

    private final Context ctx;
    private final PhotoLibrary library;
    private final Settings settings;
    // Slides already shown, so ◀ can go back. pos = index of the one on screen.
    private final List<List<String>> shown = new ArrayList<>();
    private int pos = -1;
    private List<String> upcoming;   // built by peekNext, used by the next nextSlide

    PhotoSource(Context ctx, Settings settings) {
        this.ctx = ctx;
        this.settings = settings;
        library = PhotoLibrary.get(ctx);
    }

    PhotoLibrary library() { return library; }

    void reload() {
        library.refresh();
        upcoming = null;
    }

    /** Forget the slide prepared ahead (after a settings change; the library starts a new round anyway). */
    void dropUpcoming() { upcoming = null; }

    boolean hasOwnPhotos() { return library.hasOwnPhotos(); }

    /** The next slide (1-3 photo ids). group=false always gives single photos (framed look). */
    List<String> nextSlide(boolean group) {
        if (pos < shown.size() - 1) return shown.get(++pos);
        List<String> slide = upcoming != null ? upcoming : build(group);
        upcoming = null;
        if (slide.isEmpty()) return slide;
        shown.add(slide);
        if (shown.size() > HISTORY) shown.remove(0);
        pos = shown.size() - 1;
        return slide;
    }

    /** The slide nextSlide will return, without moving to it (so it can be decoded ahead of time). */
    List<String> peekNext(boolean group) {
        if (pos < shown.size() - 1) return shown.get(pos + 1);
        if (upcoming == null) upcoming = build(group);
        return upcoming;
    }

    /** The slide shown before the current one, or null at the start of the history. */
    List<String> previousSlide() {
        return pos <= 0 ? null : shown.get(--pos);
    }

    /** After hiding a photo: take it out of the history and of the slide prepared ahead. */
    void forget(String id) {
        for (int i = shown.size() - 1; i >= 0; i--) {
            List<String> s = new ArrayList<>(shown.get(i));
            if (!s.remove(id)) continue;
            if (s.isEmpty()) {
                shown.remove(i);
                if (i <= pos) pos--;
            } else {
                shown.set(i, s);
            }
        }
        if (upcoming != null && upcoming.contains(id)) upcoming = null;
    }

    private List<String> build(boolean group) {
        String first = library.take(settings);
        if (first == null) return Collections.emptyList();
        List<String> slide = new ArrayList<>();
        slide.add(first);
        float a = library.aspect(first);
        if (group && a < PhotoLibrary.PORTRAIT) {
            boolean tall = a < PhotoLibrary.TALL;
            slide.addAll(library.pullPortraits(first, tall ? 2 : 1, tall));
            if (tall && slide.size() == 1) slide.addAll(library.pullPortraits(first, 1, false));
        }
        return slide;
    }

    /**
     * Decodes a photo scaled (during decoding) to just cover maxW x maxH. Photos are stored at 4K and
     * the screen is 1080p, so this keeps every later step at a quarter of the pixels.
     */
    Bitmap load(String id, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            decode(id, o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            float scale = Math.max((float) maxW / w, (float) maxH / h);
            int targetW = Math.min(w, (int) Math.ceil(w * scale));
            int sample = 1;
            while (w / (sample * 2) >= targetW) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            if (targetW < w / sample) {               // finish the scaling inside the decoder
                o.inScaled = true;
                o.inDensity = w / sample;
                o.inTargetDensity = targetW;
            }
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
