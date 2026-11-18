package com.gary.photosframe;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * PC preview of the print filter (same maths as the TV, from PrintEffects): renders a photo as the TV
 * would show it full screen, for Paper, Canvas and Paper cut at a filter level.
 *
 *   javac -d out app/src/main/java/com/gary/photosframe/PrintEffects.java tools/PrintPreview.java
 *   java -cp out com.gary.photosframe.PrintPreview photo.jpg outDir [level, default 2 = Maximum]
 *
 * Gallery light is left out. Output: outDir/<name>_paper.jpg, _canvas.jpg, _cut.jpg, _original.jpg.
 */
public class PrintPreview {
    public static void main(String[] a) throws Exception {
        File in = new File(a[0]), outDir = new File(a[1]);
        float s = a.length > 2 ? Float.parseFloat(a[2]) : 2f;
        outDir.mkdirs();
        BufferedImage img = ImageIO.read(in);
        int w = 1920, h = 1080;
        BufferedImage fit = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = fit.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        float scale = Math.max((float) w / img.getWidth(), (float) h / img.getHeight());
        int dw = Math.round(img.getWidth() * scale), dh = Math.round(img.getHeight() * scale);
        g.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        g.dispose();
        int[] src = fit.getRGB(0, 0, w, h, null, 0, w);
        String base = in.getName().replaceAll("\\.[^.]*$", "");
        save(src, w, h, new File(outDir, base + "_original.jpg"));
        PrintEffects.Tone tone = new PrintEffects.Tone(s);
        int[] paperTile = PrintEffects.tile(PrintEffects.paperHeight()), canvasTile = PrintEffects.tile(PrintEffects.canvasHeight());

        long t0 = System.currentTimeMillis();
        int[] p = new int[w * h];
        PrintEffects.soften(src, p, w, h, PrintEffects.soak(s), 0, h);
        tone.apply(p, 0, p.length);
        int[] c = p.clone();
        PrintEffects.overlay(p, w, h, paperTile, PrintEffects.paperAlpha(s));
        save(p, w, h, new File(outDir, base + "_paper.jpg"));
        PrintEffects.overlay(c, w, h, canvasTile, PrintEffects.canvasAlpha(s));
        save(c, w, h, new File(outDir, base + "_canvas.jpg"));
        long t1 = System.currentTimeMillis();

        int[] cut = new int[w * h];
        PrintEffects.Cut plan = PrintEffects.planCut(src, w, h);
        PrintEffects.cutRows(plan, cut, w, h, 0, h, PrintEffects.cutShadow(s), PrintEffects.cutReach(s, h));
        long t2 = System.currentTimeMillis();
        new PrintEffects.Tone(PrintEffects.cutToneLevel(s)).apply(cut, 0, cut.length);
        PrintEffects.overlay(cut, w, h, paperTile, PrintEffects.paperAlpha(s));
        save(cut, w, h, new File(outDir, base + "_cut.jpg"));
        System.out.println(base + ": paper+canvas " + (t1 - t0) + " ms, paper cut " + (t2 - t1) + " ms (PC, one thread)");
    }

    private static void save(int[] px, int w, int h, File f) throws Exception {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(out, "jpg", f);
    }
}
