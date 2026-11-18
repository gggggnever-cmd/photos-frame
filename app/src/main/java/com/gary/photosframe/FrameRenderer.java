package com.gary.photosframe;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.List;

/** Draws a slide: full screen (default) or one photo as a framed, matted picture on a wall. */
final class FrameRenderer {
    // Calm, gallery-like palette.
    static final int WALL = 0xFF2E2B28;        // warm charcoal wall
    static final int MATTE = 0xFFF1EDE4;       // off-white museum matte
    static final int FRAME = 0xFF1F1915;       // dark walnut

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // Paper tone for gaps and empty areas: a warm mount-board colour (designer's choice for the matte
    // screen, where near-black reads as muddy grey), tinted slightly toward the photos' average colour.
    static final int PAPER = 0xFFD4CEC3;
    static final float PAPER_TINT = 0.18f;
    static final float GAP_FRACTION = 48f / 1080f;   // band between side-by-side photos (48 px at 1080p)

    /** Slides thread: the paper colour for a slide, from a tiny downscale of its photos. */
    static int paperTone(List<Bitmap> photos) {
        if (photos.isEmpty()) return PAPER;
        long r = 0, g = 0, b = 0, n = 0;
        for (Bitmap ph : photos) {
            Bitmap t = Bitmap.createScaledBitmap(ph, 8, 8, true);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int c = t.getPixel(x, y);
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
            }
            t.recycle();
        }
        return mix(PAPER, (int) (r / n), (int) (g / n), (int) (b / n), PAPER_TINT);
    }

    private static int mix(int base, int r, int g, int b, float t) {
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        return 0xFF000000 | (Math.round(br + (r - br) * t) << 16) | (Math.round(bg + (g - bg) * t) << 8) | Math.round(bb + (b - bb) * t);
    }

    /**
     * Full-screen look. One landscape fills the screen edge to edge. Portraits ("Flush Triptych", the owner's
     * pick): shown whole, full height, the outer ones touching the screen edges, separated by paper bands.
     * If whole photos would not fit side by side, each gets an equal column and is trimmed to fill it.
     * A lone portrait or a panorama is shown whole, centred on paper, rather than cropped hard.
     * offsetX/offsetY: a few pixels of drift between slides (only used where a photo is trimmed).
     */
    void drawFull(Canvas c, List<Bitmap> photos, int paper, int w, int h, float offsetX, float offsetY, List<RectF> rects) {
        c.drawColor(paper);
        int n = photos.size();
        if (n == 0) return;
        float minGap = GAP_FRACTION * h;
        if (n == 1) {
            Bitmap ph = photos.get(0);
            float ratio = ((float) ph.getWidth() / ph.getHeight()) / ((float) w / h);
            RectF all = new RectF(0, 0, w, h);
            if (ratio < 0.7f || ratio > 1.45f) drawFit(c, ph, all, rects); else drawCover(c, ph, all, offsetX, offsetY, rects);
            return;
        }
        float sumW = 0;
        for (Bitmap ph : photos) sumW += h * (float) ph.getWidth() / ph.getHeight();
        if (sumW + minGap * (n - 1) <= w) {
            // Whole photos. Three spread to the edges; two sit centred with a standard band between.
            float gap = n >= 3 ? (w - sumW) / (n - 1) : minGap;
            float x = n >= 3 ? 0 : (w - sumW - gap) / 2;
            for (Bitmap ph : photos) {
                float pw = h * (float) ph.getWidth() / ph.getHeight();
                RectF r = new RectF(x, 0, x + pw, h);
                drawCover(c, ph, r, 0, 0, rects);
                x += pw + gap;
            }
        } else {
            float colW = (w - minGap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                RectF col = new RectF(i * (colW + minGap), 0, i * (colW + minGap) + colW, h);
                drawCover(c, photos.get(i), col, offsetX, offsetY, rects);
            }
        }
    }

    /** Fills dst completely (cropping the overflow), slightly over-scaled so the drift never shows an edge. */
    private void drawCover(Canvas c, Bitmap ph, RectF dst, float dx, float dy, List<RectF> rects) {
        float scale = Math.max(dst.width() / ph.getWidth(), dst.height() / ph.getHeight()) * (dx == 0 && dy == 0 ? 1f : 1.012f);
        float sw = dst.width() / scale, sh = dst.height() / scale;
        float sl = clamp((ph.getWidth() - sw) / 2 + dx / scale, 0, ph.getWidth() - sw);
        float st = clamp((ph.getHeight() - sh) / 2 + dy / scale, 0, ph.getHeight() - sh);
        c.drawBitmap(ph, new Rect(Math.round(sl), Math.round(st), Math.round(sl + sw), Math.round(st + sh)), dst, p);
        rects.add(dst);
    }

    /** Whole photo, centred in dst, on the paper already drawn there. */
    private void drawFit(Canvas c, Bitmap ph, RectF dst, List<RectF> rects) {
        float scale = Math.min(dst.width() / ph.getWidth(), dst.height() / ph.getHeight());
        float pw = ph.getWidth() * scale, phh = ph.getHeight() * scale;
        RectF r = new RectF(dst.centerX() - pw / 2, dst.centerY() - phh / 2, dst.centerX() + pw / 2, dst.centerY() + phh / 2);
        c.drawBitmap(ph, null, r, p);
        rects.add(r);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Framed look. offsetX/offsetY: a few pixels of drift between photos, to be kind to the panel. */
    void draw(Canvas c, Bitmap photo, int w, int h, float offsetX, float offsetY, List<RectF> rects) {
        c.drawColor(WALL);
        if (photo == null) return;

        float frameW = 0.030f * h;
        float matW = 0.085f * h;
        float matBottomExtra = 0.012f * h;        // classic bottom-weighted matte
        float margin = 0.07f * h;

        float border = frameW + matW;
        float maxPhotoW = w - 2 * margin - 2 * border;
        float maxPhotoH = h - 2 * margin - 2 * border - matBottomExtra;
        float scale = Math.min(maxPhotoW / photo.getWidth(), maxPhotoH / photo.getHeight());
        float pw = photo.getWidth() * scale, ph = photo.getHeight() * scale;

        float outerW = pw + 2 * border, outerH = ph + 2 * border + matBottomExtra;
        float left = (w - outerW) / 2 + offsetX, top = (h - outerH) / 2 + offsetY;
        RectF outer = new RectF(left, top, left + outerW, top + outerH);
        RectF matRect = new RectF(outer.left + frameW, outer.top + frameW, outer.right - frameW, outer.bottom - frameW);
        RectF photoRect = new RectF(matRect.left + matW, matRect.top + matW, matRect.left + matW + pw, matRect.top + matW + ph);

        // Soft shadow cast on the wall, slightly below the frame.
        p.setColor(0x99000000);
        p.setMaskFilter(new BlurMaskFilter(0.025f * h, BlurMaskFilter.Blur.NORMAL));
        c.drawRect(outer.left + 0.004f * h, outer.top + 0.012f * h, outer.right + 0.004f * h, outer.bottom + 0.018f * h, p);
        p.setMaskFilter(null);

        // Frame: four mitred sides, lit from above.
        drawSide(c, outer, matRect, 0, shade(FRAME, 1.35f)); // top
        drawSide(c, outer, matRect, 1, shade(FRAME, 1.10f)); // left
        drawSide(c, outer, matRect, 2, shade(FRAME, 0.95f)); // right
        drawSide(c, outer, matRect, 3, shade(FRAME, 0.75f)); // bottom
        // Thin highlight on the outer edge.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 0.0015f * h));
        p.setColor(0x33FFFFFF);
        c.drawRect(outer, p);
        p.setStyle(Paint.Style.FILL);

        // Matte, with the frame's shadow falling onto its top edge.
        p.setColor(MATTE);
        c.drawRect(matRect, p);
        p.setShader(new android.graphics.LinearGradient(0, matRect.top, 0, matRect.top + 0.02f * h,
                0x30000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(matRect.left, matRect.top, matRect.right, matRect.top + 0.02f * h, p);
        p.setShader(null);

        // Bevelled cut around the opening: a bright cream edge.
        float bevel = Math.max(2f, 0.005f * h);
        p.setColor(0xFFFBF8F1);
        c.drawRect(photoRect.left - bevel, photoRect.top - bevel, photoRect.right + bevel, photoRect.bottom + bevel, p);
        p.setColor(0xFFD9D3C6); // shaded lower/right bevel
        Path lower = new Path();
        lower.moveTo(photoRect.right + bevel, photoRect.top - bevel);
        lower.lineTo(photoRect.right + bevel, photoRect.bottom + bevel);
        lower.lineTo(photoRect.left - bevel, photoRect.bottom + bevel);
        lower.lineTo(photoRect.left, photoRect.bottom);
        lower.lineTo(photoRect.right, photoRect.bottom);
        lower.lineTo(photoRect.right, photoRect.top);
        lower.close();
        c.drawPath(lower, p);

        // The photo (already toned by ArtTreatment.bake; texture and light are drawn live on top).
        c.drawBitmap(photo, new Rect(0, 0, photo.getWidth(), photo.getHeight()), photoRect, p);
        rects.add(photoRect);
        // Faint glass sheen, top-left to centre.
        p.setShader(new android.graphics.LinearGradient(matRect.left, matRect.top, matRect.centerX(), matRect.centerY(),
                0x0FFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(matRect, p);
        p.setShader(null);
    }

    private void drawSide(Canvas c, RectF o, RectF i, int side, int color) {
        Path path = new Path();
        switch (side) {
            case 0: path.moveTo(o.left, o.top); path.lineTo(o.right, o.top); path.lineTo(i.right, i.top); path.lineTo(i.left, i.top); break;
            case 1: path.moveTo(o.left, o.top); path.lineTo(i.left, i.top); path.lineTo(i.left, i.bottom); path.lineTo(o.left, o.bottom); break;
            case 2: path.moveTo(o.right, o.top); path.lineTo(o.right, o.bottom); path.lineTo(i.right, i.bottom); path.lineTo(i.right, i.top); break;
            default: path.moveTo(o.left, o.bottom); path.lineTo(i.left, i.bottom); path.lineTo(i.right, i.bottom); path.lineTo(o.right, o.bottom); break;
        }
        path.close();
        p.setColor(color);
        c.drawPath(path, p);
    }

    private static int shade(int argb, float f) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((argb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
