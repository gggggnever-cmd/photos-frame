package com.gary.photosframe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Random;

/** Shows one framed photo at a time and slowly cross-fades to the next. */
public class FrameView extends View {
    static final long SHOW_MS = 10 * 60 * 1000L;  // each picture stays 10 minutes
    static final long FADE_MS = 4000L;            // slow, quiet cross-fade

    private final PhotoSource source;
    private final FrameRenderer renderer = new FrameRenderer();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fadePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Random random = new Random();
    private Bitmap current, previous;
    private float fade = 1f;
    private ValueAnimator animator;

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            showNext();
            handler.postDelayed(this, SHOW_MS);
        }
    };

    public FrameView(Context ctx) {
        super(ctx);
        source = new PhotoSource(ctx);
        setKeepScreenOn(true);
    }

    /** True once photos picked from Google Photos are on the TV (not just the bundled samples). */
    public boolean hasOwnPhotos() { return source.hasOwnPhotos(); }

    /** Re-scan the photo folder (after adding photos) and show one of them. */
    public void reload() {
        source.reload();
        skip();
    }

    /** Remote: OK / right arrow skips to the next picture. */
    public void skip() {
        handler.removeCallbacks(advance);
        advance.run();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w > 0 && h > 0) skip();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(advance);
        if (animator != null) animator.cancel();
    }

    private void showNext() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        Bitmap photo = source.next(w, h);
        Bitmap scene = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float drift = 0.004f * h;
        renderer.draw(new Canvas(scene), photo, w, h,
                (random.nextFloat() * 2 - 1) * drift, (random.nextFloat() * 2 - 1) * drift);
        if (photo != null) photo.recycle();

        if (previous != null) previous.recycle();
        previous = current;
        current = scene;
        if (animator != null) animator.cancel();
        if (previous == null) { fade = 1f; invalidate(); return; }
        animator = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
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
