package com.gary.photosframe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shows one slide at a time and slowly cross-fades to the next. A slide is one full-screen photo,
 * 2-3 portraits side by side (Look: Full screen, the default), or one framed photo (Look: Framed).
 */
public class FrameView extends View {
    // How long each slide stays comes from Settings (default 10 minutes).
    static final long FADE_MS = 4000L;            // slow, quiet cross-fade
    static final long BUTTON_FADE_MS = 1200L;     // quicker fade when the remote asks for next/previous

    private final PhotoSource source;
    private final FrameRenderer renderer = new FrameRenderer();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fadePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Random random = new Random();
    private Bitmap current, previous;
    private final List<Bitmap> shownPhotos = new ArrayList<>();  // on screen now, kept so a filter change can redraw
    private float shownDx, shownDy;
    private int shownLook;
    private final Settings settings;
    private float fade = 1f;
    private ValueAnimator animator;

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            showSlide(source.nextSlide(settings.look() == Settings.LOOK_FULL), FADE_MS);
            handler.postDelayed(this, settings.showMs());
        }
    };

    public FrameView(Context ctx) {
        super(ctx);
        source = new PhotoSource(ctx);
        settings = new Settings(ctx);
        renderer.setArtStrength(settings.artStrength());
        shownLook = settings.look();
        setKeepScreenOn(true);
    }

    /** True once photos picked from Google Photos are on the TV (not just the bundled samples). */
    public boolean hasOwnPhotos() { return source.hasOwnPhotos(); }

    /** Re-scan the photo folder (after adding photos) and show one of them. */
    public void reload() {
        source.reload();
        skip();
    }

    /** Shows the next slide now and restarts the timer. */
    public void skip() {
        handler.removeCallbacks(advance);
        advance.run();
    }

    /** Remote ▶ / OK: next slide, with a quicker fade. */
    public void next() {
        if (getWidth() == 0) return;
        handler.removeCallbacks(advance);
        showSlide(source.nextSlide(settings.look() == Settings.LOOK_FULL), BUTTON_FADE_MS);
        handler.postDelayed(advance, settings.showMs());
    }

    /** Remote ◀: the slide shown before this one. Returns false if there is nothing earlier. */
    public boolean back() {
        if (getWidth() == 0) return false;
        List<String> slide = source.previousSlide();
        if (slide == null) return false;
        handler.removeCallbacks(advance);
        showSlide(slide, BUTTON_FADE_MS);
        handler.postDelayed(advance, settings.showMs());
        return true;
    }

    /** Called after the Settings page changes something: redraw with the new filter or look, restart the timer. */
    public void applySettings() {
        float s = settings.artStrength();
        boolean lookChanged = settings.look() != shownLook;
        if (s != renderer.artStrength() || lookChanged) {
            renderer.setArtStrength(s);
            shownLook = settings.look();
            if (getWidth() > 0 && !shownPhotos.isEmpty()) transitionTo(render(shownPhotos, shownDx, shownDy), 700);
        }
        handler.removeCallbacks(advance);
        handler.postDelayed(advance, settings.showMs());
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w > 0 && h > 0) skip();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(advance);
        if (animator != null) animator.cancel();
    }

    private Bitmap render(List<Bitmap> photos, float dx, float dy) {
        int w = getWidth(), h = getHeight();
        Bitmap scene = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(scene);
        if (settings.look() == Settings.LOOK_FRAMED) {
            renderer.draw(c, photos.isEmpty() ? null : photos.get(0), w, h, dx, dy);
        } else {
            renderer.drawFull(c, photos, w, h, dx, dy);
        }
        return scene;
    }

    private void showSlide(List<String> ids, long fadeMs) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        List<Bitmap> photos = new ArrayList<>();
        int colW = ids.isEmpty() ? w : w / ids.size();
        for (String id : ids) {
            Bitmap b = source.load(id, colW, h);
            if (b != null) photos.add(b);
        }
        float drift = 0.004f * h;
        float dx = (random.nextFloat() * 2 - 1) * drift, dy = (random.nextFloat() * 2 - 1) * drift;
        Bitmap scene = render(photos, dx, dy);
        for (Bitmap b : shownPhotos) b.recycle();
        shownPhotos.clear();
        shownPhotos.addAll(photos);
        shownDx = dx;
        shownDy = dy;
        transitionTo(scene, fadeMs);
    }

    private void transitionTo(Bitmap scene, long fadeMs) {
        if (previous != null) previous.recycle();
        previous = current;
        current = scene;
        if (animator != null) animator.cancel();
        if (previous == null) { fade = 1f; invalidate(); return; }
        animator = ValueAnimator.ofFloat(0f, 1f).setDuration(fadeMs);
        animator.addUpdateListener(a -> { fade = (float) a.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    @Override protected void onDraw(Canvas c) {
        if (current == null) { c.drawColor(FrameRenderer.WALL); return; }
        if (previous != null && fade < 1f) {
            fadePaint.setAlpha(255);
            c.drawBitmap(previous, 0, 0, fadePaint);
            fadePaint.setAlpha((int) (fade * 255));
            c.drawBitmap(current, 0, 0, fadePaint);
        } else {
            c.drawBitmap(current, 0, 0, null);
        }
    }
}
