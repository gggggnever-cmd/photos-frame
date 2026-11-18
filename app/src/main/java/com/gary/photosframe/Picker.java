package com.gary.photosframe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Google Photos Picker API: create a session, wait for the user to pick, download the picks. */
final class Picker {
    private static final String API = "https://photospicker.googleapis.com/v1";

    static final class Session {
        final String id, pickerUri;
        final long pollMs, timeoutAtMs;
        Session(JSONObject j) {
            id = j.optString("id");
            pickerUri = j.optString("pickerUri");
            JSONObject pc = j.optJSONObject("pollingConfig");
            pollMs = Math.max(2000, Http.durationMs(pc == null ? null : pc.optString("pollInterval"), 5000));
            timeoutAtMs = System.currentTimeMillis() + Http.durationMs(pc == null ? null : pc.optString("timeoutIn"), 30 * 60 * 1000);
        }
    }

    static final class Item {
        final String id, baseUrl;
        Item(String id, String baseUrl) { this.id = id; this.baseUrl = baseUrl; }
    }

    interface Progress { void onProgress(int done, int total); }

    private final Auth auth;
    Picker(Auth auth) { this.auth = auth; }

    Session create() throws IOException {
        return new Session(Http.json("POST", API + "/sessions", auth.token(), "application/json", "{}"));
    }

    boolean isDone(Session s) throws IOException {
        return Http.json("GET", API + "/sessions/" + s.id, auth.token(), null, null).optBoolean("mediaItemsSet");
    }

    List<Item> photos(Session s) throws IOException {
        List<Item> out = new ArrayList<>();
        String page = null;
        do {
            String url = API + "/mediaItems?pageSize=100&sessionId=" + URLEncoder.encode(s.id, "UTF-8")
                    + (page == null ? "" : "&pageToken=" + URLEncoder.encode(page, "UTF-8"));
            JSONObject r = Http.json("GET", url, auth.token(), null, null);
            JSONArray items = r.optJSONArray("mediaItems");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                JSONObject mf = it.optJSONObject("mediaFile");
                if (mf != null && "PHOTO".equals(it.optString("type"))) out.add(new Item(it.optString("id"), mf.optString("baseUrl")));
            }
            page = r.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null);
        return out;
    }

    /**
     * Downloads each photo sized to fit 4K (3840x2160, aspect kept) — the TV's full resolution.
     * Already-downloaded photos are skipped, so re-picking only adds new ones. Returns how many were new.
     */
    int download(List<Item> items, File dir, Progress progress) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        int added = 0, done = 0;
        for (Item it : items) {
            File dest = new File(dir, safe(it.id) + ".jpg");
            if (!dest.exists()) {
                Http.download(it.baseUrl + "=w3840-h2160", auth.token(), dest);
                added++;
            }
            progress.onProgress(++done, items.size());
        }
        return added;
    }

    void delete(Session s) {
        try { Http.json("DELETE", API + "/sessions/" + s.id, auth.token(), null, null); } catch (IOException ignored) { }
    }

    private static String safe(String id) { return id.replaceAll("[^A-Za-z0-9_-]", "_"); }
}
