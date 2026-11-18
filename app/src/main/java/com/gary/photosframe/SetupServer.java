package com.gary.photosframe;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * A tiny web page served by the TV on the home Wi-Fi, only while the sign-in screen is open.
 * The phone opens it (via QR), taps "Sign in with Google", then pastes the resulting address back.
 */
final class SetupServer {
    static final int PORT = 8080;

    interface Listener { String onPasted(String pasted); }  // returns null on success, else an error message

    private final Auth auth;
    private final Listener listener;
    private ServerSocket server;
    private volatile boolean done;

    SetupServer(Auth auth, Listener listener) { this.auth = auth; this.listener = listener; }

    /** Starts serving; returns the address to show as a QR code, or null if the TV has no network address. */
    String start() throws IOException {
        String ip = lanAddress();
        if (ip == null) return null;
        server = new ServerSocket(PORT);
        Thread t = new Thread(() -> {
            // One thread per connection: phone browsers open spare "preconnect" sockets that send nothing,
            // and a single-threaded loop would sit on one of those while the real request is dropped.
            while (!server.isClosed()) {
                final Socket s;
                try { s = server.accept(); } catch (IOException e) { continue; }
                Thread h = new Thread(() -> {
                    Log.i("SetupServer", "connect from " + s.getRemoteSocketAddress());
                    try (Socket c = s) { handle(c); } catch (IOException e) { Log.w("SetupServer", "conn error " + s.getRemoteSocketAddress() + ": " + e); }
                }, "setup-conn");
                h.setDaemon(true);
                h.start();
            }
        }, "setup-server");
        t.setDaemon(true);
        t.start();
        return "http://" + ip + ":" + PORT + "/";
    }

    void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(15000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        Log.i("SetupServer", s.getRemoteSocketAddress() + " -> " + (line == null ? "(no request)" : line.replaceAll("code(=|%3D)(?:(?!%26)[^& ])*", "code=…")));
        if (line == null) return;
        String path = line.split(" ").length > 1 ? line.split(" ")[1] : "/";
        while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }

        String body;
        if (path.startsWith("/paste?")) {
            String pasted = "";
            for (String kv : path.substring(7).split("&"))
                if (kv.startsWith("u=")) pasted = URLDecoder.decode(kv.substring(2), "UTF-8");
            String err;
            synchronized (this) {  // connections are handled in parallel; sign in only once
                err = done ? null : listener.onPasted(pasted);
                if (err == null) done = true;
            }
            if (err == null) { body = page("Signed in", "<p class=ok>Done! Look at the TV — it will now show the photo picker.</p>"); }
            else body = page("Not quite", "<p class=err>" + esc(err) + "</p>" + form(auth.newSignInUrl()));
        } else if (path.equals("/") || path.startsWith("/?")) {
            body = done ? page("Signed in", "<p class=ok>Already signed in. Look at the TV.</p>")
                        : page("Sign in to Google Photos", form(auth.newSignInUrl()));
        } else {
            send(s.getOutputStream(), "404 Not Found", "text/plain", "Not found");
            return;
        }
        send(s.getOutputStream(), "200 OK", "text/html; charset=utf-8", body);
    }

    private static String form(String signInUrl) {
        return "<ol>"
                + "<li><a class=btn href=\"" + esc(signInUrl) + "\" target=\"_blank\" rel=\"noopener\">Sign in with Google</a><br>"
                + "Choose your account and tap <b>Allow</b>. If Google says the app isn't verified, tap "
                + "<b>Advanced</b> → <b>Go to Photos Frame</b>.</li>"
                + "<li>You'll end up on a page that <b>won't load</b> (\"can't be reached\"). That's expected. "
                + "Tap the address bar and <b>copy the whole address</b> (it starts with <code>http://127.0.0.1:8765</code>).</li>"
                + "<li>Come back to this tab, paste it below and tap <b>Send to TV</b>.</li></ol>"
                + "<form action=\"/paste\" method=\"get\"><textarea name=u rows=4 placeholder=\"http://127.0.0.1:8765/?state=…&code=…\"></textarea>"
                + "<button type=submit>Send to TV</button></form>";
    }

    private static String page(String title, String content) {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>Photos Frame</title><style>"
                + "body{font:17px/1.5 system-ui,sans-serif;background:#2e2b28;color:#f1ede4;margin:0;padding:20px 16px;max-width:560px}"
                + "h1{font:400 24px Georgia,serif}a.btn,button{display:inline-block;background:#f1ede4;color:#2e2b28;border:0;"
                + "border-radius:8px;padding:12px 18px;font-size:17px;text-decoration:none;margin:6px 0}"
                + "textarea{width:100%;box-sizing:border-box;font:15px monospace;padding:8px;border-radius:6px;border:0}"
                + "li{margin-bottom:14px}code{color:#e8c9a0}.ok{color:#bfe3b4;font-size:20px}.err{color:#f0b3a8}"
                + "</style></head><body><h1>" + esc(title) + "</h1>" + content + "</body></html>";
    }

    private static void send(OutputStream out, String status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The TV's address on the home network: a private (192.168.x / 10.x / 172.16-31.x) one if any, else any IPv4. */
    static String lanAddress() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }
}
