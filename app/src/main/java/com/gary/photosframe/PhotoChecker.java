package com.gary.photosframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background job: looks at each photo once and remembers (files/library/scores.tsv) its faces, sharpness
 * and brightness, for Places only and Highlights. Everything happens on the TV; nothing is uploaded.
 * Faces: ML Kit face detection with its model bundled in the app (no face recognition: it finds faces,
 * it doesn't know whose). People: a small object finder (EfficientDet-Lite0, COCO "person", in assets)
 * that sees whole bodies, so people from behind, side-on or small in the scene count too.
 * Runs at low priority with short pauses so the frame stays smooth.
 * New photos are checked after each download (kick() again).
 */
final class PhotoChecker {
    private static final int LONG_SIDE = 960;    // check a ~960 px copy: enough for faces and sharpness
    private static final int SAVE_EVERY = 25;

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile boolean again;

    static void kick(Context ctx) {
        again = true;
        if (!RUNNING.compareAndSet(false, true)) return;
        Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                while (again) { again = false; run(app); }
            } finally {
                RUNNING.set(false);
            }
        }, "photo-checker");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    private static void run(Context ctx) {
        PhotoLibrary lib = PhotoLibrary.get(ctx);
        List<String> todo = lib.unscored();
        if (todo.isEmpty()) return;
        long t0 = SystemClock.uptimeMillis();
        Log.i("PhotoChecker", "checking " + todo.size() + " photos");
        FaceDetector detector = null;
        try {
            detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setMinFaceSize(0.015f)
                    .build());
        } catch (Throwable e) {
            Log.w("PhotoChecker", "ML Kit unavailable, using the basic face finder: " + e);
        }
        ObjectDetector bodies = null;
        try {
            bodies = ObjectDetector.createFromFileAndOptions(ctx, "person_detector.tflite",
                    ObjectDetector.ObjectDetectorOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                            .setScoreThreshold(BODY_SCORE)
                            .setMaxResults(10)
                            .setLabelAllowList(Collections.singletonList("person"))
                            .build());
        } catch (Throwable e) {
            Log.w("PhotoChecker", "person finder unavailable, faces only: " + e);
        }
        Map<String, Highlights.Score> batch = new HashMap<>();
        int done = 0, people = 0;
        for (String id : todo) {
            Highlights.Score s = null;
            try {
                s = check(id, lib.score(id), detector, bodies);
            } catch (Throwable e) {
                Log.w("PhotoChecker", "skipped " + id + ": " + e);
            }
            if (s != null) {
                batch.put(id, s);
                done++;
                if (s.hasPerson()) people++;
            }
            if (batch.size() >= SAVE_EVERY) { lib.addScores(batch, false); batch.clear(); }
            if (done % 200 == 0 && done > 0)
                Log.i("PhotoChecker", done + " of " + todo.size() + " checked, " + people + " with people");
            SystemClock.sleep(30);
        }
        lib.addScores(batch, true);
        if (detector != null) detector.close();
        if (bodies != null) bodies.close();
        Log.i("PhotoChecker", "done: " + done + " photos in " + (SystemClock.uptimeMillis() - t0) / 1000 + " s, "
                + people + " with people, " + (done - people) + " places");
    }

    /** Person finder confidence from which a box counts (small people often score 0.4-0.6). */
    private static final float BODY_SCORE = 0.4f;

    /** old = an earlier result for this photo (faces/sharpness kept, only the person finder runs), or null. */
    private static Highlights.Score check(String id, Highlights.Score old, FaceDetector detector,
                                          ObjectDetector bodies) throws Exception {
        if (!id.startsWith("file:")) return null;
        String path = id.substring(5);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0) return null;
        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= LONG_SIDE) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        if (b == null) return null;
        try {
            float body = bodies == null ? 0 : tallestPerson(b, bodies);
            Log.d("PhotoChecker", "person " + path.substring(path.lastIndexOf('/') + 1) + " " + body);
            if (old != null) return old.withBody(body);
            int bw = b.getWidth(), bh = b.getHeight(), longSide = Math.max(bw, bh);
            int[] px = new int[bw * bh];
            b.getPixels(px, 0, bw, 0, 0, bw, bh);
            float[] tone = toneStats(px, bw, bh);
            int faces = 0;
            float maxFace = 0;
            if (detector != null) {
                List<Face> found = Tasks.await(detector.process(InputImage.fromBitmap(b, 0)));
                for (Face f : found) {
                    Rect r = f.getBoundingBox();
                    faces++;
                    maxFace = Math.max(maxFace, (float) r.height() / longSide);
                }
            } else {
                float[] basic = basicFaces(b);
                faces = (int) basic[0];
                maxFace = basic[1];
            }
            return new Highlights.Score(faces, maxFace, tone[0], tone[1], body);
        } finally {
            b.recycle();
        }
    }

    /** Height of the tallest person found, as a share of the photo's height (0 if nobody). */
    private static float tallestPerson(Bitmap b, ObjectDetector bodies) {
        List<Detection> found = bodies.detect(TensorImage.fromBitmap(b));
        float max = 0;
        for (Detection d : found) max = Math.max(max, d.getBoundingBox().height() / b.getHeight());
        return Math.min(max, 1f);
    }

    /** {variance of the Laplacian (sharpness), mean brightness 0..1} on the grey image. */
    static float[] toneStats(int[] px, int w, int h) {
        float[] grey = new float[w * h];
        double sum = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float y = 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
            grey[i] = y;
            sum += y;
        }
        double ls = 0, ls2 = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double lap = 4 * grey[i] - grey[i - 1] - grey[i + 1] - grey[i - w] - grey[i + w];
                ls += lap;
                ls2 += lap * lap;
                n++;
            }
        }
        double mean = n == 0 ? 0 : ls / n;
        double var = n == 0 ? 0 : ls2 / n - mean * mean;
        return new float[]{(float) var, (float) (sum / px.length / 255.0)};
    }

    /** Fallback if ML Kit can't load: Android's built-in (frontal faces only) finder. {count, largest}. */
    private static float[] basicFaces(Bitmap b) {
        int w = b.getWidth() & ~1, h = b.getHeight();
        Bitmap c = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        new android.graphics.Canvas(c).drawBitmap(b, 0, 0, null);
        android.media.FaceDetector.Face[] faces = new android.media.FaceDetector.Face[12];
        int n = new android.media.FaceDetector(w, h, faces.length).findFaces(c, faces);
        float max = 0;
        for (int i = 0; i < n; i++) max = Math.max(max, faces[i].eyesDistance() * 2.4f / Math.max(w, h));
        c.recycle();
        return new float[]{n, max};
    }
}
