package com.gary.photosframe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Shows one slide at a time and slowly cross-fades to the next. A slide is one full-screen photo,
 * three portraits side by side (Look: Full screen, the default), or one framed photo (Look: Framed).
 *
 * Speed (the TV has a slow 32-bit CPU and GPU): everything heavy happens once per slide on a
 * background thread ("slides"): decode, print-filter tone bake, layout, composed into one finished
 * screen-sized bitmap. The next slide is prepared ahead of time. Each frame on screen then only
 * blends two finished bitmaps and draws the paper/canvas texture and gallery light on top.
 */
public class FrameView extends View {
    // How long each slide stays comes from Settings (default 10 minutes).
    static final long FADE_MS = 4000L;            // slow, quiet cross-fade
    static final long BUTTON_FADE_MS = 1200L;     // quicker fade when the remote asks for next/previous
    static final long SETTINGS_FADE_MS = 600L;    // when a Settings change redraws the current slide

    /** One finished screen: the composed picture and where the photos sit in it (for canvas texture). */
    private static final class Scene {
        final List<String> ids;          // the slide as handed out (used to match the prepared slide)
        final List<String> drawnIds;     // the photos actually on screen, same order as photoRects
        final Bitmap image;
        final List<RectF> photoRects;
        Scene(List<String> ids, List<String> drawnIds, Bitmap image, List<RectF> photoRects) {
            this.ids = ids; this.drawnIds = drawnIds; this.image = image; this.photoRects = photoRects;
        }
    }

    private volatile PhotoSource source;            // created and only used on the slides thread (counts read by UI)
    private final FrameRenderer renderer = new FrameRenderer();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread worker = new HandlerThread("slides");
    private final Handler bg;
    private final Random random = new Random();
    private final Settings settings;
    private final Paint fadePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private volatile ArtTreatment art;              // built on the slides thread (texture tiles take a moment)
    private float bakedStrength = -1f;              // slides thread: level the prepared slide was baked with
    private int bakedLook = -1;
    private int bakedOrder = -1;
    private boolean bakedFavOnly;
    private String bakedDay = "";
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int highlight = -1;                      // photo outlined while the photo menu chooses one

    // UI thread state.
    private Scene current, previous;
    private float fade = 1f;
    private ValueAnimator animator;

