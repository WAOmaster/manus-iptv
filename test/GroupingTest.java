import java.io.StringReader;
import java.util.*;

import lk.manus.iptv.data.*;

/** T35 countries from the feed (real iptv-org / Free-TV examples) and T36 brand grouping. */
public class GroupingTest {
    static int fails = 0, checks = 0;

    static void eq(String what, Object e, Object a) {
        checks++;
        if (!Objects.equals(e, a)) { fails++; System.out.println("FAIL " + what + ": expected [" + e + "] got [" + a + "]"); }
    }

    static Channel ch(String id, String name, String cc, String url, String... groups) {
        return new Channel(id, name, "", "", groups, cc, url, "", "");
    }

    static Map<String, String[]> areas = new HashMap<>();
    static Map<String, String> langs = new HashMap<>();
    static final Index.Hints H = new Index.Hints() {
        @Override public String[] areas(String u) { return areas.get(u); }
        @Override public String lang(String u) { return langs.get(u); }
    };

    static Channel real(List<Channel> l, String id, String name, String suffixCc, String lang, String... cc) {
        Channel c = ch(id, name, suffixCc, "u/" + id, "Entertainment");
        if (cc.length > 0) areas.put(c.url, cc);
        if (lang != null) langs.put(c.url, lang);
        l.add(c);
        return c;
    }

    static Index build(List<Channel> l) {
        Map<String, List<Channel>> by = new LinkedHashMap<>();
        by.put("d", l);
        return Index.build(new Sources.Loaded(l, new ArrayList<Sources.Source>(), by), new ArrayList<Channel>(), H);
    }

    static boolean in(Index ix, String cc, Channel c) { List<Channel> l = ix.countries.get(cc); return l != null && l.contains(c); }

    // ---- T35: 17 real feeds whose tvg-id ends in .us ----
    static void countries() {
        List<Channel> l = new ArrayList<>();
        Channel[] br = {
            real(l, "AELatinAmerica.us@Brazil", "A&E Latin America Brazil", "us", "por", "br"),
            real(l, "BabyFirst.us@Brazil", "BabyFirst Brazil", "us", "por", "br"),
            real(l, "AvatarAlendadeAang.us@BR", "Avatar: A lenda de Aang", "us", "por", "br")};
        Channel adult = real(l, "AdultSwimLatinAmerica.us@Panregional", "Adult Swim Latin America", "us", "spa", "ar", "py", "uy");
        Channel amc = real(l, "AMCLatinAmerica.us@Panregional", "AMC Latin America", "us", "spa", "ar", "py", "uy");
        Channel axnMx = real(l, "AXNLatinAmerica.us@Mexico", "AXN Latin America Mexico", "us", "spa", "mx");
        Channel axnAndes = real(l, "AXNLatinAmerica.us@Andes", "AXN Latin America Andes", "us", "spa", "co", "pe", "ec", "bo");
        Channel acapulco = real(l, "AcapulcoShore.us@SD", "Acapulco Shore", "us", "spa", "latam");
        Channel infantil = real(l, "CanalInfantil.us@SD", "Canal Infantil", "us", "spa", "latam");
        Channel ebs = real(l, "EBS.us@HD", "EBS HD", "us", "amh", "et");
        Channel ebsCinema = real(l, "EBSCinema.us@SD", "EBS Cinema", "us", "amh", "et");
        Channel ca = real(l, "48Hours.us@CA", "48 Hours CA", "us", "eng", "ca");
        Channel cops = real(l, "5Cops.us@UK", "5 Cops UK", "us", "eng", "uk");
        Channel andr = real(l, "Andromeda.us@Italy", "Andromeda Italy", "us", "ita", "it");
        Channel redbull = real(l, "RedBullTV.at@US", "Red Bull TV US", "at", "eng", "us", "ca");
        Channel metv = real(l, "MeTV.us@SD", "MeTV", "us", "eng"); // genuinely US: no override
        Channel noHint = ch("Foo.us@SD", "Foo", "us", "u/foo", "News");
        l.add(noHint);
        Index ix = build(l);
        for (Channel c : br) { eq("br " + c.id, true, in(ix, "br", c)); eq("not us " + c.id, false, in(ix, "us", c)); eq("country " + c.id, "br", c.country); }
        for (String cc : new String[]{"ar", "py", "uy"}) { eq("adult swim in " + cc, true, in(ix, cc, adult)); eq("amc in " + cc, true, in(ix, cc, amc)); }
        eq("adult swim not us", false, in(ix, "us", adult));
        eq("axn mexico", true, in(ix, "mx", axnMx));
        for (String cc : new String[]{"co", "pe", "ec", "bo"}) eq("axn andes in " + cc, true, in(ix, cc, axnAndes));
        eq("acapulco in latam", true, in(ix, "latam", acapulco));
        eq("infantil in latam", true, in(ix, "latam", infantil));
        eq("acapulco not us", false, in(ix, "us", acapulco));
        eq("ebs in et", true, in(ix, "et", ebs));
        eq("ebs cinema in et", true, in(ix, "et", ebsCinema));
        eq("ebs not us", false, in(ix, "us", ebs) || in(ix, "us", ebsCinema));
        eq("48 hours ca", true, in(ix, "ca", ca) && !in(ix, "us", ca));
        eq("5 cops uk", true, in(ix, "uk", cops) && !in(ix, "us", cops));
        eq("andromeda italy", true, in(ix, "it", andr) && !in(ix, "us", andr));
        eq("red bull in us and ca", true, in(ix, "us", redbull) && in(ix, "ca", redbull) && !in(ix, "at", redbull));
        eq("MeTV stays us", true, in(ix, "us", metv));
        eq("no cloud data = id suffix", true, in(ix, "us", noHint));
        eq("us list = MeTV, Red Bull, Foo", 3, ix.countries.get("us").size());
        Index.relabel(Collections.singletonList(l), H); // idempotent
        eq("relabel twice", "br", br[0].country);
        eq("language set", "por", br[0].lang);
        Curated.of(l, new HashSet<>(Arrays.asList("ar")), new HashSet<>(Arrays.asList("Entertainment")));
        eq("for you finds a multi-country feed", true, Curated.of(l, new HashSet<>(Arrays.asList("py")), new HashSet<>(Arrays.asList("Entertainment"))).contains(amc));
    }

