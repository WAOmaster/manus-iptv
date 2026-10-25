package lk.manus.iptv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Loads channel logos into ImageViews: memory LRU, disk cache, 3-thread network pool. Never throws. */
public final class LogoLoader {
    private static final String TAG = "IPTV-logo";
    private static final int THREADS = 3;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long MAX_DISK_BYTES = 30L * 1024 * 1024;
    private static final int TRIM_EVERY = 50;
    private static final int MAX_W = 320; // 2x the 160x90 target box
    private static final int MAX_H = 180;
    private static final int FADE_MS = 150;
    private static final String USER_AGENT = "Mozilla/5.0 ManusIPTV";

    /** Set by the guide while key-repeat scrolling: logos pop in without the fade. */
    public static volatile boolean quiet;
    /** True when a load was skipped while quiet; the guide rebinds the visible rows when scrolling stops. */
    public static volatile boolean deferred;
    private static LogoLoader instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private final LruCache<String, Bitmap> memory;
    private final Set<String> failed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, List<ImageView>> inFlight = new HashMap<>(); // main thread only
    private final File diskDir;
    private final AtomicInteger writes = new AtomicInteger();
    private final Object trimLock = new Object();

    private LogoLoader(Context ctx) {
        diskDir = new File(ctx.getApplicationContext().getCacheDir(), "logos");
        long size = Runtime.getRuntime().maxMemory() / 16 / (Ui.low() ? 4 : 1); // Low-tier TV: a quarter of the cache
        memory = new LruCache<String, Bitmap>((int) Math.min(size, Integer.MAX_VALUE)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public static synchronized LogoLoader get(Context ctx) {
        if (instance == null) instance = new LogoLoader(ctx);
        return instance;
    }

    /** Main thread only. Shows a LetterTile at once, then swaps in the logo when it arrives. */
    public void load(String url, String name, ImageView iv) {
        try {
            if (iv == null) return;
            iv.setTag(url);
            if (iv.getAlpha() != 1f) { iv.animate().cancel(); iv.setAlpha(1f); } // a fade may still be running on a recycled view
            boolean blank = url == null || url.trim().isEmpty();
            Bitmap cached = blank ? null : memory.get(url);
            if (cached != null) {
                iv.setImageBitmap(cached);
                return;
            }
            iv.setImageDrawable(new LetterTile(name));
            if (blank || failed.contains(url)) return;
            if (quiet) { deferred = true; return; } // fast scrolling: fetch/decode/upload later, once the list calms down

            List<ImageView> waiting = inFlight.get(url);
            if (waiting != null) {
                waiting.add(iv);
                return;
            }
            pool.execute(() -> fetchAndDecode(url));
            waiting = new ArrayList<>();
            waiting.add(iv);
            inFlight.put(url, waiting); // after execute, so a rejected task leaves no stuck entry
        } catch (Throwable t) {
            Log.w(TAG, "load failed for " + lk.manus.iptv.data.Redact.host(url) + " " + t.getClass().getSimpleName());
        }
    }

    public void clearMemory() {
        memory.evictAll();
    }

    private void fetchAndDecode(String url) {
        Bitmap bmp = null;
        try {
            File f = new File(diskDir, sha1(url) + ".png");
            byte[] bytes = Files.exists(f.toPath()) ? Files.readAllBytes(f.toPath()) : null;
            if (bytes != null) {
                bmp = decode(bytes);
                if (bmp != null) f.setLastModified(System.currentTimeMillis()); // keeps trim LRU-like
                else f.delete(); // corrupt cache entry
            }
            if (bmp == null) {
                bytes = download(url);
                if (bytes != null) {
                    bmp = decode(bytes);
                    if (bmp == null) failed.add(url); // SVG or undecodable: never retry
                    else writeDisk(f, bytes);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "logo fetch failed: " + lk.manus.iptv.data.Redact.host(url) + " " + t.getClass().getSimpleName());
        }
        if (bmp != null) memory.put(url, bmp);
        deliver(url, bmp);
    }

    private void deliver(String url, Bitmap bmp) {
        main.post(() -> {
            try {
                List<ImageView> views = inFlight.remove(url);
                if (views == null || bmp == null) return;
                for (ImageView iv : views) {
                    if (!url.equals(iv.getTag())) continue; // recycled view now shows another url
                    if (quiet) { iv.setImageBitmap(bmp); continue; } // no fade while the list is scrolling fast
                    iv.setAlpha(0f);
                    iv.setImageBitmap(bmp);
                    iv.animate().alpha(1f).setDuration(FADE_MS).start();
                }
            } catch (Throwable t) {
                Log.w(TAG, "deliver failed for " + lk.manus.iptv.data.Redact.host(url) + " " + t.getClass().getSimpleName());
            }
        });
    }

    /** Returns raw bytes, or null on any network problem, bad status, non-http(s) scheme or oversize body. */
    private static byte[] download(String url) throws IOException {
        URL u = new URL(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String proto = u.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) return null;
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setInstanceFollowRedirects(false); // manual, so http->https also works
                c.setRequestProperty("User-Agent", USER_AGENT);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    u = new URL(u, loc);
                    continue;
                }
                if (code != 200 || c.getContentLengthLong() > MAX_BYTES) return null;
                return readCapped(c.getInputStream());
            } finally {
                c.disconnect();
            }
        }
        return null;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Decodes downsampled to at most 320x180. Returns null if not a bitmap (e.g. SVG). */
    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while ((bounds.outWidth + sample - 1) / sample > MAX_W
                || (bounds.outHeight + sample - 1) / sample > MAX_H) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private void writeDisk(File f, byte[] bytes) {
        try {
            diskDir.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(bytes);
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
                return;
            }
            if (writes.incrementAndGet() % TRIM_EVERY == 0) trimDisk();
        } catch (Throwable t) {
            Log.w(TAG, "disk write failed", t);
        }
    }

    /** Deletes oldest files until the cache is within 30 MB. */
    private void trimDisk() {
        synchronized (trimLock) {
            File[] files = diskDir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= MAX_DISK_BYTES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File f : files) {
                if (total <= MAX_DISK_BYTES) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        }
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
