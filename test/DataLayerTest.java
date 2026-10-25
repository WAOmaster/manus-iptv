import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import lk.manus.iptv.data.*;

/** T15: Health rules, Prober parsing, Ranker, Dedup. Run from the project folder: java -cp build/test DataLayerTest [full-playlist.m3u] */
public class DataLayerTest {
    static int fails = 0, checks = 0;
    static final long D = 86400000L, H = 3600000L, T0 = 1_800_000_000_000L;

    static void eq(String what, Object e, Object a) {
        checks++;
        if (!Objects.equals(e, a)) { fails++; System.out.println("FAIL " + what + ": expected [" + e + "] got [" + a + "]"); }
    }

    static Channel ch(String id, String name, String q, String logo, String cc, String url, String... groups) {
        return new Channel(id, name, q, logo, groups, cc, url, "", "");
    }

    // ---- Health ----
    static void health() {
        Health.Rule r = new Health.Rule();
        eq("fresh unknown", Health.UNKNOWN, r.status(T0));
        eq("fresh needs probe", true, r.needsProbe(T0));
        r.record(true, T0);
        eq("ok alive", Health.ALIVE, r.status(T0 + D));
        eq("alive no probe 2d", false, r.needsProbe(T0 + 2 * D));
        eq("alive probe 3d+", true, r.needsProbe(T0 + 3 * D + 1));
        eq("alive->unknown 7d", Health.UNKNOWN, r.status(T0 + 7 * D + 1));
        r.record(false, T0 + D);
        eq("one fail still alive", Health.ALIVE, r.status(T0 + D));
        r.record(false, T0 + D + H);
        eq("2 fails same day not dead", Health.ALIVE, r.status(T0 + D + H));
        r.record(false, T0 + D + 2 * H);
        eq("3 fails one day dead", Health.DEAD, r.status(T0 + D + 2 * H));
        r.record(true, T0 + 2 * D);
        eq("success revives", Health.ALIVE, r.status(T0 + 2 * D));

        Health.Rule d = new Health.Rule();
        long day1 = (T0 / D + 1) * D + H; // inside a UTC day
        d.record(false, day1);
        d.record(false, day1 + D);
        eq("2 fails 2 days dead", Health.DEAD, d.status(day1 + D));
        eq("dead no probe 6d", false, d.needsProbe(day1 + D + 6 * D));
        eq("dead probe after 7d", true, d.needsProbe(day1 + D + 7 * D + 1));
        Health.Rule u = new Health.Rule();
        u.record(false, T0);
        eq("1 fail unknown", Health.UNKNOWN, u.status(T0));
        eq("unknown retry gap", false, u.needsProbe(T0 + 10 * 60000));
        eq("unknown retry after 1h", true, u.needsProbe(T0 + H + 1));
    }

