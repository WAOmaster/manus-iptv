package lk.manus.iptv.data;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import lk.manus.iptv.Config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The user's playlist links. Each source = JSON entry in SharedPreferences + cached file filesDir/sources/id.m3u refreshed with ETag.
 * Links may embed logins: they live only in private app storage and are never logged (see {@link Redact}).
 * The merged list = all enabled sources, de-duplicated by stream url, built-in iptv-org first.
 */
public final class Sources {
    public static final String DEFAULT_ID = "default";
    // Compile-time constants: in the Play flavour these fold to "" so no list address ships in the app.
    public static final String DEFAULT_URL = Config.PLAY ? "" : "https://iptv-org.github.io/iptv/index.m3u";
    public static final String FREE_NAME = Config.PLAY ? "" : "iptv-org";
    /** Suggested in Add source (never added automatically). Official free broadcasters, mostly Europe. */
    public static final String FREE_TV_URL = Config.PLAY ? "" : "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8";
    private static final String TAG = "IPTV";
    private static final long MAX_BYTES = 60L * 1024 * 1024;

    public static final class Source {
        public final String id, url;
        public String name;
        public boolean enabled = true;
        public int count = -1;     // channels at last successful load, -1 = never
        public long ts;            // last successful refresh (ms), 0 = never
        Source(String id, String name, String url) { this.id = id; this.name = name; this.url = url; }
        public boolean builtin() { return id.equals(DEFAULT_ID); }
        Source copy() { Source s = new Source(id, name, url); s.enabled = enabled; s.count = count; s.ts = ts; return s; }
    }

    /** What the guide is built from: merged list plus each enabled source's own full list (for its original folders). */
    public static final class Loaded {
        public final List<Channel> merged;
        public final List<Source> sources;
        public final Map<String, List<Channel>> bySrc;
        public Loaded(List<Channel> m, List<Source> s, Map<String, List<Channel>> b) { merged = m; sources = s; bySrc = b; }
    }

    public interface Callback {
        void onLoaded(Loaded l);
        void onError();
    }

    /** Result of checking a link before saving it. */
    public static final class Probe {
        public final String url;
        public int count;
        public String error;   // plain English, null = ok
        File tmp;
        Probe(String url) { this.url = url; }
    }

    private static final class HttpError extends IOException { final int code; HttpError(int c) { super("HTTP " + c); code = c; } }
    private static final class Resp { int code; String etag; }

    private final SharedPreferences prefs;
    private final File dir;
    private final String defaultUrl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Source> list = new ArrayList<>();
    private int gen;

