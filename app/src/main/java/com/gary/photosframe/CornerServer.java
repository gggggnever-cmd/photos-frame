package com.gary.photosframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The phone's remote for the YouTube corner: a small page served by the TV on the home Wi-Fi while the
 * frame is open. The QR code (Settings → YouTube corner) holds the address with a private key, so only
 * phones that scanned it can control the TV. The phone pastes a shared YouTube link and taps Play.
 */
final class CornerServer {
    static final int PORT = 8081;

    /** Runs a phone command on the TV; returns null if fine, else a message for the phone. */
    interface Commands {
        String command(String c, String arg);
        String state();
    }

    // One server for the whole app (2026-11-21 fix): it used to belong to the frame screen, and when that
    // screen was re-created the new one couldn't open port 8081 while the old one still held it, so the
    // phone got no answer. Now it starts once, retries if the port is busy, and the frame screen that is
    // currently open receives the commands.
    private static CornerServer instance;
    private static volatile Commands active;

    /** The open frame screen takes the phone's commands (starts the server the first time). */
    static synchronized void attach(Context ctx, Commands c) {
        active = c;
        if (instance == null) { instance = new CornerServer(ctx.getApplicationContext()); instance.start(); }
    }

    /** Starts the server without a frame screen (the always-on helper calls this, for the phones' bedtime). */
    static synchronized void ensureRunning(Context ctx) {
        if (instance == null) { instance = new CornerServer(ctx.getApplicationContext()); instance.start(); }
    }

    /** The frame screen is closing: stop sending it commands (the server keeps listening). */
    static synchronized void detach(Commands c) { if (active == c) active = null; }

    private final String key;
    private final Context app;
    private ServerSocket server;

    private CornerServer(Context ctx) {
        app = ctx;
        SharedPreferences p = ctx.getSharedPreferences("corner", Context.MODE_PRIVATE);
        String k = p.getString("key", "");
        if (k.isEmpty()) {
            StringBuilder b = new StringBuilder();
            SecureRandom r = new SecureRandom();
            String abc = "abcdefghjkmnpqrstuvwxyz23456789";
            for (int i = 0; i < 10; i++) b.append(abc.charAt(r.nextInt(abc.length())));
            k = b.toString();
            p.edit().putString("key", k).apply();
        }
        key = k;
    }

    /** The address for the QR code, or null if the TV has no network address. */
    static String address(Context ctx) {
        String ip = SetupServer.lanAddress();
        if (ip == null) return null;
        String k = ctx.getSharedPreferences("corner", Context.MODE_PRIVATE).getString("key", "");
        return "http://" + ip + ":" + PORT + "/?k=" + k;
    }

