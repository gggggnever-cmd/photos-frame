package com.gary.photosframe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.List;

/**
 * The phone's side of Add photos (asked 2026-11-25): the QR code opens a small page on the TV's phone server
 * (CornerServer) where the owner may type the album's name BEFORE picking, then taps through to Google's picker.
 *
 * Guard rails (the owner: "error guard if I add a name and end up not selecting any photo or change my mind"):
 * - A name is only a note on this one pick. Nothing is created until photos actually arrive.
 * - No pick (cancelled, code expired, Add photos closed) → the next code gets a new token and the note is dropped.
 * - The name can be changed or cleared on the phone until Done; an empty name means "ask on the TV as usual".
 * - After a pick without a name, the phone can still name it while the TV's choice screen is open.
 * One pick at a time; every method is synchronized (the server's threads and the add-photos thread share it).
 */
final class PhonePick {
    private PhonePick() { }

    static final int MAX_NAME = 60;

    private static String token = "", pickerUri = "", state = "closed", name = "", lateName = "", result = "";
    private static int count;

    /** A new code is on the TV: a fresh token, no name yet. Returns the token for the QR's address. */
    static synchronized String begin(String uri) {
        StringBuilder b = new StringBuilder();
        SecureRandom r = new SecureRandom();
        for (int i = 0; i < 8; i++) b.append("abcdefghjkmnpqrstuvwxyz23456789".charAt(r.nextInt(31)));
        token = b.toString();
        pickerUri = uri;
        state = "waiting";
        name = ""; lateName = ""; result = ""; count = 0;
        return token;
    }

    /** Add photos closed (or the code expired and a new one replaces it): the old page says so. */
    static synchronized void end() { state = "closed"; name = ""; lateName = ""; }

    /** The TV got the pick. Returns the name typed beforehand ("" if none). */
    static synchronized String picked(int n) { count = n; state = n == 0 ? "empty" : "picked"; return name; }

    /** The batch went somewhere (album name or trip label shown on the phone). */
    static synchronized void done(String where) { result = where; state = "done"; }

    /** A name sent from the phone after the pick, while the TV asks; taken once. */
    static synchronized String takeLateName() { String n = lateName; lateName = ""; return n; }

    static synchronized boolean matches(String t) { return !t.isEmpty() && t.equals(token); }

    /** The phone sets (or clears) the name. Returns an error for the page, or null. */
    static synchronized String setName(String t, String raw) {
        if (!matches(t)) return "This code has been used. Scan the new code on the TV.";
        String n = clean(raw);
        if (state.equals("waiting")) { name = n; return null; }
        if (state.equals("picked")) {
            if (n.isEmpty()) return "Type a name first.";
            lateName = n;
            return null;
        }
        return "Too late to name this lot: it has been added already.";
    }

    /** What the page shows. */
    static synchronized String stateJson(String t) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", matches(t) ? state : "old");
            o.put("name", name);
            o.put("count", count);
            o.put("result", result);
            return o.toString();
        } catch (Exception e) { return "{\"state\":\"old\"}"; }
    }

    static synchronized String pickerUri(String t) { return matches(t) ? pickerUri : ""; }

    /** Trims, folds runs of spaces, drops control characters, at most MAX_NAME characters. */
    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : raw.toCharArray()) if (!Character.isISOControl(c)) b.append(c);
        String n = b.toString().trim().replaceAll("\\s+", " ");
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME).trim() : n;
    }

    /** The albums for the page's suggestions, as a JSON array of names. */
    static String albumsJson(List<String[]> choices) {
        JSONArray a = new JSONArray();
        for (String[] c : choices) a.put(c[1]);
        return a.toString();
    }
}
