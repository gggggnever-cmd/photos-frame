package com.gary.photosframe;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Tiny blocking HTTP helpers. Call only from a background thread. */
final class Http {
    static final class HttpError extends IOException {
        final int code; final String body;
        HttpError(int code, String body) { super("HTTP " + code + ": " + body); this.code = code; this.body = body; }
    }

    static JSONObject form(String url, Map<String, String> fields) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
              .append(java.net.URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return json("POST", url, null, "application/x-www-form-urlencoded", sb.toString());
    }

    static JSONObject json(String method, String url, String token, String contentType, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) throw new HttpError(code, text);
        try { return text.isEmpty() ? new JSONObject() : new JSONObject(text); }
        catch (org.json.JSONException e) { throw new IOException("Bad JSON from " + url, e); }
    }

    /** Streams url to dest via a temp file, so a half-finished download never shows up as a photo. */
    static void download(String url, String token, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        if (code >= 400) throw new HttpError(code, read(c.getErrorStream()));
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { c.disconnect(); }
        if (!tmp.renameTo(dest)) throw new IOException("Could not save " + dest);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /** Google API durations look like "5s" or "1799.5s". */
    static long durationMs(String d, long fallback) {
        if (d == null || d.isEmpty()) return fallback;
        try { return (long) (Double.parseDouble(d.replace("s", "")) * 1000); }
        catch (NumberFormatException e) { return fallback; }
    }
}