    // ---- Free-TV (tvg-country + group-title headings) ----
    static void freeTv() throws Exception {
        String m3u = "#EXTM3U\n"
            + "#EXTINF:-1 tvg-id=\"BBCTwo.uk\" tvg-country=\"GB\" group-title=\"UK\",BBC Two\u24BC\nhttp://x/1.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"EuronewsGreek.fr\" tvg-country=\"GR\" group-title=\"Greece\",Euronews Greek\nhttp://x/2.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"PeerTV.de\" tvg-country=\"IT\" group-title=\"Italy\",Peer TV Südtirol\nhttp://x/3.m3u8\n"
            + "#EXTINF:-1 tvg-name=\"Pluto TV Spotlight\" group-title=\"VOD Movies (EN)\",Pluto TV Spotlight\nhttp://x/4.m3u8\n"
            + "#EXTINF:-1 tvg-name=\"X\" tvg-country=\"BE\" group-title=\"Belgium\",Arte Belgique\nhttp://x/5.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"Kanali7.al\" group-title=\"Albania\",Kanali 7\nhttp://x/6.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"Y.us\" tvg-country=\"ZZZ\" group-title=\"USA\",Y\nhttp://x/7.m3u8\n";
        List<Channel> l = M3uParser.parse(new StringReader(m3u));
        eq("GB -> uk", "uk", l.get(0).country);
        eq("tvg-country beats the id suffix", "gr", l.get(1).country);
        eq("tvg-country Italy for a .de id", "it", l.get(2).country);
        eq("no id, no country", "", Normalizer.apply(l.get(3), "free").country);
        eq("no id, tvg-country only", "be", l.get(4).country);
        eq("id suffix without tvg-country", "al", l.get(5).country);
        eq("3-letter tvg-country ignored", "us", l.get(6).country);
    }

    // ---- T36 ----
    static Channel g(String id, String name, String cc, String lang, String url, String... groups) {
        Channel c = ch(id, name, cc, url, groups.length == 0 ? new String[]{"News"} : groups);
        c.lang = lang;
        return c;
    }

    static int n(Channel... cs) { return Dedup.fold(new ArrayList<>(Arrays.asList(cs)), (Ranker.Signals) null, null, false).size(); }