    private void start() {
        Thread t = new Thread(() -> {
            ServerSocket ss = null;
            while (ss == null) {
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress(PORT));
                } catch (IOException e) {
                    Log.w("CornerServer", "can't listen on " + PORT + " yet: " + e);
                    try { if (ss != null) ss.close(); } catch (IOException ignored) { }
                    ss = null;
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                }
            }
            server = ss;
            Log.i("CornerServer", "listening on " + PORT);
            while (!ss.isClosed()) {
                final Socket s;
                try { s = ss.accept(); } catch (IOException e) { continue; }
                Log.i("CornerServer", "connection from " + s.getInetAddress().getHostAddress());
                Thread h = new Thread(() -> {
                    try (Socket c = s) { handle(c); } catch (IOException e) { /* phone went away */ }
                }, "corner-conn");
                h.setDaemon(true);
                h.start();
            }
        }, "corner-server");
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(15000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null) return;
        String[] parts = line.split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }

        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
        String k = "", c = "", a = "", who = "", charging = "", tok = "", pname = "", palbum = "";
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String name = kv.substring(0, eq), value = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
            if (name.equals("k")) k = value; else if (name.equals("c")) c = value; else if (name.equals("a")) a = value;
            else if (name.equals("who")) who = value; else if (name.equals("charging")) charging = value;
            else if (name.equals("s")) tok = value; else if (name.equals("name")) pname = value;
            else if (name.equals("album")) palbum = value;
        }
        OutputStream out = s.getOutputStream();
        Log.i("CornerServer", "request " + path + (c.isEmpty() ? "" : " " + c) + (key.equals(k) ? "" : " (no or wrong key)"));
        if (path.equals("/manifest.json")) {
            send(out, "200 OK", "application/manifest+json", "{\"name\":\"TV YouTube corner\",\"short_name\":\"TV YouTube\","
                    + "\"display\":\"standalone\",\"background_color\":\"#1d1a18\",\"theme_color\":\"#1d1a18\"}");
            return;
        }
        if (!key.equals(k)) {
            send(out, "403 Forbidden", "text/html; charset=utf-8", PhonePage.locked());
            return;
        }
        switch (path) {
            case "/":
                send(out, "200 OK", "text/html; charset=utf-8", PhonePage.html(app, key));
                break;
            case "/cmd": {
                Commands commands = active;
                String err = commands == null ? "Photos Frame isn't open on the TV. Press the art button, then try again."
                        : commands.command(c, a);
                send(out, "200 OK", "application/json", err == null ? "{\"ok\":true}"
                        : "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(err) + "}");
                break;
            }
            case "/phone":   // a phone automation: plugged in or unplugged (see Bedtime)
                send(out, "200 OK", "text/plain; charset=utf-8", charging.equals("forget") ? Bedtime.forget(app, who)
                        : Bedtime.report(app, who, charging.equals("1") || charging.equalsIgnoreCase("true")));
                break;
            // Add photos from the phone (PhonePick): the page, where the photos go (before or after the pick), the state.
            case "/pick":
                send(out, "200 OK", "text/html; charset=utf-8", PhonePage.pick(app, key, tok));
                break;
            case "/pickname": {
                String label = "";   // the chosen album's name, for the page
                if (!palbum.isEmpty())
                    for (String[] r : PhotoLibrary.get(app).namedChoices()) if (r[0].equals(palbum)) label = r[1];
                String err = !palbum.isEmpty() && label.isEmpty() ? "That album isn't on the TV any more."
                        : PhonePick.set(tok, pname, palbum, label);
                send(out, "200 OK", "application/json", err == null ? "{\"ok\":true}"
                        : "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(err) + "}");
                break;
            }
            case "/pickstate":
                send(out, "200 OK", "application/json", PhonePick.stateJson(tok));
                break;
            case "/state":
                Commands now = active;
                send(out, "200 OK", "application/json", now == null ? "{\"showing\":false}" : now.state());
                break;
            default:
                send(out, "404 Not Found", "text/plain", "Not found");
        }
    }

    private static void send(OutputStream out, String status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    /**
     * The phone page. It lives in assets/corner_phone.html (one file: styles, icons and script, nothing
     * fetched from outside) so it can be edited as a web page instead of as Java strings.
     */
    static final class PhonePage {
        private static String template;   // read once: assets can't change while the app runs

        /** The remote, with this TV's key written into its script. Only sent to a phone that already has the key. */
        static String html(Context ctx, String key) {
            String t = template;
            if (t == null) {
                t = asset(ctx, "corner_phone.html");
                if (t == null) return message("The remote's page is missing from this build of Photos Frame.");
                template = t;
            }
            // JSON-quoted, so whatever the key holds it stays one JavaScript string.
            return t.replace("__KEY__", org.json.JSONObject.quote(key));
        }

        /** The Add photos page for one pick (token tok); an old token gets the page, which then says "used". */
        static String pick(Context ctx, String key, String tok) {
            String t = asset(ctx, "pick_phone.html");
            if (t == null) return message("The Add photos page is missing from this build of Photos Frame.");
            String albums;
            try { albums = PhonePick.albumsJson(PhotoLibrary.get(ctx).namedChoices()); } catch (RuntimeException e) { albums = "[]"; }
            return t.replace("__KEY__", org.json.JSONObject.quote(key))
                    .replace("__TOKEN__", org.json.JSONObject.quote(tok))
                    .replace("__PICKER__", org.json.JSONObject.quote(PhonePick.pickerUri(tok)))
                    .replace("__ALBUMS__", albums);
        }

        /** No key or the wrong key: says how to get the code, and nothing else. */
        static String locked() {
            return message("Scan the code on the TV first: on the frame press \u25b2, then <b>YouTube corner</b>.");
        }

        private static String message(String html) {
            return "<!doctype html><html lang=en><head><meta charset=utf-8>"
                    + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                    + "<meta name=theme-color content=#1d1a18><title>TV YouTube</title><style>"
                    + "body{font:17px/1.5 system-ui,sans-serif;background:#1d1a18;color:#f1ede4;margin:0 auto;max-width:440px;padding:22px 20px}"
                    + "p.e{margin:0;font-size:11px;letter-spacing:.16em;text-transform:uppercase;color:#a69d90}"
                    + "h1{font:400 26px/1.15 Georgia,serif;margin:2px 0 16px}"
                    + "div{background:#292522;border:1px solid rgba(241,237,228,.09);border-radius:20px;padding:18px;color:#cfc8bc}"
                    + "b{color:#f1ede4}</style></head><body><p class=e>Photos Frame</p><h1>YouTube corner</h1><div>"
                    + html + "</div></body></html>";
        }

        private static String asset(Context ctx, String name) {
            try (java.io.InputStream in = ctx.getAssets().open(name)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            } catch (IOException e) {
                Log.w("CornerServer", "can't read " + name + ": " + e);
                return null;
            }
        }
    }
}
