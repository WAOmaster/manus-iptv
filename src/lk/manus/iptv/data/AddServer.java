package lk.manus.iptv.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny LAN web page so a phone can send a playlist link to the TV. Runs only while the Add-source screen is open.
 * A 6-digit one-time code (shown on the TV) guards the form; 5 wrong tries regenerate it. Bodies are capped at 8 KB.
 * Nothing the phone sends is ever logged.
 */
public final class AddServer {
    public interface Listener {
        void onCode(String code);                 // code (re)generated
        void onReceived(String url, String name); // correct code, main thread
    }

    private static final int MAX_BODY = 8192, MAX_WRONG = 5;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom rnd = new SecureRandom();
    private volatile ServerSocket server;
    private volatile String code = "";
    private int wrong;
    private int port;

    private final boolean feed; // true = the page sends a channel data feed address instead of a playlist

    public AddServer(Listener l) { this(l, false); }

    public AddServer(Listener l, boolean feed) { listener = l; this.feed = feed; }

    public String code() { return code; }

    /** "http://192.168.x.x:8765/" or null when the TV has no LAN address. */
    public String address() {
        String ip = lanIp();
        return ip == null || port == 0 ? null : "http://" + ip + ":" + port + "/";
    }

    public synchronized boolean start() {
        if (server != null) return true;
        newCode();
        try {
            ServerSocket s;
            try { s = new ServerSocket(8765); } catch (IOException e) { s = new ServerSocket(0); }
            server = s;
            port = s.getLocalPort();
        } catch (IOException e) {
            Log.w("IPTV", "add server not started: " + e.getClass().getSimpleName());
            return false;
        }
        final ServerSocket ss = server;
        new Thread(new Runnable() {
            @Override public void run() {
                while (!ss.isClosed()) {
                    try (Socket c = ss.accept()) {
                        c.setSoTimeout(5000);
                        handle(c);
                    } catch (Exception e) {
                        if (ss.isClosed()) break;
                    }
                }
            }
        }, "add-server").start();
        Log.i("IPTV", "add server listening on port " + port);
        return true;
    }

    public synchronized void stop() {
        ServerSocket s = server;
        server = null;
        port = 0;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private synchronized void newCode() {
        code = String.format("%06d", rnd.nextInt(1000000));
        wrong = 0;
        final String c = code;
        main.post(new Runnable() { @Override public void run() { listener.onCode(c); } });
    }

    private void handle(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        // read headers (cap 16 KB), then the body
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0, b;
        while (state < 4 && head.size() < 16384 && (b = in.read()) >= 0) {
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        if (state < 4) { reply(c, 400, "Bad request"); return; }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2) { reply(c, 400, "Bad request"); return; }
        int len = 0;
        for (String l : lines) {
            if (l.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try { len = Integer.parseInt(l.substring(15).trim()); } catch (NumberFormatException e) { len = -1; }
            }
        }
        String path = req[1];
        if (req[0].equals("GET") && (path.equals("/") || path.startsWith("/?"))) { reply(c, 200, feed ? FORM.replace("Add a playlist to Manus TV", "Set the channel data feed").replace("Playlist link (M3U)", "Feed address (the link to your data feed)").replace("<label>Name (optional)<input name=name maxlength=60 style='width:100%;font-size:18px;padding:10px;box-sizing:border-box'></label>", "") : FORM); return; }
        if (!req[0].equals("POST") || !path.equals("/")) { reply(c, 404, page("Not found", "Nothing here.")); return; }
        if (len < 0 || len > MAX_BODY) { reply(c, 413, page("Too big", "That request is too large.")); return; }
        byte[] body = new byte[len];
        int got = 0;
        while (got < len) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
        Map<String, String> f = form(new String(body, 0, got, StandardCharsets.UTF_8));
        String url = f.get("url") == null ? "" : f.get("url").trim();
        String name = f.get("name") == null ? "" : f.get("name").trim();
        synchronized (this) {
            if (!code.equals(f.get("code"))) {
                boolean regen = ++wrong >= MAX_WRONG;
                if (regen) newCode();
                reply(c, 403, page("Wrong code", "That code is not right. Check the 6 digits on the TV."
                        + (regen ? " Too many tries: the TV now shows a new code." : "")));
                return;
            }
        }
        if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length() > 2000) {
            reply(c, 400, page("Not a link", "The link must start with http:// or https://. Go back and try again."));
            return;
        }
        newCode(); // one-time
        final String u = url, n = name.length() > 60 ? name.substring(0, 60) : name;
        main.post(new Runnable() { @Override public void run() { listener.onReceived(u, n); } });
        reply(c, 200, page("Sent to the TV", "Done. Look at the TV: it is checking the link and will ask you to press Save."));
    }

    private static Map<String, String> form(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            try { m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8")); }
            catch (Exception ignored) { }
        }
        return m;
    }

    private static void reply(Socket c, int status, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + status + " X\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream o = c.getOutputStream();
        o.write(h.getBytes(StandardCharsets.ISO_8859_1));
        o.write(b);
        o.flush();
    }

    private static String page(String title, String msg) {
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><body style='font:18px sans-serif;"
                + "margin:24px;background:#0b0f19;color:#f5f7ff'><h2>" + title + "</h2><p>" + msg + "</p><p><a style='color:#22d3ee' href='/'>Back</a></p>";
    }

    private static final String FORM = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<body style='font:18px sans-serif;margin:24px;background:#0b0f19;color:#f5f7ff'><h2>Add a playlist to Manus TV</h2>"
            + "<form method=post action=/ style='display:flex;flex-direction:column;gap:14px'>"
            + "<label>Playlist link (M3U)<input name=url type=url required placeholder='https://...' style='width:100%;font-size:18px;padding:10px;box-sizing:border-box'></label>"
            + "<label>Name (optional)<input name=name maxlength=60 style='width:100%;font-size:18px;padding:10px;box-sizing:border-box'></label>"
            + "<label>6-digit code shown on the TV<input name=code required inputmode=numeric pattern='[0-9]{6}' maxlength=6 style='width:100%;font-size:18px;padding:10px;box-sizing:border-box'></label>"
            + "<button style='font-size:20px;padding:12px;background:#7c5cff;color:#fff;border:0;border-radius:12px'>Send to TV</button></form>";

    private static String lanIp() {
        try {
            String best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")) return a.getHostAddress();
                        if (best == null) best = a.getHostAddress();
                    }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }
}
