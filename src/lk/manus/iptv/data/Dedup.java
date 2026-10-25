package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Folds streams (a group sits where its first stream was, so a pre-sorted list stays sorted) of the same channel (same normalised name + country) into one row; the rest are alternates. */
public final class Dedup {
    private Dedup() {}

    private static final Pattern NOISE = Pattern.compile("\\b(hd|fhd|uhd|sd|4k|full hd|\\d{3,4}[pi])\\b");
    private static final Pattern JUNK = Pattern.compile("[^\\p{L}\\p{N}]+");

    // Accumulates across sections (each section folds on its own); a rep keeps the alternates of the section that folded it last.
    private static final Map<Channel, List<Channel>> alts = java.util.Collections.synchronizedMap(new IdentityHashMap<Channel, List<Channel>>());

    /** Normalised name; "tv" is kept on purpose ("Star TV" is not "Star"). */
    public static String key(Channel c) {
        String k = c.dkey;
        if (k == null) c.dkey = k = compute(c);
        return k;
    }

    private static String compute(Channel c) {
        String n = Search.fold(c.name);
        n = NOISE.matcher(n).replaceAll(" ");
        n = JUNK.matcher(n).replaceAll(" ").trim();
        return n + "|" + c.country;
    }

    // ---- brand key (T36): the name without quality tags and without markers that only say which region this version is for ----

    private static final Pattern PAREN = Pattern.compile("\\(([^)]*)\\)");
    private static final Map<String, List<String[]>> OWN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String[][] ALIASES = {
        {"uk", "uk", "gb", "united kingdom", "great britain", "britain", "england"},
        {"us", "us", "usa", "united states", "united states of america", "america"},
        {"latam", "latam", "latin america", "latinoamerica", "hispam"},
        {"ae", "uae", "united arab emirates"}, {"kr", "korea", "south korea"}, {"cz", "czech", "czechia", "czech republic"},
    };

    /** Phrases (as word arrays) that name this country in a channel name: its code, aliases and English name. */
    private static List<String[]> own(String cc) {
        List<String[]> l = cc.isEmpty() ? Collections.<String[]>emptyList() : OWN.get(cc);
        if (l == null) {
            List<String> p = new ArrayList<>();
            p.add(cc);
            for (String[] a : ALIASES) if (a[0].equals(cc)) for (int i = 1; i < a.length; i++) p.add(a[i]);
            String n = cc.length() == 2 ? new java.util.Locale("", cc).getDisplayCountry(java.util.Locale.ENGLISH) : "";
            if (!n.isEmpty()) p.add(Search.fold(n));
            l = new ArrayList<>();
            for (String x : p) l.add(x.split(" "));
            OWN.put(cc, l);
        }
        return l;
    }

    private static boolean ends(List<String> t, String[] p) {
        if (t.size() <= p.length) return false;
        for (int i = 0; i < p.length; i++) if (!t.get(t.size() - p.length + i).equals(p[i])) return false;
        return true;
    }

