package lk.manus.iptv.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Lazy, polite liveness checker: 2 workers, 6 s timeouts, newest request first, queue capped at 40, pausable. Feeds {@link Health}. */
public final class Prober {
    public interface Listener { void onProbed(String url, boolean ok); } // main thread

    private static final String TAG = "Prober";
    private static final int WORKERS = 2, CAP = 40, MAX_READ = 64 * 1024, TIMEOUT = 6000, OFFLINE_STREAK = 10;
    private static Prober inst;

    private final Health health;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Channel> queue = new ArrayDeque<>(); // head = newest
    private final Set<String> pending = new HashSet<>();          // queued or in flight
    private boolean paused, started;
    private int failStreak;
    private volatile Listener listener;

    public static synchronized Prober get(Context c) {
        if (inst == null) inst = new Prober(Health.get(c));
        return inst;
    }

    private Prober(Health h) { health = h; }

    public void setListener(Listener l) { listener = l; }

    public synchronized void setPaused(boolean p) {
        paused = p;
        if (!p) notifyAll();
    }

    public void request(Channel ch) {
        if (ch == null || !isHttp(ch.url) || !health.needsProbe(ch.url)) return;
        synchronized (this) {
            if (!pending.add(ch.url)) return;
            queue.addFirst(ch);
            if (queue.size() > CAP) pending.remove(queue.removeLast().url);
            if (!started) {
                started = true;
                for (int i = 0; i < WORKERS; i++) {
                    Thread t = new Thread(this::loop, "prober-" + i);
                    t.setDaemon(true);
                    t.start();
                }
            }
            notifyAll();
        }
    }

    private void loop() {
        while (true) {
            Channel ch;
            synchronized (this) {
                while (paused || queue.isEmpty()) {
                    try { wait(); } catch (InterruptedException e) { return; }
                }
                ch = queue.removeFirst(); // url stays in pending while in flight
            }
            boolean ok = false;
            try { ok = probe(ch); } catch (Throwable t) { Log.w(TAG, "probe " + Redact.host(ch.url) + ": " + t); }
            boolean offline;
            synchronized (this) {
                pending.remove(ch.url);
                failStreak = ok ? 0 : failStreak + 1;
                offline = failStreak >= OFFLINE_STREAK;
            }
            if (offline) { // ten failures in a row looks like no network, not ten dead streams: do not blame the channels
                try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
                continue;
            }
            Log.i(TAG, Redact.host(ch.url) + (ok ? " alive" : " DEAD") + " (" + ch.name + ")");
            health.record(ch.url, ok);
            health.flushAsync();
            final boolean res = ok;
            final String url = ch.url;
            main.post(() -> { Listener l = listener; if (l != null) l.onProbed(url, res); });
        }
    }

    private static boolean isHttp(String u) {
        return u != null && (u.regionMatches(true, 0, "http://", 0, 7) || u.regionMatches(true, 0, "https://", 0, 8));
    }

    // ---- probe ----

    private static final class Res {
        int code;
        byte[] body;
        String url;
    }

    private static boolean probe(Channel ch) throws IOException {
        Res r = fetch(ch.url, ch.userAgent, ch.referrer);
        if (r == null || r.code < 200 || r.code > 299) return false;
        int v = judge(r.body);
        if (v != 2) return v == 1;
        String variant = firstVariant(new String(r.body, StandardCharsets.UTF_8));
        if (variant == null) return false;
        Res m = fetch(new URL(new URL(r.url), variant).toString(), ch.userAgent, ch.referrer);
        return m != null && m.code >= 200 && m.code <= 299 && mediaPlaylist(new String(m.body, StandardCharsets.UTF_8));
    }

    /** 0 = not a stream, 1 = ok, 2 = master playlist (check a variant). */
    public static int judge(byte[] b) {
        int off = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        byte[] tag = "#EXTM3U".getBytes(StandardCharsets.US_ASCII);
        boolean pl = b.length >= off + tag.length;
        for (int i = 0; pl && i < tag.length; i++) if (b[off + i] != tag[i]) pl = false;
        if (pl) return new String(b, StandardCharsets.UTF_8).contains("#EXT-X-STREAM-INF") ? 2 : 1;
        return b.length > 188 && b[0] == 0x47 && b[188] == 0x47 ? 1 : 0;
    }

    /** First URI line after #EXT-X-STREAM-INF, or null. */
    public static String firstVariant(String master) {
        boolean seen = false;
        for (String line : master.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) seen = true;
            else if (seen && !line.isEmpty() && !line.startsWith("#")) return line;
        }
        return null;
    }

    public static boolean mediaPlaylist(String s) {
        return s.contains("#EXTINF") || s.contains("#EXT-X-TARGETDURATION");
    }

    /** GET with manual redirects (max 3, http and https both allowed), reads at most 64 KB. Null on network failure. */
    private static Res fetch(String url, String ua, String ref) {
        long deadline = System.currentTimeMillis() + 15000;
        try {
            for (int hop = 0; hop <= 3; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                try {
                    c.setConnectTimeout(TIMEOUT);
                    c.setReadTimeout(TIMEOUT);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent", ua == null || ua.isEmpty() ? "Mozilla/5.0 (Linux; Android 12) ManusIPTV" : ua);
                    if (ref != null && !ref.isEmpty()) c.setRequestProperty("Referer", ref);
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        if (loc == null || hop == 3) return null;
                        url = new URL(new URL(url), loc).toString();
                        continue;
                    }
                    Res r = new Res();
                    r.code = code;
                    r.url = url;
                    if (code >= 200 && code < 300) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        try (InputStream in = c.getInputStream()) {
                            int n;
                            while (out.size() < MAX_READ && System.currentTimeMillis() < deadline && (n = in.read(buf, 0, Math.min(buf.length, MAX_READ - out.size()))) > 0) out.write(buf, 0, n);
                        }
                        r.body = out.toByteArray();
                    } else r.body = new byte[0];
                    return r;
                } finally {
                    c.disconnect();
                }
            }
        } catch (IOException e) {
            Log.w(TAG, Redact.host(url) + ": " + e.getClass().getSimpleName());
        }
        return null;
    }
}
