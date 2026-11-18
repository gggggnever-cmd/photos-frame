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
    private ArtTreatment art = new ArtTreatment();

    private float artStrength = -1f;
    void setArtStrength(float s) { artStrength = s; art = new ArtTreatment(s); }
    float artStrength() { return artStrength; }

    static final int GAP = 0xFF121110;          // thin line between side-by-side photos

    /**
     * Full-screen look: 1-3 photos fill the screen edge to edge, side by side in equal columns, each
     * trimmed to fill its column. A single photo whose shape is far from the screen's (a lone portrait,
     * a panorama) is shown whole over a soft, dimmed, blurred copy of itself instead of being cropped hard.
     * offsetX/offsetY: a few pixels of drift between slides.
     */
    void drawFull(Canvas c, List<Bitmap> photos, int w, int h, float offsetX, float offsetY) {
        c.drawColor(GAP);
        int n = photos.size();
        if (n == 0) return;
        float gap = n > 1 ? Math.max(2f, 0.004f * h) : 0f;
        float colW = (w - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            Bitmap ph = photos.get(i);
            if (ph == null) continue;
            RectF col = new RectF(i * (colW + gap), 0, i * (colW + gap) + colW, h);
            float photoAspect = (float) ph.getWidth() / ph.getHeight(), colAspect = col.width() / col.height();
            if (n == 1 && (photoAspect / colAspect < 0.7f || photoAspect / colAspect > 1.45f)) {
                drawOnBlur(c, ph, col);
            } else {
                drawCover(c, ph, col, offsetX, offsetY);
            }
            art.drawSurface(c, col);
        }
    }

    /** Fills dst completely (cropping the overflow), slightly over-scaled so the drift never shows an edge. */
    private void drawCover(Canvas c, Bitmap ph, RectF dst, float dx, float dy) {
        float scale = Math.max(dst.width() / ph.getWidth(), dst.height() / ph.getHeight()) * 1.012f;
        float sw = dst.width() / scale, sh = dst.height() / scale;
        float sl = clamp((ph.getWidth() - sw) / 2 + dx / scale, 0, ph.getWidth() - sw);
        float st = clamp((ph.getHeight() - sh) / 2 + dy / scale, 0, ph.getHeight() - sh);
        p.setColorFilter(art.colorFilter());
        c.drawBitmap(ph, new Rect(Math.round(sl), Math.round(st), Math.round(sl + sw), Math.round(st + sh)), dst, p);
        p.setColorFilter(null);
    }

    /** Whole photo, centred, over a heavily blurred and darkened cover of itself. */
    private void drawOnBlur(Canvas c, Bitmap ph, RectF dst) {
        // Cheap blur: shrink to a few dozen pixels, then let filtered upscaling smear it.
        int bw = Math.max(1, ph.getWidth() / 48), bh = Math.max(1, ph.getHeight() / 48);
        Bitmap tiny = Bitmap.createScaledBitmap(ph, bw, bh, true);
        drawCover(c, tiny, dst, 0, 0);
        tiny.recycle();
        p.setColor(0x8C000000);
        c.drawRect(dst, p);
        float scale = Math.min(dst.width() / ph.getWidth(), dst.height() / ph.getHeight());
        float pw = ph.getWidth() * scale, phh = ph.getHeight() * scale;
        RectF r = new RectF(dst.centerX() - pw / 2, dst.centerY() - phh / 2, dst.centerX() + pw / 2, dst.centerY() + phh / 2);
        p.setColor(0x80000000);
        p.setMaskFilter(new BlurMaskFilter(0.02f * dst.height(), BlurMaskFilter.Blur.NORMAL));
        c.drawRect(r, p);
        p.setMaskFilter(null);
        p.setColorFilter(art.colorFilter());
        c.drawBitmap(ph, new Rect(0, 0, ph.getWidth(), ph.getHeight()), r, p);
        p.setColorFilter(null);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Framed look. offsetX/offsetY: a few pixels of drift between photos, to be kind to the panel. */
    void draw(Canvas c, Bitmap photo, int w, int h, float offsetX, float offsetY) {
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

        // The photo, given the matte "art print" treatment (see ArtTreatment.STRENGTH).
        p.setColorFilter(art.colorFilter());
        c.drawBitmap(photo, new Rect(0, 0, photo.getWidth(), photo.getHeight()), photoRect, p);
        p.setColorFilter(null);
        art.drawSurface(c, photoRect);
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
