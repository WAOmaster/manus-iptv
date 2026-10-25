package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Channel ordering: health, favourite, on-device plays, then a static score, then name. Keys are precomputed; the comparator allocates nothing. */
public final class Ranker {
    private Ranker() {}

    public interface Signals {
        boolean isFavourite(String url);
        int playCount(String url);
        long lastPlayedMs(String url);
        int health(String url);              // Health.DEAD / UNKNOWN / LIKELY / ALIVE
        Set<String> pinnedCountries();       // iteration order = user's priority
        /** Cloud popularity of this stream in tenths (0..100), -1 = none: then the built-in {@link Popular} table is used. */
        default int popularity(String url) { return -1; }
    }

    private static final class E {
        long k1;      // health, favourite, play count, last played (minutes)
        int score;
        String name;
        Channel ch;
    }

    private static final Comparator<E> CMP = (a, b) -> {
        if (a.k1 != b.k1) return a.k1 > b.k1 ? -1 : 1;
        if (a.score != b.score) return a.score > b.score ? -1 : 1;
        return a.name.compareTo(b.name);
    };

    /** Static score in tenths. Exposed for tests. */
    static int score(Channel c, Map<String, Integer> pinned, int npinned) { return score(c, pinned, npinned, -1); }

    /** pop: cloud popularity in tenths, or -1 to use the Popular table. */
    static int score(Channel c, Map<String, Integer> pinned, int npinned, int pop) {
        int s = 0;
        Integer idx = c.country.isEmpty() ? null : pinned.get(c.country);
        if (idx != null) s += 400 + 5 * Math.max(0, 9 - idx);
        String q = c.quality;
        if (q.equals("1080p") || q.equals("4K") || q.equals("2160p")) s += 60;
        else if (q.equals("720p")) s += 40;
        else if (q.equals("576p") || q.equals("480p")) s += 20;
        if (!c.logo.isEmpty()) s += 30;
        for (String g : c.groups) if (mainstream(g)) { s += 20; break; }
        s += pop >= 0 ? pop : 10 * Popular.of(c.id);
        return s;
    }

    private static boolean mainstream(String g) {
        switch (g.toLowerCase(Locale.ROOT)) {
            case "news": case "sports": case "movies": case "series": case "tv series": case "kids":
            case "music": case "entertainment": return true;
            default: return false;
        }
    }

    /** Stable in-place sort. */
    public static void sort(List<Channel> list, Signals s) {
        int n = list.size();
        Map<String, Integer> pinned = new HashMap<>();
        int i = 0;
        for (String p : s.pinnedCountries()) pinned.put(p, i++);
        E[] es = new E[n];
        for (int j = 0; j < n; j++) {
            Channel c = list.get(j);
            E e = new E();
            long h = Math.max(-1, Math.min(2, s.health(c.url))) + 1;              // 0..3
            long fav = s.isFavourite(c.url) ? 1 : 0;
            long pc = Math.min(Math.max(s.playCount(c.url), 0), 65535);
            long last = Math.min(Math.max(s.lastPlayedMs(c.url), 0) / 60000L, Integer.MAX_VALUE);
            e.k1 = ((h * 2 + fav) << 47) | (pc << 31) | last;
            e.score = score(c, pinned, pinned.size(), s.popularity(c.url));
            e.name = c.name.toLowerCase(Locale.ROOT);
            e.ch = c;
            es[j] = e;
        }
        Arrays.sort(es, CMP); // TimSort: stable
        for (int j = 0; j < n; j++) list.set(j, es[j].ch);
    }

    /** New list without DEAD channels (unless showUnavailable); order unchanged. */
    public static List<Channel> visible(List<Channel> list, Signals s, boolean showUnavailable) {
        if (showUnavailable) return new ArrayList<>(list);
        List<Channel> out = new ArrayList<>(list.size());
        for (Channel c : list) if (s.health(c.url) != Health.DEAD) out.add(c);
        return out;
    }
}