    /**
     * Brand of a channel: folded name, "int'l" spelled out, quality tags removed, and "(India)" / "UK" / "Latin America" removed
     * when it names one of the channel's OWN countries. Spaces dropped ("Nick Jr." = "NickJr"). Sets {@code breg} only when a trailing
     * marker ("TBN UK") was removed: iptv-org's own "(Country)" tag exists to tell apart DIFFERENT channels of one name ("Canal 13 (Chile)").
     */
    public static String brand(Channel c) {
        String b = c.bkey;
        if (b != null) return b;
        String n = Search.fold(c.name);
        if (n.indexOf("int") >= 0) n = n.replace("int'l", "international").replace("intl", "international");
        List<String[]> own = own(c.country);
        if (c.areas != null && c.areas.length > 1) { own = new ArrayList<>(own); for (String a : c.areas) own.addAll(own(a)); }
        boolean reg = false;
        if (n.indexOf('(') >= 0) {
            Matcher m = PAREN.matcher(n);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String in = String.join(" ", words(m.group(1)));
                boolean hit = false;
                for (String[] p : own) if (in.equals(String.join(" ", p))) { hit = true; break; }
                m.appendReplacement(sb, hit ? " " : Matcher.quoteReplacement(m.group()));
            }
            m.appendTail(sb);
            n = sb.toString();
        }
        List<String> t = new ArrayList<>(6);
        for (String w : words(n)) if (!noise(w)) t.add(w);
        for (boolean again = !own.isEmpty(); again; ) {
            again = false;
            for (String[] p : own) if (ends(t, p)) {
                int len = 0;
                for (int i = 0; i < t.size() - p.length; i++) len += t.get(i).length();
                if (len < 3) continue;
                for (int i = 0; i < p.length; i++) t.remove(t.size() - 1);
                reg = again = true;
                break;
            }
        }
        c.breg = reg;
        b = t.size() == 1 ? t.get(0) : String.join("", t);
        c.bgk = b.length() < 3 ? null : "b" + b + "|" + c.country;                       // too short a brand ("tv", "1") never groups
        c.blk = b.length() < 4 || c.lang.isEmpty() || generic(t) ? null : b + "|" + c.lang;
        c.bkey = b;
        return b;
    }

    private static final java.util.Set<String> GENERIC = new java.util.HashSet<>(java.util.Arrays.asList(
            "canal", "channel", "tv", "television", "kanal", "tele", "telekanal", "radio", "kino", "news", "tvn", "the", "tv1", "one", "plus", "live", "cinema", "movies", "music", "sport", "sports"));

    /** "Canal 13", "Channel 5", "TV": a name that says nothing about WHICH channel, so the same words in two countries are two channels. */
    private static boolean generic(List<String> t) {
        int letters = 0;
        for (String w : t) {
            if (Character.isDigit(w.charAt(0))) continue;
            if (!GENERIC.contains(w)) return false;
            letters++;
        }
        return letters >= 0;
    }

    /** Lowercase words of letters/digits, no regex. */
    private static List<String> words(String s) {
        List<String> out = new ArrayList<>(6);
        int st = -1;
        for (int i = 0; i <= s.length(); i++) {
            boolean w = i < s.length() && Character.isLetterOrDigit(s.charAt(i));
            if (w && st < 0) st = i;
            else if (!w && st >= 0) { out.add(s.substring(st, i)); st = -1; }
        }
        return out;
    }

    /** Quality words: hd fhd uhd sd 4k 720p 1080i ... (the same ones {@link #NOISE} removes). */
    private static boolean noise(String w) {
        int n = w.length();
        if (n == 2) return w.equals("hd") || w.equals("sd") || w.equals("4k");
        if (n == 3) return w.equals("fhd") || w.equals("uhd");
        if (n < 4 || n > 5) return false;
        char e = w.charAt(n - 1);
        if (e != 'p' && e != 'i') return false;
        for (int i = 0; i < n - 1; i++) if (w.charAt(i) < '0' || w.charAt(i) > '9') return false;
        return true;
    }

    private static int qrank(Channel c) {
        String q = c.quality;
        int r = q.equals("4K") || q.equals("2160p") ? 5 : q.equals("1080p") ? 4 : q.equals("720p") ? 3
                : q.equals("576p") ? 2 : q.equals("480p") ? 1 : 0;
        return r * 2 + (c.logo.isEmpty() ? 0 : 1);
    }

    public static List<Channel> fold(List<Channel> list) { return fold(list, (java.util.function.ToIntFunction<String>) null); }

    /** Base tvg-id without "@feed", lowercase; "" when the channel has none. */
    public static String idKey(Channel c) {
        String k = c.ikey;
        if (k == null) {
            int at = c.id.indexOf('@');
            c.ikey = k = (at < 0 ? c.id : c.id.substring(0, at)).toLowerCase(java.util.Locale.ROOT);
        }
        return k;
    }

    /** With a health function (DEAD -1 / UNKNOWN 0 / ALIVE 1 per url) a live stream is preferred as the row's main stream. */
    public static List<Channel> fold(List<Channel> list, final java.util.function.ToIntFunction<String> health) {
        return fold(list, health == null ? null : new Ranker.Signals() {
            @Override public boolean isFavourite(String url) { return false; }
            @Override public int playCount(String url) { return 0; }
            @Override public long lastPlayedMs(String url) { return 0; }
            @Override public int health(String url) { return health.applyAsInt(url); }
            @Override public Set<String> pinnedCountries() { return Collections.<String>emptySet(); }
        }, null, false);
    }

    /** Same-category test for the optional cross-language merge. */
    private static boolean sharesCategory(Channel a, Channel b) {
        for (String x : a.groups) for (String y : b.groups) if (Index.merged(x).equals(Index.merged(y))) return true;
        return a.groups.length == 0 && b.groups.length == 0;
    }

    /** Categories overlap, or one side has none ("Undefined" / "Other" says nothing). */
    private static boolean compatible(Channel a, Channel b) {
        return sharesCategory(a, b) || unknownCats(a) || unknownCats(b);
    }

    private static boolean unknownCats(Channel c) {
        for (String g : c.groups) if (!Index.merged(g).equals("Other")) return false;
        return true;
    }

    private static final class G { final List<Channel> m = new ArrayList<>(2); Channel first; boolean reg; }

    /**
     * Region consolidation (T25). A group is the same base tvg-id (feeds "@SD", "@NorthAmerica"... of one channel) OR the same
     * normalised name + country; with {@code crossLang} also the same name in another country when the categories overlap.
     * Row = best member: TV-alive > cloud-alive > quality > the user's country order > popularity; a remembered choice
     * ({@code choice}: id/name key -> url) wins unless that stream is dead.
     */
    public static List<Channel> fold(List<Channel> list, final Ranker.Signals sg, Map<String, String> choice, boolean crossLang) {
        Map<String, G> byKey = new HashMap<>(list.size() * 2);
        Map<String, List<G>> byName = crossLang ? new HashMap<String, List<G>>() : null;
        Map<String, List<G>> byLang = new HashMap<>();
        List<G> order = new ArrayList<>();
        for (Channel c : list) {
            G g = null;
            if (c.name.isEmpty()) { g = new G(); order.add(g); g.first = c; g.m.add(c); continue; }
            String nk = "n" + key(c), ik = idKey(c);
            if (!ik.isEmpty()) g = byKey.get("i" + ik);
            if (g == null) g = byKey.get(nk);
            brand(c);
            String bk = c.bgk;                        // same brand, same country (also Free-TV vs iptv-org)
            if (g == null && bk != null) g = byKey.get(bk);
            String lk = c.blk;                        // same brand, same language, another country: only when a region marker shows it is a regional version
            if (g == null && lk != null) {
                List<G> same = byLang.get(lk);
                if (same != null) for (G x : same) if ((x.reg || c.breg) && compatible(x.first, c)) { g = x; break; }
            }
            String name = null;
            if (g == null && crossLang) {
                name = nk.substring(1, nk.lastIndexOf('|'));
                List<G> same = byName.get(name);
                if (same != null) for (G x : same) if (sharesCategory(x.first, c)) { g = x; break; }
            }
            if (g == null) { g = new G(); g.first = c; order.add(g); }
            if (c.breg) g.reg = true;
            if (lk != null) { List<G> same = byLang.get(lk); if (same == null) byLang.put(lk, same = new ArrayList<>(1)); if (!same.contains(g)) same.add(g); }
            if (crossLang) { List<G> same = byName.get(name != null ? name : (name = nk.substring(1, nk.lastIndexOf('|')))); if (same == null) byName.put(name, same = new ArrayList<>(1)); if (!same.contains(g)) same.add(g); }
            if (!ik.isEmpty() && !byKey.containsKey("i" + ik)) byKey.put("i" + ik, g);
            if (!byKey.containsKey(nk)) byKey.put(nk, g);
            if (bk != null && !byKey.containsKey(bk)) byKey.put(bk, g);
            boolean dupUrl = false;
            for (Channel x : g.m) if (x.url.equals(c.url)) { dupUrl = true; break; }
            if (!dupUrl) g.m.add(c);
        }
        final Map<String, Integer> pin = new HashMap<>();
        if (sg != null) { int i = 0; for (String p : sg.pinnedCountries()) pin.put(p, i++); }
        final Ranker.Signals s = sg;
        Comparator<Channel> cmp = new Comparator<Channel>() {
            @Override public int compare(Channel a, Channel b) {
                if (s != null) {
                    int d = Integer.compare(s.health(b.url), s.health(a.url));
                    if (d != 0) return d;
                }
                int d = Integer.compare(qrank(b), qrank(a));
                if (d != 0) return d;
                if (s != null) {
                    Integer pa = pin.get(a.country), pb = pin.get(b.country);
                    d = Integer.compare(pa == null ? 99 : pa, pb == null ? 99 : pb);
                    if (d != 0) return d;
                    d = Integer.compare(pop(s, b), pop(s, a));
                }
                return d;
            }
        };
        Map<Channel, List<Channel>> a = new IdentityHashMap<>();
        List<Channel> reps = new ArrayList<>(order.size());
        for (G g : order) {
            List<Channel> m = g.m;
            if (m.size() > 1) {
                Collections.sort(m, cmp); // stable: input order breaks ties
                if (choice != null && !choice.isEmpty()) {
                    String u = null;
                    for (Channel c : m) { u = choice.get("n" + key(c)); if (u == null && !idKey(c).isEmpty()) u = choice.get("i" + idKey(c)); if (u != null) break; }
                    if (u != null) for (int i = 0; i < m.size(); i++) if (m.get(i).url.equals(u)) {
                        if (i > 0 && (s == null || s.health(u) != Health.DEAD)) m.add(0, m.remove(i));
                        break;
                    }
                }
            }
            Channel rep = m.get(0);
            reps.add(rep);
            a.put(rep, m.size() == 1 ? Collections.<Channel>emptyList() : new ArrayList<>(m.subList(1, m.size())));
        }
        alts.putAll(a);
        return reps;
    }

    private static int pop(Ranker.Signals s, Channel c) {
        int p = s.popularity(c.url);
        return p >= 0 ? p : 10 * Popular.of(c.id);
    }

    /** The keys a remembered version choice for this channel is filed under (base tvg-id and normalised name). */
    public static String[] choiceKeys(Channel c) {
        return idKey(c).isEmpty() ? new String[]{"n" + key(c)} : new String[]{"i" + idKey(c), "n" + key(c)};
    }

    /** The row plus its other versions (rep first). */
    public static List<Channel> versions(Channel rep) {
        List<Channel> l = new ArrayList<>(1 + alternates(rep).size());
        l.add(rep);
        l.addAll(alternates(rep));
        return l;
    }

    /** Makes {@code pick} the row of its group in memory (the old row and the rest become its alternates). */
    public static void promote(Channel rep, Channel pick) {
        List<Channel> v = versions(rep);
        v.remove(pick);
        alts.put(pick, v);
    }

    /** DEBUG test hook: make {@code alt} the (only) alternate stream of {@code rep}. */
    public static void register(Channel rep, Channel alt) { alts.put(rep, new ArrayList<>(Collections.singletonList(alt))); }

    /** Other streams for the same channel from the last {@link #fold}, best first; empty if none. */
    public static List<Channel> alternates(Channel rep) {
        List<Channel> l = alts.get(rep);
        return l == null ? Collections.<Channel>emptyList() : l;
    }
}
