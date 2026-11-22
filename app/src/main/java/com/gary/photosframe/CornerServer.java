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

    private final Commands commands;
    private final String key;
    private ServerSocket server;

    CornerServer(Context ctx, Commands commands) {
        this.commands = commands;
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

    void start() {
        if (server != null) return;
        try {
            server = new ServerSocket(PORT);
        } catch (IOException e) {
            Log.w("CornerServer", "can't listen on " + PORT + ": " + e);
            return;
        }
        ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                final Socket s;
                try { s = ss.accept(); } catch (IOException e) { continue; }
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

    void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
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
        String k = "", c = "", a = "";
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String name = kv.substring(0, eq), value = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
            if (name.equals("k")) k = value; else if (name.equals("c")) c = value; else if (name.equals("a")) a = value;
        }
        OutputStream out = s.getOutputStream();
        if (path.equals("/manifest.json")) {
            send(out, "200 OK", "application/manifest+json", "{\"name\":\"TV YouTube corner\",\"short_name\":\"TV YouTube\","
                    + "\"display\":\"standalone\",\"background_color\":\"#2e2b28\",\"theme_color\":\"#2e2b28\"}");
            return;
        }
        if (!key.equals(k)) {
            send(out, "403 Forbidden", "text/html; charset=utf-8", PhonePage.locked());
            return;
        }
        switch (path) {
            case "/":
                send(out, "200 OK", "text/html; charset=utf-8", PhonePage.html(key));
                break;
            case "/cmd": {
                String err = commands.command(c, a);
                send(out, "200 OK", "application/json", err == null ? "{\"ok\":true}"
                        : "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(err) + "}");
                break;
            }
            case "/state":
                send(out, "200 OK", "application/json", commands.state());
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

    /** The phone page (plain HTML + a little JavaScript, no outside files). */
    static final class PhonePage {
        static String locked() {
            return shell("<h1>YouTube corner</h1><p>Scan the code on the TV first: on the frame press ▲, then "
                    + "<b>YouTube corner</b>.</p>", "");
        }

        static String html(String key) {
            String body = "<h1>YouTube corner</h1>"
                    + "<div id=now class=now>Nothing playing</div>"
                    + "<textarea id=link rows=2 placeholder=\"Paste a YouTube link here\"></textarea>"
                    + "<button class=big id=go>Play on TV</button>"
                    + "<p id=err class=err></p>"
                    + "<div class=row><button data-c=prev>⏮</button><button data-c=toggle id=pp>⏯</button><button data-c=next>⏭</button></div>"
                    + "<div class=row><button data-c=voldown>Vol −</button><button data-c=volup>Vol +</button></div>"
                    + "<div class=row><button data-c=move>Move corner</button><button data-c=size id=size>Bigger</button></div>"
                    + "<div class=row><button data-c=close class=quiet>Close the corner</button></div>"
                    + "<details><summary>How to use</summary><ol>"
                    + "<li>In the YouTube app, open a video or playlist and tap <b>Share → Copy link</b>.</li>"
                    + "<li>Come back here, tap the box, choose <b>Paste</b>, then <b>Play on TV</b>.</li>"
                    + "<li>Your own playlists play if they're <b>Public</b> or <b>Unlisted</b> (not Private).</li>"
                    + "<li>Keep this page: Chrome menu ⋮ → <b>Add to Home screen</b>.</li></ol></details>";
            String js = "var K=" + org.json.JSONObject.quote(key) + ";"
                    + "function q(id){return document.getElementById(id)}"
                    + "function cmd(c,a){q('err').textContent='';return fetch('/cmd?k='+K+'&c='+c+(a?'&a='+encodeURIComponent(a):''))"
                    + ".then(function(r){return r.json()}).then(function(j){if(!j.ok)q('err').textContent=j.error;setTimeout(poll,600)})"
                    + ".catch(function(){q('err').textContent='Can\\u2019t reach the TV. Is the frame on, and is this phone on the home Wi-Fi?'})}"
                    + "q('go').onclick=function(){var t=q('link').value.trim();if(!t){q('err').textContent='Paste a YouTube link first.';return}"
                    + "cmd('play',t).then(function(){q('link').value=''})};"
                    + "document.querySelectorAll('[data-c]').forEach(function(b){b.onclick=function(){cmd(b.dataset.c)}});"
                    + "function poll(){fetch('/state?k='+K).then(function(r){return r.json()}).then(function(s){"
                    + "var t=s.showing?(s.title?s.title+(s.author?' \\u00b7 '+s.author:''):'Loading\\u2026'):'Nothing playing';"
                    + "if(s.showing&&s.count>1)t+='  ('+(s.index+1)+' of '+s.count+')';"
                    + "if(s.showing&&s.state==2)t='Paused: '+t;"
                    + "q('now').textContent=t;q('size').textContent=s.big?'Smaller':'Bigger'}).catch(function(){})}"
                    + "poll();setInterval(poll,3000);";
            return shell(body, js);
        }

        private static String shell(String body, String js) {
            return "<!doctype html><html><head><meta charset=utf-8>"
                    + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                    + "<link rel=manifest href=/manifest.json><meta name=theme-color content=#2e2b28>"
                    + "<title>TV YouTube</title><style>"
                    + "body{font:17px/1.45 system-ui,sans-serif;background:#2e2b28;color:#f1ede4;margin:0;padding:18px 16px;max-width:520px}"
                    + "h1{font:400 24px Georgia,serif;margin:0 0 10px}"
                    + ".now{background:#1e1c1a;border-radius:10px;padding:12px;margin-bottom:12px;color:#e8c9a0;min-height:22px}"
                    + "textarea{width:100%;box-sizing:border-box;font:16px system-ui;padding:10px;border-radius:8px;border:0}"
                    + "button{background:#f1ede4;color:#2e2b28;border:0;border-radius:10px;padding:14px 0;font-size:18px;flex:1}"
                    + "button.big{width:100%;margin:10px 0 4px;font-size:19px}button.quiet{background:#4a4540;color:#f1ede4}"
                    + ".row{display:flex;gap:10px;margin:10px 0}.err{color:#f0b3a8;min-height:1em;margin:4px 0}"
                    + "details{margin-top:18px;color:#cfc8bc}li{margin-bottom:8px}"
                    + "</style></head><body>" + body + "<script>" + js + "</script></body></html>";
        }
    }
}
