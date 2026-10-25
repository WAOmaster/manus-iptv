package lk.manus.iptv.player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HLS master-playlist helper: fetch, parse variants, pick one. Pure Java. */
public final class Hls {
    private Hls() {}

    public static final class Variant {
        public final int bandwidth, width, height;
        public final String url;
        Variant(int b, int w, int h, String u) { bandwidth = b; width = w; height = h; url = u; }
        public String label() { return bandwidth / 1000 + "kbps " + (width > 0 ? width + "x" + height : "?"); }
    }

    /** Body of a fetched playlist plus the URL it ended up at (after redirects), the base for relative links. */
    public static final class Fetched {
        public final String body, url;
        Fetched(String b, String u) { body = b; url = u; }
    }

    private static final Pattern BW = Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
    private static final Pattern RES = Pattern.compile("(?:^|,)RESOLUTION=(\\d+)x(\\d+)");
    private static final int MAX_BYTES = 512 * 1024, TIMEOUT_MS = 8000;

    /** Variants sorted by bandwidth ascending; empty when the text is not a master playlist. */
    public static List<Variant> parseMaster(String text, String baseUrl) {
        List<Variant> out = new ArrayList<>();
        if (text == null || !text.contains("#EXT-X-STREAM-INF")) return out;
        String attrs = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) { attrs = line.substring(18); continue; }
            if (attrs == null || line.isEmpty()) continue;
            if (line.startsWith("#")) continue; // other tags between the tag and its URI
            Matcher b = BW.matcher(attrs), r = RES.matcher(attrs);
            int bw = b.find() ? parse(b.group(1)) : 0;
            int w = 0, h = 0;
            if (r.find()) { w = parse(r.group(1)); h = parse(r.group(2)); }
            String u = resolve(baseUrl, line);
            if (u != null) out.add(new Variant(bw, w, h, u));
            attrs = null;
        }
        Collections.sort(out, new Comparator<Variant>() {
            @Override public int compare(Variant a, Variant c) { return Integer.compare(a.bandwidth, c.bandwidth); }
        });
        return out;
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }

    /** Absolute or relative reference against a base; null if unusable. */
    public static String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref.trim()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** "best" = highest, "saver" = lowest, anything else (auto) = null: let the player adapt on the master. */
    public static Variant pick(List<Variant> v, String quality) {
        if (v.isEmpty()) return null;
        if ("best".equals(quality)) return v.get(v.size() - 1);
        if ("saver".equals(quality)) return v.get(0);
        return null;
    }

    /**
     * Fetch a playlist: 8 s timeout, at most 512 KB. Returns null when the body does not start with #EXTM3U,
     * after reading only a few bytes, so a plain video stream is never downloaded and a second login is not held open.
     */
    public static Fetched fetch(String url, String ua, String referer) throws IOException {
        if (url == null || !url.regionMatches(true, 0, "http", 0, 4)) return null;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (ua != null && !ua.isEmpty()) c.setRequestProperty("User-Agent", ua);
            if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
            try (InputStream in = c.getInputStream()) {
                byte[] head = new byte[7];
                int n = 0, r;
                while (n < 7 && (r = in.read(head, n, 7 - n)) > 0) n += r;
                if (n < 7 || !new String(head, "ISO-8859-1").equals("#EXTM3U")) return null;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bo.write(head, 0, 7);
                byte[] buf = new byte[8192];
                while (bo.size() < MAX_BYTES && (r = in.read(buf)) > 0) bo.write(buf, 0, r);
                return new Fetched(bo.toString("UTF-8"), c.getURL().toString());
            }
        } finally {
            c.disconnect();
        }
    }
}