    static void grouping() {
        // same brand, same country: region/quality markers and Free-TV decorations do not split it
        eq("(Country) tag same country", 1, n(g("", "ETV (Estonia)", "ee", "", "a"), g("", "ETV", "ee", "", "b")));
        eq("trailing own country", 1, n(g("", "48 Hours CA", "ca", "", "a"), g("", "48 Hours", "ca", "", "b")));
        eq("Int'l = International", 1, n(g("", "CNN Int'l", "us", "", "a"), g("", "CNN International", "us", "", "b")));
        eq("Nick Jr. = NickJr", 1, n(g("", "Nick Jr.", "us", "", "a"), g("", "NickJr", "us", "", "b")));
        eq("free-tv symbols and quality", 1, n(g("", "Kanali 7 \u24C8 (720p)", "al", "", "a"), g("", "Kanali 7 HD", "al", "", "b")));
        eq("different country stays apart without language", 2, n(g("", "Nick Jr. (India)", "in", "", "a"), g("", "Nick Jr. (United States)", "us", "", "b")));
        // same language, other country: only when a trailing region marker shows it is a regional version
        eq("Hope Channel / India", 1, n(g("HopeChannel.us@SD", "Hope Channel", "us", "eng", "a"), g("HopeChannelIndia.in@SD", "Hope Channel India", "in", "eng", "b")));
        eq("Dunya News / UK", 1, n(g("", "Dunya News", "pk", "urd", "a"), g("", "Dunya News UK", "uk", "urd", "b")));
        eq("Las Estrellas / Latin America", 1, n(g("", "Las Estrellas", "mx", "spa", "a"), g("", "Las Estrellas Latin America", "latam", "spa", "b")));
        eq("MBC Masr / USA", 1, n(g("", "MBC Masr", "eg", "ara", "a"), g("", "MBC Masr USA", "us", "ara", "b")));
        eq("undefined category is compatible", 1, n(g("", "Hope Channel", "us", "eng", "a", "Religious"), g("", "Hope Channel UK", "uk", "eng", "b", "Undefined")));
        eq("different category apart", 2, n(g("", "Alpha", "us", "eng", "a", "Kids"), g("", "Alpha UK", "uk", "eng", "b", "Sports")));
        // must stay apart
        eq("Canal 13 (Chile) / (Guatemala)", 2, n(g("", "Canal 13 (Chile)", "cl", "spa", "a"), g("", "Canal 13 (Guatemala)", "gt", "spa", "b")));
        eq("generic name + country word", 2, n(g("", "Canal 13", "cl", "spa", "a"), g("", "Canal 13 Guatemala", "gt", "spa", "b")));
        eq("Channel 5 stays apart", 2, n(g("", "Channel 5", "sg", "eng", "a"), g("", "Channel 5 UK", "uk", "eng", "b")));
        eq("other language apart", 2, n(g("", "ATN Bangla", "bd", "eng", "a"), g("", "ATN Bangla UK", "uk", "ben", "b")));
        eq("unknown language apart", 2, n(g("", "Dunya News", "pk", "", "a"), g("", "Dunya News UK", "uk", "", "b")));
        eq("short brand apart", 2, n(g("", "MTV", "us", "eng", "a"), g("", "MTV Guyana", "gy", "eng", "b")));
        eq("Star TV is not Star", 2, n(g("", "Star TV", "tr", "", "a"), g("", "Star", "tr", "", "b")));
        eq("Sky Sports 1 / 2", 2, n(g("", "Sky Sports 1", "uk", "eng", "a"), g("", "Sky Sports 2", "uk", "eng", "b")));
        eq("brand keeps real words", "dunyanews", Dedup.brand(g("", "Dunya News UK", "uk", "urd", "a")));
        eq("brand of a parenthetical tag", "nickjr", Dedup.brand(g("", "Nick Jr. (India)", "in", "", "a")));
        // the representative is still picked by the usual rules and versions list all
        List<Channel> f = Dedup.fold(new ArrayList<>(Arrays.asList(g("", "Dunya News UK", "uk", "urd", "b"), g("", "Dunya News", "pk", "urd", "a"))), (Ranker.Signals) null, null, false);
        eq("versions of a merged row", 2, Dedup.versions(f.get(0)).size());
    }

    public static void main(String[] a) throws Exception {
        countries(); freeTv(); grouping();
        System.out.println(checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