    public Sources(SharedPreferences prefs, File filesDir, String defaultOverride) {
        this.prefs = prefs;
        this.dir = new File(filesDir, "sources");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.getName().endsWith(".tmp")) f.delete();
        defaultUrl = defaultOverride == null ? DEFAULT_URL : defaultOverride;
        File legacy = new File(filesDir, "playlist.m3u"), legacyTag = new File(filesDir, "playlist.etag");
        if (legacy.exists() && !file(DEFAULT_ID).exists()) {
            legacy.renameTo(file(DEFAULT_ID));
            legacyTag.renameTo(etag(DEFAULT_ID));
        }
        legacyTag.delete();
        read();
    }

    private File file(String id) { return new File(dir, id + ".m3u"); }
    private File etag(String id) { return new File(dir, id + ".etag"); }

    // ---- list management ----

    private void read() {
        list.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("sources", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Source s = new Source(o.getString("id"), o.getString("name"), o.getString("url"));
                s.enabled = o.optBoolean("enabled", true);
                s.count = o.optInt("count", -1);
                s.ts = o.optLong("ts", 0);
                list.add(s);
            }
        } catch (Exception e) {
            Log.w(TAG, "sources unreadable: " + e.getClass().getSimpleName());
        }
        // The free list is pre-enabled only when the build says so, or when this install already had it cached; otherwise the user adds sources.
        if (!Config.PLAY && (list.isEmpty() || !list.get(0).builtin()) && (Config.IPTVORG || file(DEFAULT_ID).exists()))
            list.add(0, new Source(DEFAULT_ID, FREE_NAME, DEFAULT_URL));
    }

    /** Re-reads the saved list (another Sources instance, e.g. the setup wizard, may have changed it). */
    public synchronized void reread() { read(); }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Source s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.id).put("name", s.name).put("url", s.url).put("enabled", s.enabled).put("count", s.count).put("ts", s.ts);
                a.put(o);
            }
            prefs.edit().putString("sources", a.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "sources not saved: " + e.getClass().getSimpleName());
        }
    }

    public synchronized List<Source> list() {
        List<Source> r = new ArrayList<>();
        for (Source s : list) r.add(s.copy());
        return r;
    }

    public synchronized Source get(String id) {
        for (Source s : list) if (s.id.equals(id)) return s.copy();
        return null;
    }

    public synchronized int enabledCount() {
        int n = 0;
        for (Source s : list) if (s.enabled) n++;
        return n;
    }

    public synchronized void setEnabled(String id, boolean on) {
        for (Source s : list) if (s.id.equals(id)) s.enabled = on;
        save();
    }

    public synchronized void rename(String id, String name) {
        if (name.trim().isEmpty()) return;
        for (Source s : list) if (s.id.equals(id)) s.name = name.trim();
        save();
    }

    /** The built-in source can't be removed when the build pre-enables it; a user-added free list can. */
    public synchronized void remove(String id) {
        if (id.equals(DEFAULT_ID) && Config.IPTVORG) return;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) list.remove(i--);
        file(id).delete();
        etag(id).delete();
        save();
    }

    /** Saves a link that already passed {@link #probe}; its downloaded copy becomes the cache. */
    public synchronized Source add(String name, Probe p) {
        boolean free = !Config.PLAY && p.url.equals(DEFAULT_URL) && get(DEFAULT_ID) == null; // the free list keeps its own id so it is read as iptv-org, not normalised
        String id = free ? DEFAULT_ID : String.format("%08x", new Random().nextInt());
        String n = name == null || name.trim().isEmpty() ? (free ? FREE_NAME : Redact.host(p.url)) : name.trim();
        Source s = new Source(id, n, p.url);
        s.count = p.count;
        s.ts = System.currentTimeMillis();
        try {
            Files.move(p.tmp.toPath(), file(id).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.w(TAG, "cache move failed: " + e.getClass().getSimpleName());
        }
        if (free) list.add(0, s); else list.add(s);
        save();
        return s;
    }

    public void discard(Probe p) { if (p != null && p.tmp != null) p.tmp.delete(); }

    private String urlOf(Source s) { return s.builtin() ? defaultUrl : s.url; }

    // ---- loading ----

    /** Cached copies first (fast), then conditional refresh of each enabled source; delivers on the main thread, again if anything changed. */
    public void load(final Callback cb) {
        final int g;
        synchronized (this) { g = ++gen; }
        new Thread(new Runnable() {
            @Override public void run() {
                List<Source> snap = new ArrayList<>();
                synchronized (Sources.this) { for (Source s : list) if (s.enabled) snap.add(s.copy()); }
                if (snap.isEmpty()) { deliver(cb, g, snap, new LinkedHashMap<String, List<Channel>>()); return; } // no sources yet: an empty list, not an error
                Map<String, List<Channel>> by = new LinkedHashMap<>();
                Set<String> cached = new HashSet<>();
                for (Source s : snap) {
                    List<Channel> l = parse(s);
                    if (l != null && !l.isEmpty()) { by.put(s.id, l); cached.add(s.id); }
                }
                boolean any = !by.isEmpty();
                if (any) deliver(cb, g, snap, by);
                boolean changed = false;
                for (Source s : snap) {
                    try {
                        Resp r = refresh(s, cached.contains(s.id));
                        if (r.code == 200) {
                            List<Channel> l = parse(s);
                            if (l != null && !l.isEmpty()) { by.put(s.id, l); changed = true; }
                        }
                        stamp(s.id, by.containsKey(s.id) ? by.get(s.id).size() : -1);
                    } catch (Exception e) {
                        Log.w(TAG, "source " + s.name + " (" + Redact.host(urlOf(s)) + ") refresh failed: " + e.getClass().getSimpleName());
                    }
                }
                if (changed || (!any && !by.isEmpty())) { any = true; deliver(cb, g, snap, by); }
                if (!any) main.post(new Runnable() { @Override public void run() { if (g == gen) cb.onError(); } });
            }
        }, "sources").start();
    }

    private synchronized void stamp(String id, int count) {
        for (Source s : list) if (s.id.equals(id)) { if (count >= 0) { s.count = count; s.ts = System.currentTimeMillis(); } }
        save();
    }

    private void deliver(final Callback cb, final int g, List<Source> snap, Map<String, List<Channel>> by) {
        final List<Source> ordered = new ArrayList<>();
        final Map<String, List<Channel>> copy = new LinkedHashMap<>();
        final List<Channel> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Source s : snap) {
            List<Channel> l = by.get(s.id);
            if (l == null) continue;
            ordered.add(s);
            copy.put(s.id, l);
            for (Channel c : l) if (seen.add(c.url)) merged.add(c);
        }
        final Loaded loaded = new Loaded(merged, ordered, copy);
        main.post(new Runnable() { @Override public void run() { if (g == gen) cb.onLoaded(loaded); } });
    }

    private List<Channel> parse(Source s) {
        File f = file(s.id);
        if (!f.exists()) return null;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            List<Channel> raw = M3uParser.parse(r);
            List<Channel> out = new ArrayList<>(raw.size());
            for (Channel c : raw) out.add(s.builtin() ? c.withSrc(s.id) : Normalizer.apply(c, s.id));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private Resp refresh(Source s, boolean haveCache) throws IOException {
        File tmp = new File(dir, s.id + ".m3u.tmp");
        String tag = null;
        if (haveCache && etag(s.id).exists()) tag = new String(Files.readAllBytes(etag(s.id).toPath()), StandardCharsets.UTF_8).trim();
        Resp r = download(urlOf(s), tmp, tag == null || tag.isEmpty() ? null : tag);
        if (r.code == 200) {
            Files.move(tmp.toPath(), file(s.id).toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (r.etag != null) Files.write(etag(s.id).toPath(), r.etag.getBytes(StandardCharsets.UTF_8)); else etag(s.id).delete();
        }
        return r;
    }

    // ---- checking a new link ----

    /** Blocking: downloads (15 s timeouts, redirects, max 60 MB), parses, counts. Call off the main thread. */
    public Probe probe(String url) {
        Probe p = new Probe(url);
        String u = url == null ? "" : url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://")) || u.length() > 2000) {
            p.error = "That doesn't look like a web link. It should start with http:// or https://";
            return p;
        }
        File tmp = new File(dir, "probe" + System.nanoTime() + ".tmp");
        try {
            download(u, tmp, null);
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(tmp), StandardCharsets.UTF_8)) {
                p.count = M3uParser.parse(r).size();
            }
            if (p.count == 0) {
                boolean looksM3u = false;
                try (InputStream in = new FileInputStream(tmp)) {
                    byte[] b = new byte[4096];
                    String head = new String(b, 0, Math.max(0, in.read(b)), StandardCharsets.ISO_8859_1);
                    looksM3u = head.contains("#EXT");
                }
                p.error = looksM3u ? "The playlist is empty: 0 channels found." : "That link isn't an IPTV playlist (M3U).";
            } else {
                p.tmp = tmp;
                return p;
            }
        } catch (HttpError e) {
            p.error = e.code == 404 ? "Nothing there (HTTP 404). Check the link." : "The server refused the request (HTTP " + e.code + ").";
        } catch (TooBig e) {
            p.error = "That file is too big (over 60 MB).";
        } catch (Exception e) {
            Log.w(TAG, "probe failed (" + Redact.host(u) + "): " + e.getClass().getSimpleName());
            p.error = "Can't reach that link. Check the address and the TV's internet.";
        }
        tmp.delete();
        return p;
    }

    private static final class TooBig extends IOException { }

    /** GET with manual redirects (http and https only). Returns 304 only when an ETag was sent. Body goes to {@code out} on 200. */
    private static Resp download(String url, File out, String ifNoneMatch) throws IOException {
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            URL parsed = new URL(u);
            String proto = parsed.getProtocol();
            if (!proto.equals("http") && !proto.equals("https")) throw new IOException("scheme");
            HttpURLConnection c = (HttpURLConnection) parsed.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "ManusIPTV/1.0");
                if (ifNoneMatch != null) c.setRequestProperty("If-None-Match", ifNoneMatch);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without target");
                    u = new URL(parsed, loc).toString();
                    continue;
                }
                Resp r = new Resp();
                r.code = code;
                if (code == 304) return r;
                if (code != 200) throw new HttpError(code);
                r.etag = c.getHeaderField("ETag");
                long total = 0;
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) { out.delete(); throw new TooBig(); }
                        os.write(buf, 0, n);
                    }
                }
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }
}