    // ---- Prober parsing ----
    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static void prober() {
        eq("media playlist ok", 1, Prober.judge(b("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n")));
        eq("master", 2, Prober.judge(b("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n")));
        eq("bom", 1, Prober.judge(b("\uFEFF#EXTM3U\n#EXTINF:1,\n")));
        eq("html page", 0, Prober.judge(b("<html>blocked</html>")));
        eq("empty", 0, Prober.judge(new byte[0]));
        byte[] ts = new byte[400];
        ts[0] = 0x47; ts[188] = 0x47;
        eq("ts ok", 1, Prober.judge(ts));
        ts[188] = 0;
        eq("ts bad second sync", 0, Prober.judge(ts));
        eq("variant", "low/index.m3u8", Prober.firstVariant("#EXTM3U\r\n#EXT-X-STREAM-INF:BANDWIDTH=1\r\nlow/index.m3u8\r\n"));
        eq("no variant", null, Prober.firstVariant("#EXTM3U\n#EXTINF:1,\nx.ts"));
        eq("media check", true, Prober.mediaPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:4"));
        eq("media check no", false, Prober.mediaPlaylist("#EXTM3U\n#EXT-X-STREAM-INF:\nx"));
    }

    // ---- Ranker ----
    static class Sig implements Ranker.Signals {
        Set<String> favs = new HashSet<>();
        Map<String, Integer> pc = new HashMap<>(), hl = new HashMap<>();
        Map<String, Long> lp = new HashMap<>();
        Set<String> pinned = new LinkedHashSet<>();
        public boolean isFavourite(String u) { return favs.contains(u); }
        public int playCount(String u) { return pc.getOrDefault(u, 0); }
        public long lastPlayedMs(String u) { return lp.getOrDefault(u, 0L); }
        public int health(String u) { return hl.getOrDefault(u, 0); }
        public Set<String> pinnedCountries() { return pinned; }
        Map<String, Integer> pop = new HashMap<>();
        public int popularity(String u) { return pop.getOrDefault(u, -1); }
    }

    static String names(List<Channel> l) { StringBuilder s = new StringBuilder(); for (Channel c : l) s.append(c.name).append(','); return s.toString(); }

    static List<Channel> list(Channel... cs) { return new ArrayList<>(Arrays.asList(cs)); }

    static void ranker() {
        Channel a = ch("", "Alpha", "", "", "", "u/a");
        Channel b = ch("", "Beta", "", "", "", "u/b");
        Channel c = ch("", "Gamma", "", "", "", "u/c");
        Sig s = new Sig();
        List<Channel> l = list(c, b, a);
        Ranker.sort(l, s);
        eq("name A-Z when all equal", "Alpha,Beta,Gamma,", names(l));

        s.hl.put("u/c", Health.ALIVE); s.hl.put("u/a", Health.DEAD);
        l = list(a, b, c); Ranker.sort(l, s);
        eq("alive > unknown > dead", "Gamma,Beta,Alpha,", names(l));
        eq("visible hides dead", "Beta,Gamma,", names(Ranker.visible(list(b, c, a), s, false)));
        eq("visible showUnavailable keeps", 3, Ranker.visible(list(a, b, c), s, true).size());

        s = new Sig();
        s.favs.add("u/c");
        l = list(a, b, c); Ranker.sort(l, s);
        eq("favourite first", "Gamma,Alpha,Beta,", names(l));

        s.hl.put("u/a", Health.DEAD); s.favs.add("u/a");
        l = list(a, b, c); Ranker.sort(l, s);
        eq("health beats favourite", "Gamma,Beta,Alpha,", names(l));

        s = new Sig();
        s.pc.put("u/b", 5); s.pc.put("u/c", 5); s.lp.put("u/b", T0); s.lp.put("u/c", T0 + 10 * H);
        l = list(a, b, c); Ranker.sort(l, s);
        eq("play count then recency", "Gamma,Beta,Alpha,", names(l));
        s.pc.put("u/a", 9);
        l = list(a, b, c); Ranker.sort(l, s);
        eq("higher play count first", "Alpha,Gamma,Beta,", names(l));

        s = new Sig();
        Channel lk = ch("Hiru.lk@SD", "Zed", "", "", "lk", "u/lk");
        Channel uk = ch("X.uk", "Yak", "", "", "uk", "u/uk");
        Channel us = ch("X.us", "Aaa", "", "", "us", "u/us");
        s.pinned.add("lk"); s.pinned.add("uk");
        l = list(us, uk, lk); Ranker.sort(l, s);
        eq("pinned countries first in pin order", "Zed,Yak,Aaa,", names(l));

        s = new Sig();
        Channel hd = ch("", "Q1", "1080p", "", "", "u/1");
        Channel sd = ch("", "Q0", "480p", "", "", "u/2");
        Channel logo = ch("", "Q2", "", "http://l", "", "u/3");
        l = list(sd, logo, hd); Ranker.sort(l, s);
        eq("quality 1080p > logo > 480p", "Q1,Q2,Q0,", names(l));

        Channel news = ch("", "Zn", "", "", "", "u/n", "News");
        Channel other = ch("", "Aa", "", "", "", "u/o", "Legislative");
        l = list(other, news); Ranker.sort(l, s);
        eq("mainstream category", "Zn,Aa,", names(l));

        Channel bbc = ch("BBCNews.uk@SD", "Zzz BBC", "", "", "uk", "u/bbc");
        l = list(other, bbc); Ranker.sort(l, s);
        eq("popularity prior", "Zzz BBC,Aa,", names(l));

        // cloud hint: LIKELY sits between UNKNOWN and ALIVE; cloud popularity replaces the table
        s = new Sig();
        s.hl.put("u/a", Health.UNKNOWN); s.hl.put("u/b", Health.LIKELY); s.hl.put("u/c", Health.ALIVE);
        l = list(a, b, c); Ranker.sort(l, s);
        eq("alive > likely > unknown", "Gamma,Beta,Alpha,", names(l));
        s = new Sig();
        s.pop.put("u/bbc", 0); s.pop.put("u/o", 90);
        l = list(bbc, other); Ranker.sort(l, s);
        eq("cloud popularity beats table", "Aa,Zzz BBC,", names(l));
        eq("merge: TV alive beats cloud d", Health.ALIVE, Health.merge(Health.ALIVE, 'd'));
        eq("merge: unknown + d = dead", Health.DEAD, Health.merge(Health.UNKNOWN, 'd'));
        eq("merge: unknown + a = likely", Health.LIKELY, Health.merge(Health.UNKNOWN, 'a'));
        eq("merge: g/t ignored", Health.UNKNOWN, Health.merge(Health.UNKNOWN, 't'));
        eq("merge: TV dead beats cloud a", Health.DEAD, Health.merge(Health.DEAD, 'a'));

        CloudData.Prog pr = new CloudData.Prog(new long[]{100, 200, 300}, new long[]{200, 300, 400}, new String[]{"A", "B", "C"});
        eq("now mid", "B", CloudData.at(pr, 250, 0).title);
        eq("now at boundary is the new one", "B", CloudData.at(pr, 200, 0).title);
        eq("next", "C", CloudData.at(pr, 250, 1).title);
        eq("no next at end", null, CloudData.at(pr, 350, 1));
        eq("before first: no now", null, CloudData.at(pr, 50, 0));
        eq("before first: next is first", "A", CloudData.at(pr, 50, 1).title);
        eq("after last: none", null, CloudData.at(pr, 500, 0));
        eq("progress", 0.5f, CloudData.at(pr, 250, 0).progress(250000L));

        // stability: identical keys keep input order only when names equal
        Channel t1 = ch("", "Same", "", "", "", "u/s1"), t2 = ch("", "Same", "", "", "", "u/s2"), t3 = ch("", "same", "", "", "", "u/s3");
        l = list(t2, t1, t3); Ranker.sort(l, new Sig());
        eq("stable on full tie", "u/s2,u/s1,u/s3", l.get(0).url + "," + l.get(1).url + "," + l.get(2).url);
    }

    // ---- Dedup ----
    static void dedup() {
        Channel bbcHd = ch("BBCNews.uk@HD", "BBC News HD", "720p", "", "uk", "u/1");
        Channel bbc = ch("BBCNews.uk@SD", "BBC News", "", "http://l", "uk", "u/2");
        Channel bbcUs = ch("BBCNews.us", "BBC News", "", "", "us", "u/3");
        eq("key strips HD", Dedup.key(bbc), Dedup.key(bbcHd));
        eq("key keeps country", false, Dedup.key(bbc).equals(Dedup.key(bbcUs)));
        List<Channel> f = Dedup.fold(list(bbcHd, bbcUs, bbc));
        eq("fold count", 2, f.size());
        eq("group at first position", "u/1,u/3", f.get(0).url + "," + f.get(1).url);
        eq("alternates", "u/2", names1(Dedup.alternates(f.get(0))));
        eq("no alternates", 0, Dedup.alternates(f.get(1)).size());

        Channel s1 = ch("", "Star TV", "", "", "tr", "u/a"), s2 = ch("", "Star", "", "", "tr", "u/b");
        eq("tv kept", 2, Dedup.fold(list(s1, s2)).size());
        Channel p = ch("", "Café   Music (1080p)", "1080p", "", "fr", "u/c"), q = ch("", "cafe music-FHD", "", "", "fr", "u/d");
        eq("accents/punct/spaces", 1, Dedup.fold(list(p, q)).size());
        Channel x1 = ch("", "Sky Sports 1", "", "", "uk", "u/e"), x2 = ch("", "Sky Sports 2", "", "", "uk", "u/f");
        eq("numbers differ", 2, Dedup.fold(list(x1, x2)).size());
        Channel k1 = ch("", "News", "480p", "", "uk", "u/g"), k2 = ch("", "News", "720p", "", "uk", "u/h"), k3 = ch("", "News", "1080p", "", "uk", "u/i"), k4 = ch("", "News", "720p", "", "uk", "u/j");
        f = Dedup.fold(list(k1, k2, k3, k4));
        eq("alternates best first", "u/h,u/j,u/g", urls(Dedup.alternates(f.get(0))));
        final Channel hd = ch("", "Live", "1080p", "", "uk", "u/hd"), sd = ch("", "Live", "480p", "", "uk", "u/sd");
        f = Dedup.fold(list(hd, sd), url -> url.equals("u/hd") ? Health.DEAD : Health.ALIVE);
        eq("alive stream preferred as the row", "u/sd", f.get(0).url);
        eq("dead one is the alternate", "u/hd", urls(Dedup.alternates(f.get(0))));
        Dedup.fold(list(hd, sd));
        Dedup.fold(list(k1, k2));
        eq("alternates survive folding another section", "u/sd", urls(Dedup.alternates(hd)));
        Channel d1 = ch("", "Same", "", "", "uk", "u/z"), d2 = ch("", "Same", "", "", "uk", "u/z");
        eq("same url folded", 0, Dedup.alternates(Dedup.fold(list(d1, d2)).get(0)).size());
        eq("empty list", 0, Dedup.fold(new ArrayList<>()).size());
    }


    // ---- T25 region consolidation ----
    static void regions() {
        // same base tvg-id, different feed + different display name -> one entry
        Channel na = ch("BBCNews.uk@NorthAmerica", "BBC News North America", "", "", "uk", "r/na"), hd = ch("BBCNews.uk@HD", "BBC News", "1080p", "", "uk", "r/hd");
        List<Channel> f = Dedup.fold(list(na, hd), new Sig(), null, false);
        eq("feeds of one id fold", 1, f.size());
        eq("quality picks the row", "r/hd", f.get(0).url);
        eq("versions = row + others", "r/hd,r/na", urls(Dedup.versions(f.get(0))));
        eq("id key lowercases and drops feed", "bbcnews.uk", Dedup.idKey(na));

        // id'd and id-less streams of the same name + country still fold
        Channel withId = ch("Hiru.lk@SD", "Hiru TV", "", "", "lk", "r/h1"), noId = ch("", "Hiru TV", "480p", "", "lk", "r/h2");
        eq("id + no id, same name", 1, Dedup.fold(list(withId, noId), new Sig(), null, false).size());

        // across languages: off by default; on needs overlapping categories
        Channel de = ch("NickDE.de", "Nickelodeon", "", "", "de", "r/de", "Kids"), uk = ch("NickUK.uk", "Nickelodeon", "", "", "uk", "r/uk", "Kids");
        eq("cross off: DE and UK stay apart", 2, Dedup.fold(list(de, uk), new Sig(), null, false).size());
        eq("cross on, same category: merged", 1, Dedup.fold(list(de, uk), new Sig(), null, true).size());
        Channel uk2 = ch("NickUK2.uk", "Nickelodeon", "", "", "uk", "r/uk2", "News");
        eq("cross on, no shared category: apart", 2, Dedup.fold(list(de, uk2), new Sig(), null, true).size());
        eq("cross on never merges different names", 2, Dedup.fold(list(de, ch("X.uk", "Nick Jr", "", "", "uk", "r/nj", "Kids")), new Sig(), null, true).size());

        // representative: TV alive > cloud likely > unknown > dead, then quality, then country order, then popularity
        Sig s = new Sig();
        Channel a = ch("A.us", "Rep", "1080p", "", "us", "r/a"), b = ch("A.uk", "Rep", "480p", "", "uk", "r/b"), c = ch("A.fr", "Rep", "720p", "", "fr", "r/c");
        s.hl.put("r/a", Health.UNKNOWN); s.hl.put("r/b", Health.ALIVE); s.hl.put("r/c", Health.LIKELY);
        f = Dedup.fold(list(a, b, c), s, null, true);
        eq("TV alive beats higher quality", "r/b,r/c,r/a", urls(Dedup.versions(f.get(0))));
        s = new Sig();
        s.hl.put("r/a", Health.LIKELY); s.hl.put("r/b", Health.LIKELY); s.hl.put("r/c", Health.LIKELY);
        f = Dedup.fold(list(b, c, a), s, null, true);
        eq("then quality", "r/a,r/c,r/b", urls(Dedup.versions(f.get(0))));
        Channel q1 = ch("Q.uk", "Same", "720p", "", "uk", "r/q1"), q2 = ch("Q.us", "Same", "720p", "", "us", "r/q2");
        s = new Sig();
        s.pinned.add("us"); s.pinned.add("uk");
        eq("then the user's country order", "r/q2", Dedup.fold(list(q1, q2), s, null, true).get(0).url);
        s = new Sig();
        s.pop.put("r/q1", 20); s.pop.put("r/q2", 90);
        eq("then popularity", "r/q2", Dedup.fold(list(q1, q2), s, null, true).get(0).url);

        // remembered choice wins, unless that stream is dead
        s = new Sig();
        Map<String, String> choice = new HashMap<>();
        for (String k : Dedup.choiceKeys(b)) choice.put(k, "r/b");
        eq("choice wins over quality", "r/b", Dedup.fold(list(a, b), s, choice, true).get(0).url);
        s.hl.put("r/b", Health.DEAD);
        eq("dead choice ignored", "r/a", Dedup.fold(list(a, b), s, choice, true).get(0).url);

        // promote: the picked version becomes the row in memory, the old row an alternate
        f = Dedup.fold(list(a, b), new Sig(), null, true);
        Dedup.promote(f.get(0), b);
        eq("promote", "r/a", urls(Dedup.alternates(b)));

        // performance shape: 13k streams in ~8k groups with feeds
        List<Channel> big = new ArrayList<>();
        for (int i = 0; i < 13000; i++) big.add(ch("Ch" + (i % 8000) + ".us@F" + i, "Channel " + (i % 8000), i % 3 == 0 ? "720p" : "", "", "us", "r/big" + i, "News"));
        long t = System.nanoTime();
        int n = Dedup.fold(big, new Sig(), null, false).size();
        long ms = (System.nanoTime() - t) / 1000000;
        System.out.println("regions: 13000 streams -> " + n + " entries in " + ms + " ms");
        eq("13k folds to 8000", 8000, n);
        eq("13k fold under 200 ms on a PC", true, ms < 200);
    }

    static String names1(List<Channel> l) { return urls(l); }
    static String urls(List<Channel> l) { StringBuilder s = new StringBuilder(); for (Channel c : l) s.append(s.length() > 0 ? "," : "").append(c.url); return s.toString(); }

    static void timing(String path) throws Exception {
        List<Channel> all;
        try (java.io.Reader r = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) { all = M3uParser.parse(r); }
        Sig s = new Sig();
        s.pinned.add("lk"); s.pinned.add("in");
        for (int i = 0; i < all.size(); i += 7) s.hl.put(all.get(i).url, i % 3 - 1);
        long best = Long.MAX_VALUE, bestD = Long.MAX_VALUE;
        int folded = 0;
        for (int run = 0; run < 5; run++) {
            List<Channel> copy = new ArrayList<>(all);
            long t = System.nanoTime();
            Ranker.sort(copy, s);
            best = Math.min(best, System.nanoTime() - t);
            t = System.nanoTime();
            folded = Dedup.fold(all).size();
            bestD = Math.min(bestD, System.nanoTime() - t);
        }
        System.out.println("timing " + all.size() + " channels: sort " + best / 1000000 + " ms, fold " + bestD / 1000000 + " ms (-> " + folded + ")");
        eq("sort+fold < 300 ms", true, (best + bestD) / 1000000 < 300);
    }

    /** Zero sources must give a usable empty index (the app shows "No channels yet" and opens Settings). */
    static void empty() {
        Sources.Loaded ld = new Sources.Loaded(new ArrayList<Channel>(), new ArrayList<Sources.Source>(), new LinkedHashMap<String, List<Channel>>());
        Index ix = Index.build(ld, new ArrayList<Channel>(), new Index.Hints() {
            @Override public String[] areas(String url) { return null; }
            @Override public String lang(String url) { return null; }
        });
        eq("empty index has no channels", 0, ix.all.size());
        eq("empty index has no categories", 0, ix.catOrder.size());
        eq("empty index has no countries", 0, ix.countryOrder.size());
        eq("free-list url hidden in play", true, Sources.DEFAULT_URL.isEmpty() == lk.manus.iptv.Config.PLAY);
    }

    public static void main(String[] a) throws Exception {
        health(); empty(); prober(); ranker(); dedup(); regions();
        if (a.length > 0) timing(a[0]);
        System.out.println(checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
