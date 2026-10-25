package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Section index built once off the UI thread. Section ids: fav, recent, foryou, lk, cat:Label, cc:xx, countries, search, settings,
 * src:id (a source's folders) and sf:id TAB folder (one folder's channels).
 */
public final class Index {
    /** The one place the iptv-org categories are merged: merged label -> raw labels (display order = row order). */
    private static final String[][] MERGE = {
        {"News", "News"}, {"Sports", "Sports"}, {"Movies", "Movies"}, {"TV Series", "Series", "TV Series"},
        {"Entertainment", "Entertainment", "General", "Comedy", "Culture", "Lifestyle", "Travel", "Cooking", "Family", "Classic", "Outdoor", "Auto", "Relax"},
        {"Kids", "Kids", "Animation"}, {"Music", "Music"}, {"Documentary", "Documentary", "Science", "Education", "Weather"},
        {"Religious", "Religious"}, {"Business", "Business", "Shop", "Legislative", "Public"}};
    private static final Map<String, String> RAW2MERGED = new HashMap<>();
    static {
        for (String[] m : MERGE) for (int i = 1; i < m.length; i++) RAW2MERGED.put(m[i].toLowerCase(Locale.ROOT), m[0]);
    }

    /** Raw group label -> merged category; anything unknown is "Other". */
    public static String merged(String raw) {
        String m = RAW2MERGED.get(raw.toLowerCase(Locale.ROOT));
        return m == null ? "Other" : m;
    }

    /** Where a stream's feed is really watched and in which language (T35/T36): the cloud job's data, or a test double. */
    public interface Hints {
        String[] areas(String url);
        String lang(String url);
    }

    public static final Hints CLOUD = new Hints() {
        @Override public String[] areas(String url) { return CloudData.areas(url); }
        @Override public String lang(String url) { return CloudData.lang(url); }
    };

    /**
     * Sets country (first broadcast country of the feed), areas and language on every channel: "AMC Latin America Brazil" is
     * Brazil, not the US its tvg-id suffix says. No hint = the channel is left as parsed. Idempotent; run before any list is built.
     */
    public static void relabel(Iterable<? extends Iterable<Channel>> lists, Hints h) {
        for (Iterable<Channel> l : lists) for (Channel c : l) c.relabel(h.areas(c.url), h.lang(c.url));
    }

    /** For You: pinned countries' channels in the chosen categories (old stored raw labels are merged on the fly). */
    public static List<Channel> forYou(List<Channel> all, Store st) {
        CloudData.awaitReady(2500);
        relabel(Collections.singletonList(all), CLOUD);
        Set<String> cats = new HashSet<>();
        for (String c : st.fyCats()) cats.add(merged(c));
        return Curated.of(all, st.fyCountries(), cats);
    }

    public volatile List<Channel> forYou;
    public final List<Channel> all = new ArrayList<>();          // merged, de-duplicated, name order
    public String[] folded;                                      // Search.fold(name) per entry of all
    public final List<Sources.Source> sources = new ArrayList<>(); // enabled, built-in first
    private final Map<String, LinkedHashMap<String, List<Channel>>> folders = new HashMap<>();
    private final Map<String, List<String>> folderOrder = new HashMap<>();
    public final Map<String, List<Channel>> cats = new HashMap<>();
    public final Map<String, List<Channel>> countries = new HashMap<>();
    public final Map<String, Channel> byUrl = new HashMap<>();
    public final List<String> catOrder = new ArrayList<>();      // labels, display order
    public final List<String> countryOrder = new ArrayList<>();  // codes, count desc, lk first

    private Index(List<Channel> forYou) { this.forYou = forYou; }

    public static String groupLabel(String g) {
        if (g.isEmpty() || g.equalsIgnoreCase("Undefined")) return "Other";
        return g.equals("Series") ? "TV Series" : g;
    }

    private static final class K { String k; Channel c; }

    private static List<K> sorted(List<Channel> in, Set<String> seen) {
        List<K> ks = new ArrayList<>(in.size());
        for (Channel c : in) {
            if (seen != null && !seen.add(c.url)) continue;
            K k = new K();
            k.k = c.name.toLowerCase(Locale.ROOT);
            k.c = c;
            ks.add(k);
        }
        Collections.sort(ks, new Comparator<K>() { @Override public int compare(K a, K b) { return a.k.compareTo(b.k); } });
        return ks;
    }

