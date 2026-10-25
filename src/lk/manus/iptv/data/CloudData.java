package lk.manus.iptv.data;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import lk.manus.iptv.Config;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * The cloud job's feed (channels.json.gz + epg.json.gz on GitHub Pages): a status / popularity HINT per stream and now/next per tvg-id.
 * Downloaded off the UI thread, cached in filesDir; with no data every lookup just says "unknown" and the app works as before.
 */
public final class CloudData {
    private static final String[] FILES = {"channels.json.gz", "epg.json.gz"};
    private static final long EVERY = 6 * 3600000L;
    private static final int MAX_BYTES = 8 << 20;

    /** One programme with its position in the day; times are epoch seconds. */
    public static final class Slot {
        public final String title;
        public final long start, stop;
        public Slot(String t, long a, long b) { title = t; start = a; stop = b; }
        public float progress(long nowMs) { return stop <= start ? 0 : Math.max(0f, Math.min(1f, (nowMs / 1000f - start) / (stop - start))); }
    }

    /** Programmes of one channel, sorted by start: starts[i], stops[i], titles[i]. */
    public static final class Prog {
        final long[] starts, stops;
        final String[] titles;
        public Prog(long[] a, long[] b, String[] t) { starts = a; stops = b; titles = t; }

        /** Index of the programme airing at nowSec, or -1. */
        public int at(long nowSec) {
            int i = Arrays.binarySearch(starts, nowSec);
            if (i < 0) i = -i - 2;
            return i >= 0 && nowSec < stops[i] ? i : -1;
        }
    }

    private static final class Snap {
        Map<String, Integer> ch = new HashMap<>();   // stream url -> status char | popularity*10 << 8
        Map<String, Prog> epg = new HashMap<>();     // exact tvg-id (channel@feed) -> programmes; a bare base id = that channel's main feed
        Map<String, String[]> cc = new HashMap<>();  // stream url -> broadcast countries when they differ from the tvg-id suffix (T35)
        Map<String, String> lg = new HashMap<>();    // stream url -> first feed language (iso 639-3)
        int alive;
        long ts;                                     // when the channels file was fetched / confirmed current
    }