    // Slides thread state.
    private Scene prepared;                         // the next slide, finished ahead of time

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            requestNext(FADE_MS);
            ui.postDelayed(this, settings.showMs());
        }
    };

    public FrameView(Context ctx) {
        super(ctx);
        settings = new Settings(ctx);
        worker.start();
        bg = new Handler(worker.getLooper());
        // The photo library reads every photo's date the first time (a few seconds), so build it here.
        bg.post(() -> {
            source = new PhotoSource(ctx, settings);
            bakedOrder = settings.order();
            bakedFavOnly = settings.favouritesOnly();
            bakedDay = settings.day();
            ensureArt();
        });
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setColor(0xFFF1EDE4);
        setKeepScreenOn(true);
    }

    /** True once photos picked from Google Photos are on the TV (not just the bundled samples). */
    public boolean hasOwnPhotos() { return Storage.photoCount(getContext()) > 0; }

    /** Re-scan the photo folder (after adding photos) and show one of them. */
    public void reload() {
        bg.post(() -> { source.reload(); prepared = null; });
        skip();
    }

    /** The photos on screen now, left to right (for the photo menu). */
    public List<String> currentPhotos() {
        return current == null ? Collections.emptyList() : current.drawnIds;
    }

    /** Outline one of the photos on screen (-1: none). */
    public void setHighlight(int index) {
        highlight = index;
        invalidate();
    }

    /** Photo menu: hide a photo for good (until "Show hidden photos again"), then move on. */
    public void hide(String id, Runnable done) {
        bg.post(() -> {
            source.library().hide(id);
            source.forget(id);
            prepared = null;
            ui.post(done);
        });
        next();
    }

    /** Photo menu: favourite on/off. Reports the new state on the UI thread. */
    public void toggleFavourite(String id, java.util.function.Consumer<Boolean> done) {
        bg.post(() -> {
            boolean now = source.library().toggleFavourite(id);
            ui.post(() -> done.accept(now));
        });
    }

    /** Settings page: how many are hidden / favourites, and bring hidden photos back. */
    public int hiddenCount() { PhotoSource s = source; return s == null ? 0 : s.library().hiddenCount(); }
    public int favouriteCount() { PhotoSource s = source; return s == null ? 0 : s.library().favouriteCount(); }
    public void unhideAll() { bg.post(() -> { source.library().unhideAll(); prepared = null; }); }
    public List<String[]> days() {
        PhotoSource s = source;
        return s == null ? Collections.emptyList() : s.library().days(settings.favouritesOnly());
    }
    public boolean isFavourite(String id) { PhotoSource s = source; return s != null && s.library().isFavourite(id); }

    /** Shows the next slide now and restarts the timer. */
    public void skip() {
        ui.removeCallbacks(advance);
        advance.run();
    }

    /** Remote ▶ / OK: next slide, with a quicker fade. */
    public void next() {
        ui.removeCallbacks(advance);
        requestNext(BUTTON_FADE_MS);
        ui.postDelayed(advance, settings.showMs());
    }

    /** Remote ◀: the slide shown before this one. Runs onNone on the UI thread if there is nothing earlier. */
    public void back(Runnable onNone) {
        ui.removeCallbacks(advance);
        int w = getWidth(), h = getHeight();
        bg.post(() -> {
            List<String> ids = source.previousSlide();
            if (ids == null) { ui.post(onNone); return; }
            Scene s = compose(ids, w, h);
            ui.post(() -> show(s, BUTTON_FADE_MS));
        });
        ui.postDelayed(advance, settings.showMs());
    }

    /**
     * After the Settings page changes something. Surface only changes the live texture; filter level
     * and look need the current slide rebuilt (a short fade, the picture stays the same).
     */
    public void applySettings() {
        invalidate();
        ui.removeCallbacks(advance);
        ui.postDelayed(advance, settings.showMs());
        Scene shown = current;
        int w = getWidth(), h = getHeight();
        if (shown == null || w == 0) return;
        bg.post(() -> {
            if (settings.order() != bakedOrder || settings.favouritesOnly() != bakedFavOnly
                    || !settings.day().equals(bakedDay)) {
                // New order, day or play choice: the library starts a new round with the next slide.
                bakedOrder = settings.order();
                bakedFavOnly = settings.favouritesOnly();
                bakedDay = settings.day();
                source.dropUpcoming();
                prepared = null;
                return;
            }
            if (settings.artStrength() == bakedStrength && settings.look() == bakedLook) return;
            ensureArt();
            prepared = null;
            Scene s = compose(shown.ids, w, h);
            ui.post(() -> show(s, SETTINGS_FADE_MS));
        });
    }

    private void requestNext(long fadeMs) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        bg.post(() -> {
            ensureArt();
            boolean group = settings.look() == Settings.LOOK_FULL;
            List<String> ids = source.nextSlide(group);
            Scene s = prepared != null && prepared.ids.equals(ids) ? prepared : compose(ids, w, h);
            prepared = null;
            ui.post(() -> show(s, fadeMs));
            prepared = compose(source.peekNext(group), w, h);   // get the following slide ready
        });
    }

    /** Slides thread: (re)build the filter if the level changed. */
    private void ensureArt() {
        float s = settings.artStrength();
        if (art == null || s != bakedStrength) {
            art = new ArtTreatment(s);
            bakedStrength = s;
            prepared = null;
        }
    }

    /** Slides thread: decode, tone, lay out and compose one slide into a finished bitmap. */
    private Scene compose(List<String> ids, int w, int h) {
        long t0 = SystemClock.uptimeMillis();
        ArtTreatment a = art;
        int look = settings.look();
        bakedLook = look;
        List<Bitmap> photos = new ArrayList<>();
        List<String> drawn = new ArrayList<>();
        int colW = ids.isEmpty() ? w : w / ids.size();
        for (String id : ids) {
            Bitmap raw = source.load(id, colW, h);
            if (raw == null) continue;
            Bitmap toned = a.bake(raw);
            if (toned != raw) raw.recycle();
            photos.add(toned);
            drawn.add(id);
        }
        if (look == Settings.LOOK_FRAMED && drawn.size() > 1) drawn = drawn.subList(0, 1);
        long t1 = SystemClock.uptimeMillis();
        float drift = 0.004f * h;
        float dx = (random.nextFloat() * 2 - 1) * drift, dy = (random.nextFloat() * 2 - 1) * drift;
        Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(image);
        List<RectF> rects = new ArrayList<>();
        if (look == Settings.LOOK_FRAMED) {
            renderer.draw(c, photos.isEmpty() ? null : photos.get(0), w, h, dx, dy, rects);
        } else {
            renderer.drawFull(c, photos, FrameRenderer.paperTone(photos), w, h, dx, dy, rects);
        }
        for (Bitmap b : photos) b.recycle();
        Log.i("FrameView", "composed n=" + ids.size() + " decode+tone=" + (t1 - t0) + "ms layout="
                + (SystemClock.uptimeMillis() - t1) + "ms");
        return new Scene(ids, Collections.unmodifiableList(drawn), image, Collections.unmodifiableList(rects));
    }

    /** UI thread: start the cross-fade to a finished scene. */
    private void show(Scene s, long fadeMs) {
        previous = current;
        current = s;
        if (animator != null) animator.cancel();
        if (previous == null) { fade = 1f; invalidate(); return; }
        fade = 0f;
        animator = ValueAnimator.ofFloat(0f, 1f).setDuration(fadeMs);
        animator.addUpdateListener(an -> { fade = (float) an.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w > 0 && h > 0) { bg.post(() -> prepared = null); skip(); }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ui.removeCallbacks(advance);
        if (animator != null) animator.cancel();
        worker.quitSafely();
    }

    @Override protected void onDraw(Canvas c) {
        if (current == null) { c.drawColor(FrameRenderer.WALL); return; }
        if (previous != null && fade < 1f) {
            fadePaint.setAlpha(255);
            c.drawBitmap(previous.image, 0, 0, fadePaint);
            fadePaint.setAlpha(Math.round(fade * 255));
            c.drawBitmap(current.image, 0, 0, fadePaint);
        } else {
            previous = null;
            c.drawBitmap(current.image, 0, 0, null);
        }
        ArtTreatment a = art;
        if (a != null) a.drawSurface(c, getWidth(), getHeight(), current.photoRects,
                settings.surface() == Settings.SURFACE_CANVAS);
        if (highlight >= 0 && highlight < current.photoRects.size()) {
            float sw = Math.max(3f, getHeight() * 0.004f);
            highlightPaint.setStrokeWidth(sw);
            RectF r = new RectF(current.photoRects.get(highlight));
            r.inset(sw / 2, sw / 2);
            c.drawRect(r, highlightPaint);
        }
    }
}