    public static Index build(Sources.Loaded ld, List<Channel> forYou) {
        CloudData.awaitReady(2500);
        return build(ld, forYou, CLOUD);
    }

    public static Index build(Sources.Loaded ld, List<Channel> forYou, Hints h) {
        relabel(ld.bySrc.values(), h);
        relabel(Collections.singletonList(ld.merged), h);
        Index ix = new Index(forYou);
        ix.sources.addAll(ld.sources);
        List<K> ks = sorted(ld.merged, new HashSet<String>());
        ix.folded = new String[ks.size()];
        int n = 0;
        for (K k : ks) {
            Channel c = k.c;
            ix.all.add(c);
            ix.folded[n++] = Search.fold(c.name);
            ix.byUrl.put(c.url, c);
            if (c.areas != null) { for (String a : c.areas) add(ix.countries, a, c); }
            else if (!c.country.isEmpty()) add(ix.countries, c.country, c);
            List<String> seenL = new ArrayList<>(2);
            for (String g : c.groups) { String l = merged(g); if (!seenL.contains(l)) seenL.add(l); }
            if (seenL.isEmpty()) seenL.add("Other");
            for (String l : seenL) add(ix.cats, l, c);
        }
        for (Channel c : ix.all) { Dedup.key(c); Dedup.brand(c); } // pay the slow part here, off the UI thread, so ranking a section is fast
        for (String[] m : MERGE) if (ix.cats.containsKey(m[0])) ix.catOrder.add(m[0]);
        if (ix.cats.containsKey("Other")) ix.catOrder.add("Other");
        ix.countryOrder.addAll(ix.countries.keySet());
        final Map<String, List<Channel>> cc = ix.countries;
        Collections.sort(ix.countryOrder, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                if (a.equals("lk") != b.equals("lk")) return a.equals("lk") ? -1 : 1;
                return cc.get(b).size() - cc.get(a).size();
            }
        });
        if (ld.sources.size() > 1) {
            for (Sources.Source src : ld.sources) {
                LinkedHashMap<String, List<Channel>> f = new LinkedHashMap<>();
                for (K k : sorted(ld.bySrc.get(src.id), null)) {
                    String[] g = k.c.folders;
                    if (g.length == 0) add(f, "Other", k.c);
                    for (String x : g) add(f, x.isEmpty() ? "Other" : x, k.c);
                }
                final Map<String, List<Channel>> fm = f;
                List<String> order = new ArrayList<>(f.keySet());
                Collections.sort(order, new Comparator<String>() {
                    @Override public int compare(String a, String b) { return fm.get(b).size() - fm.get(a).size(); }
                });
                ix.folders.put(src.id, f);
                ix.folderOrder.put(src.id, order);
            }
        }
        return ix;
    }

    public List<String> folderOrder(String srcId) {
        List<String> l = folderOrder.get(srcId);
        return l == null ? new ArrayList<String>() : l;
    }

    public int count(String srcId) {
        for (Sources.Source s : sources) if (s.id.equals(srcId)) return s.count;
        return 0;
    }

    public String srcName(String srcId) {
        for (Sources.Source s : sources) if (s.id.equals(srcId)) return s.name;
        return srcId;
    }

    public List<Channel> search(String q, Set<String> favs) { return Search.run(all, folded, q, favs); }

    private static void add(Map<String, List<Channel>> m, String k, Channel c) {
        List<Channel> l = m.get(k);
        if (l == null) { l = new ArrayList<>(); m.put(k, l); }
        l.add(c);
    }

    private List<Channel> resolve(List<String> urls) {
        List<Channel> out = new ArrayList<>();
        for (String u : urls) { Channel c = byUrl.get(u); if (c != null) out.add(c); }
        return out;
    }

    // ---- ranking: fold duplicates, sort by signals, hide dead; cached per section ----

    private volatile Ranker.Signals sig;
    private final Map<String, Object[]> cache = new HashMap<>(); // id -> {raw list it was built from, ranked list}
    private volatile boolean dirty;
    private long lastRefresh;
    private String sigKey = "";

    public void setSignals(Ranker.Signals s) { sig = s; synchronized (cache) { cache.clear(); } }

    /** Health, favourites or plays changed: the next {@link #refresh} may rebuild. Cheap; call as often as you like. */
    public void markDirty() { dirty = true; }

    /** Drops every ranked list now (health was reset in bulk). */
    public void rebuildNow() { synchronized (cache) { cache.clear(); } }

    /**
     * Drops the ranked lists when the pinned countries / "show unavailable" setting changed (at once), or when something
     * was marked dirty and 5 s passed. Call it when a section is OPENED, never while the user is looking at one.
     */
    public void refresh(Store s) {
        String k = s.fyCountries() + "|" + s.showUnavail() + "|" + s.crossLang();
        long now = System.currentTimeMillis();
        if (k.equals(sigKey) && !(dirty && now - lastRefresh >= 5000)) return;
        sigKey = k;
        dirty = false;
        lastRefresh = now;
        synchronized (cache) { cache.clear(); }
    }

    /**
     * THE single place every section's channel list is produced (guide rows, zap list, restore after restart).
     * Callers must not modify the returned list.
     */
    public List<Channel> list(String id, Store s) { return out(id, raw(id, s), s); }

    @SuppressWarnings("unchecked")
    private List<Channel> out(String id, List<Channel> r, Store s) {
        Ranker.Signals sg = sig;
        if (sg == null || r.isEmpty() || id.equals("fav") || id.equals("recent")) return r; // favourites / recents keep the user's order
        synchronized (cache) {
            Object[] e = cache.get(id);
            if (e != null && e[0] == r) return (List<Channel>) e[1];
        }
        final Ranker.Signals g = sg;
        long t0 = System.nanoTime();
        List<Channel> l = Dedup.fold(r, g, s.versionMap(), s.crossLang());
        long t1 = System.nanoTime();
        int groups = l.size();
        Ranker.sort(l, sg);
        long t2 = System.nanoTime();
        l = Ranker.visible(l, sg, s.showUnavail());
        if (r.size() >= 200) log("rank " + id + ": " + r.size() + " streams -> " + groups + " entries -> " + l.size() + " rows, fold " + (t1 - t0) / 1000000 + " ms, sort " + (t2 - t1) / 1000000 + " ms");
        synchronized (cache) { cache.put(id, new Object[]{r, l}); }
        return l;
    }

    private static void log(String m) {
        try { android.util.Log.i("IPTV", m); } catch (Throwable ignored) { } // plain-JVM tests have only android.jar stubs
    }

    private List<Channel> raw(String id, Store s) {
        List<Channel> r = null;
        if (id.equals("fav")) return resolve(s.favs());
        if (id.equals("recent")) return resolve(s.recents());
        if (id.equals("foryou")) return forYou;
        if (id.equals("all")) return all;
        if (id.equals("lk")) r = countries.get("lk");
        else if (id.startsWith("cat:")) r = cats.get(id.substring(4));
        else if (id.startsWith("cc:")) r = countries.get(id.substring(3));
        else if (id.startsWith("sf:")) {
            int t = id.indexOf('\t');
            Map<String, List<Channel>> f = t < 0 ? null : folders.get(id.substring(3, t));
            if (f != null) r = f.get(id.substring(t + 1));
        }
        return r == null ? new ArrayList<Channel>() : r;
    }

    public String title(String id) {
        switch (id) {
            case "fav": return "Favourites";
            case "recent": return "Recent";
            case "lk": return "Sri Lanka";
            case "countries": return "Countries";
            case "cats": return "Categories";
            case "srcs": return "My Sources";
            case "all": return "All channels";
            case "settings": return "Settings";
            case "search": return "Search";
            case "foryou": return "For You";
            case "yt": return "YouTube Live";
            default:
                if (id.startsWith("cat:")) return id.substring(4);
                if (id.startsWith("cc:")) return lk.manus.iptv.ui.Ui.country(id.substring(3));
                if (id.startsWith("sf:")) return id.substring(id.indexOf('\t') + 1);
                return "For You";
        }
    }
}
