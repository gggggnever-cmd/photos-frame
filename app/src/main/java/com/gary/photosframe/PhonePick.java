package com.gary.photosframe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.List;

/**
 * The phone's side of Add photos (asked 2026-11-25): the QR code opens a small page on the TV's phone server
 * (CornerServer) where the owner says where the photos go BEFORE picking — "New album" with a name, or "Add to an
 * album" from the list — then taps through to Google's picker.
 *
 * Guard rails (the owner: "error guard if I add a name and end up not selecting any photo or change my mind"):
 * - The choice is only a note on this one pick. Nothing is created until photos actually arrive.
 * - No pick (cancelled, code expired, Add photos closed) → the next code gets a new token and the note is dropped.
 * - It can be changed or cleared on the phone until Done; no choice means "ask on the TV as usual".
 * - After a pick without a choice the phone can still choose while the TV's choice screen is open.
 * One pick at a time; every method is synchronized (the server's threads and the add-photos thread share it).
 */
final class PhonePick {
    private PhonePick() { }

    static final int MAX_NAME = 60;

    /** Where the phone said the photos go: a new album's name, or an existing album/trip key. Empty = ask the TV. */
    static final class Choice {
        final String name, key;   // name set → new album; key set ("album:<id>" or "start~end") → add to it
        Choice(String name, String key) { this.name = name; this.key = key; }
        boolean isEmpty() { return name.isEmpty() && key.isEmpty(); }
        static final Choice NONE = new Choice("", "");
    }

    private static String token = "", pickerUri = "", state = "closed", result = "", label = "";
    private static Choice choice = Choice.NONE, late = Choice.NONE;
    private static int count;

    /** A new code is on the TV: a fresh token, no choice yet. Returns the token for the QR's address. */
    static synchronized String begin(String uri) {
        StringBuilder b = new StringBuilder();
        SecureRandom r = new SecureRandom();
        for (int i = 0; i < 8; i++) b.append("abcdefghjkmnpqrstuvwxyz23456789".charAt(r.nextInt(31)));
        token = b.toString();
        pickerUri = uri;
        state = "waiting";
        choice = Choice.NONE; late = Choice.NONE; result = ""; label = ""; count = 0;
        return token;
    }

    /** Add photos closed: the old page says so. */
    static synchronized void end() { state = "closed"; choice = Choice.NONE; late = Choice.NONE; }

    /** The TV got the pick. Returns what the phone chose beforehand (NONE if nothing). */
    static synchronized Choice picked(int n) { count = n; state = n == 0 ? "empty" : "picked"; return choice; }

    /** The batch went somewhere (shown on the phone). */
    static synchronized void done(String where) { result = where; state = "done"; }

    /** A choice sent from the phone after the pick, while the TV asks; taken once. */
    static synchronized Choice takeLate() { Choice c = late; late = Choice.NONE; return c; }

    static synchronized boolean matches(String t) { return !t.isEmpty() && t.equals(token); }

    /**
     * The phone sets (or clears) the choice: a new album's name, or the key of an existing one (with its name for
     * the page). Returns an error for the page, or null.
     */
    static synchronized String set(String t, String rawName, String key, String keyLabel) {
        if (!matches(t)) return "This code has been used. Scan the new code on the TV.";
        String n = clean(rawName), k = key == null ? "" : key.trim();
        Choice c = !k.isEmpty() ? new Choice("", k) : new Choice(n, "");
        if (state.equals("waiting")) { choice = c; label = k.isEmpty() ? n : clean(keyLabel); return null; }
        if (state.equals("picked")) {
            if (c.isEmpty()) return "Type a name or choose an album first.";
            late = c;
            return null;
        }
        return "Too late for this lot: it has been added already.";
    }

    /** What the page shows. */
    static synchronized String stateJson(String t) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", matches(t) ? state : "old");
            o.put("name", label);
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

    /** The albums for the page's list: [{key, name, count, label}]. */
    static String albumsJson(List<String[]> choices) {
        JSONArray a = new JSONArray();
        try {
            for (String[] c : choices)
                a.put(new JSONObject().put("key", c[0]).put("name", c[1]).put("count", Integer.parseInt(c[2])).put("label", c[3]));
        } catch (Exception ignored) { }
        return a.toString();
    }
}
