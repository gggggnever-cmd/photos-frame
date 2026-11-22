package com.gary.photosframe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a YouTube link shared from the phone (pure Java, tested on the PC). Accepts youtube.com/watch,
 * youtu.be, music.youtube.com, /shorts/, /live/, /embed/ and /playlist links, with or without extra text
 * around them (some share sheets add the title).
 */
final class YouTubeLink {
    final String video;   // 11-character video id, or null
    final String list;    // playlist id, or null

    private YouTubeLink(String video, String list) { this.video = video; this.list = list; }

    private static final Pattern URL = Pattern.compile("https?://\\S+|(?:www\\.|m\\.|music\\.)?youtu(?:be\\.com|\\.be)/\\S+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern LIST = Pattern.compile("[A-Za-z0-9_-]{10,64}");

    /** The video and/or playlist in the text, or null if it has no YouTube link. */
    static YouTubeLink parse(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text.trim());
        while (m.find()) {
            YouTubeLink l = parseUrl(m.group());
            if (l != null) return l;
        }
        return null;
    }

    private static YouTubeLink parseUrl(String url) {
        String u = url.replaceFirst("(?i)^https?://", "");
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int slash = u.indexOf('/');
        String host = (slash < 0 ? u : u.substring(0, slash)).toLowerCase(java.util.Locale.ROOT);
        String rest = slash < 0 ? "" : u.substring(slash + 1);
        String path = rest, query = "";
        int q = rest.indexOf('?');
        if (q >= 0) { path = rest.substring(0, q); query = rest.substring(q + 1); }

        String video = null, list = null;
        for (String kv : query.split("&")) {
            if (kv.startsWith("v=")) video = id(kv.substring(2));
            else if (kv.startsWith("list=")) list = listId(kv.substring(5));
        }
        if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
            video = id(path);
        } else if (host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com")) {
            String[] parts = path.split("/");
            if (parts.length >= 2 && (parts[0].equals("shorts") || parts[0].equals("live") || parts[0].equals("embed")))
                video = id(parts[1]);
        } else {
            return null;
        }
        if (video == null && list == null) return null;
        return new YouTubeLink(video, list);
    }

    private static String id(String s) {
        String t = s.length() > 11 ? s.substring(0, 11) : s;
        if (!ID.matcher(t).matches()) return null;
        if (s.length() > 11 && Character.isLetterOrDigit(s.charAt(11))) return null;   // longer word, not an id
        return t;
    }

    private static String listId(String s) {
        int end = 0;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_' || s.charAt(end) == '-')) end++;
        String t = s.substring(0, end);
        // "RD…" lists are YouTube's own mixes made for the signed-in viewer; logged out they rarely play.
        return LIST.matcher(t).matches() ? t : null;
    }
}