    private static volatile Snap snap;
    private static CloudData inst;
    private final File dir;
    private final android.content.SharedPreferences prefs;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "cloud"); t.setDaemon(true); return t; });
    private Runnable onUpdate;
    private boolean started;

    /** The feed base URL ending in "/", or "" = no feed. A saved choice (even "" = cleared) beats the build default. */
    private String base() { return normalize(prefs.contains("feed") ? prefs.getString("feed", "") : Config.FEED_URL); }

    static String normalize(String u) {
        String t = u == null ? "" : u.trim();
        if (t.isEmpty()) return "";
        if (t.endsWith("meta.json")) t = t.substring(0, t.length() - 9);
        return t.endsWith("/") ? t : t + "/";
    }

    /** True when a feed is set (the app otherwise runs without cloud data). */
    public boolean hasFeed() { return !base().isEmpty(); }

    /** The feed's host for display, "" when none. */
    public String feedHost() { String b = base(); try { return b.isEmpty() ? "" : new URL(b).getHost(); } catch (IOException e) { return b; } }

    /** Validates the address by fetching its meta.json (must be a JSON object), then switches to it and downloads. done gets null = ok, else a plain-English problem. */
    public void setFeed(final String url, final java.util.function.Consumer<String> done) {
        ex.execute(() -> {
            String b = normalize(url);
            String err = b.startsWith("http://") || b.startsWith("https://") ? check(b) : "That doesn't look like a web link. It should start with https://";
            if (err == null) { switchTo(b); refresh(true); }
            done.accept(err);
        });
    }

    /** Back to no feed: cached feed files are deleted. */
    public void clearFeed(final Runnable done) { ex.execute(() -> { switchTo(""); done.run(); }); }

    private void switchTo(String b) {
        prefs.edit().putString("feed", b).remove("etag_" + FILES[0]).remove("etag_" + FILES[1]).apply();
        for (String f : FILES) new File(dir, f).delete();
        snap = null;
        Runnable r = onUpdate;
        if (r != null) r.run();
    }

    private static String check(String b) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(b + "meta.json").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code != 200) return code == 404 ? "No meta.json found at that address (HTTP 404). Check the link." : "The server refused the request (HTTP " + code + ").";
            byte[] buf = new byte[4096];
            int n;
            try (InputStream in = c.getInputStream()) { n = Math.max(0, in.read(buf)); }
            return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim().startsWith("{") ? null : "That address doesn't look like a channel data feed.";
        } catch (Exception e) {
            return "Can't reach that address. Check the link and the internet connection.";
        } finally { if (c != null) c.disconnect(); }
    }

    private CloudData(Context c) {
        dir = c.getFilesDir();
        prefs = c.getSharedPreferences("cloud", Context.MODE_PRIVATE);
    }

    public static synchronized CloudData get(Context c) {
        if (inst == null) inst = new CloudData(c.getApplicationContext());
        return inst;
    }

    /** Loads the cached copy, refreshes if older than 6 h, then every 6 h. onUpdate runs on the worker thread after each new snapshot. */
    public synchronized void start(Runnable onUpdate) {
        this.onUpdate = onUpdate;
        if (started) return;
        started = true;
        cloudStarted = true;
        ex.execute(() -> { try { reload(); } finally { FIRST.countDown(); } });
        ex.scheduleWithFixedDelay(this::refresh, 360, 360, TimeUnit.MINUTES);
        ex.execute(this::refresh);
    }

    private void refresh() { refresh(false); }

    /** force = ignore the 6 h age and the ETag (Settings > About > Refresh now). Returns false if a download failed. */
    private boolean refresh(boolean force) {
        String b = base();
        if (b.isEmpty()) return true;
        boolean changed = false, ok = true;
        for (String f : FILES) {
            File t = new File(dir, f);
            if (!force && t.exists() && System.currentTimeMillis() - t.lastModified() < EVERY) continue;
            try { changed |= download(b, f, t, force); } catch (Exception e) { ok = false; log("download " + f + " failed: " + e.getClass().getSimpleName()); }
        }
        if (changed || force) reload();
        return ok;
    }

    /** Re-downloads both files now and re-parses them; {@code done} runs on the worker thread with a one-line result. */
    public void refreshNow(final java.util.function.Consumer<String> done) {
        ex.execute(() -> {
            if (!hasFeed()) { done.accept("No channel data feed set"); return; }
            boolean ok = refresh(true);
            done.accept(ok ? "Channel data refreshed: " + aliveCount() + " alive, guide for " + epgChannels() + " channels" : "Couldn't reach the data server");
        });
    }

    /** True if new bytes were stored; a 304 only refreshes the file's age. */
    private boolean download(String base, String name, File dst, boolean force) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + name).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            String et = !force && dst.exists() ? prefs.getString("etag_" + name, null) : null;
            if (et != null) c.setRequestProperty("If-None-Match", et);
            int code = c.getResponseCode();
            if (code == 304) { dst.setLastModified(System.currentTimeMillis()); log(name + " not modified"); return false; }
            if (code != 200) throw new IOException("HTTP " + code);
            File tmp = new File(dir, name + ".tmp");
            long n = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[16384];
                int r;
                while ((r = in.read(b)) > 0) {
                    n += r;
                    if (n > MAX_BYTES) throw new IOException("too big");
                    out.write(b, 0, r);
                }
            } catch (IOException e) { tmp.delete(); throw e; }
            if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new IOException("rename"); }
            prefs.edit().putString("etag_" + name, c.getHeaderField("ETag")).apply();
            log("downloaded " + name + " " + n / 1024 + " KB");
            return true;
        } finally { c.disconnect(); }
    }

    private void reload() {
        File fc = new File(dir, FILES[0]), fe = new File(dir, FILES[1]);
        if (!fc.exists() && !fe.exists()) return;
        long t0 = System.nanoTime();
        Snap s = new Snap();
        try { if (fc.exists()) { parseChannels(fc, s); s.ts = fc.lastModified(); } } catch (Exception e) { log("parse channels failed: " + e); s.ch.clear(); }
        try { if (fe.exists()) parseEpg(fe, s); } catch (Exception e) { log("parse epg failed: " + e); s.epg.clear(); }
        log("parsed in " + (System.nanoTime() - t0) / 1000000 + " ms: " + s.ch.size() + " streams, " + s.alive + " alive, guide for " + s.epg.size() + " channels");
        snap = s;
        Runnable r = onUpdate;
        if (r != null) r.run();
    }

    private static JsonReader reader(File f) throws IOException {
        return new JsonReader(new InputStreamReader(new GZIPInputStream(new BufferedInputStream(new FileInputStream(f), 16384)), "UTF-8"));
    }

    private static void parseChannels(File f, Snap s) throws IOException {
        try (JsonReader r = reader(f)) {
            r.beginArray();
            while (r.hasNext()) {
                String u = null, st = "", lg = null;
                String[] cc = null;
                double p = 0;
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if (k.equals("u") && r.peek() == JsonToken.STRING) u = r.nextString();
                    else if (k.equals("s") && r.peek() == JsonToken.STRING) st = r.nextString();
                    else if (k.equals("p") && r.peek() == JsonToken.NUMBER) p = r.nextDouble();
                    else if (k.equals("lg") && r.peek() == JsonToken.STRING) lg = r.nextString().intern();
                    else if (k.equals("cc") && r.peek() == JsonToken.BEGIN_ARRAY) {
                        List<String> l = new ArrayList<>(3);
                        r.beginArray();
                        while (r.hasNext()) { if (r.peek() == JsonToken.STRING) l.add(r.nextString().intern()); else r.skipValue(); }
                        r.endArray();
                        if (!l.isEmpty()) cc = l.toArray(new String[0]);
                    }
                    else r.skipValue();
                }
                r.endObject();
                if (u == null || st.isEmpty()) continue;
                int pop = (int) Math.round(Math.max(0, Math.min(10, p)) * 10);
                s.ch.put(u, (pop << 8) | st.charAt(0));
                if (cc != null) s.cc.put(u, cc);
                if (lg != null) s.lg.put(u, lg);
                if (st.charAt(0) == 'a') s.alive++;
            }
            r.endArray();
        }
    }

    private static void parseEpg(File f, Snap s) throws IOException {
        try (JsonReader r = reader(f)) {
            r.beginObject();
            while (r.hasNext()) {
                String id = r.nextName();
                long[] a = new long[16], b = new long[16];
                String[] t = new String[16];
                int n = 0;
                r.beginArray();
                while (r.hasNext()) {
                    r.beginArray();
                    long st = r.nextLong(), en = r.nextLong();
                    String ti = r.nextString();
                    while (r.hasNext()) r.skipValue();
                    r.endArray();
                    if (n == a.length) { a = Arrays.copyOf(a, n * 2); b = Arrays.copyOf(b, n * 2); t = Arrays.copyOf(t, n * 2); }
                    a[n] = st; b[n] = en; t[n++] = ti;
                }
                r.endArray();
                if (n > 0) s.epg.put(id, new Prog(Arrays.copyOf(a, n), Arrays.copyOf(b, n), Arrays.copyOf(t, n)));
            }
            r.endObject();
        }
    }

    // ---- lookups (any thread) ----

    /** Cloud status char for a stream ('a','d','g','t'), 0 if unknown. */
    public static char status(String url) {
        Snap s = snap;
        Integer v = s == null ? null : s.ch.get(url);
        return v == null ? 0 : (char) (v & 0xFF);
    }

    /** Popularity in tenths (0..100), or -1 when the cloud has no record. */
    public static int popularity(String url) {
        Snap s = snap;
        Integer v = s == null ? null : s.ch.get(url);
        return v == null ? -1 : v >> 8;
    }

    /** Programme airing now for a tvg-id, or null. */
    public static Slot now(String tvgId, long nowMs) { return slot(tvgId, nowMs, 0); }

    /** The programme after the current one (or the first one still to come), or null. */
    public static Slot next(String tvgId, long nowMs) { return slot(tvgId, nowMs, 1); }

    /**
     * Exact id only: "channel@feed" is that feed's own guide and never another region's (T34). An id without a feed
     * ("BBCOne.uk", other sources) finds the base-id alias the job publishes for the channel's main feed.
     */
    private static Slot slot(String id, long nowMs, int off) {
        Snap s = snap;
        if (s == null || id == null || id.isEmpty()) return null;
        Prog p = s.epg.get(id);
        return p == null ? null : at(p, nowMs / 1000, off);
    }

    /** Countries the stream's feed is really broadcast to (first = main), or null when the id suffix is right / unknown. */
    public static String[] areas(String url) { Snap s = snap; return s == null ? null : s.cc.get(url); }

    /** First language of the stream's feed (iso 639-3), or null when unknown. */
    public static String lang(String url) { Snap s = snap; return s == null ? null : s.lg.get(url); }

    private static final java.util.concurrent.CountDownLatch FIRST = new java.util.concurrent.CountDownLatch(1);
    private static volatile boolean cloudStarted;

    /** Lets the index build wait (bounded) for the cached snapshot so countries are right the first time; no-op if the cloud was never started. */
    public static void awaitReady(long ms) {
        if (!cloudStarted) return;
        try { FIRST.await(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static Slot at(Prog p, long nowSec, int off) {
        int i = p.at(nowSec);
        if (off == 0) return i < 0 ? null : new Slot(p.titles[i], p.starts[i], p.stops[i]);
        if (i < 0) { i = Arrays.binarySearch(p.starts, nowSec); i = i < 0 ? -i - 1 : i; } else i++;
        return i < p.starts.length ? new Slot(p.titles[i], p.starts[i], p.stops[i]) : null;
    }

    public static int epgChannels() { Snap s = snap; return s == null ? 0 : s.epg.size(); }
    public static int aliveCount() { Snap s = snap; return s == null ? 0 : s.alive; }
    /** When the channel data was last confirmed current (ms), 0 = none. */
    public static long updatedAt() { Snap s = snap; return s == null ? 0 : s.ts; }

    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", java.util.Locale.US); // UI thread only; default zone = device (Asia/Colombo)

    public static String hm(long epochSec) { return HM.format(new Date(epochSec * 1000)); }

    private static void log(String m) {
        try { android.util.Log.i("IPTV", "CloudData " + m); } catch (Throwable ignored) { }
    }
}
