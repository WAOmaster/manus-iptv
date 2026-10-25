import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Normalizer;

/** Run from the project folder: java -cp build/test NormalizerTest */
public class NormalizerTest {
    private static int checks, failures;

    private static void eq(String what, String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) { failures++; System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]"); }
    }

    private static void cat(String group, String expected) { eq("category(" + group + ")", expected, Normalizer.category(group)); }
    private static void cc(String group, String expected) { eq("country(" + group + ")", expected, Normalizer.country(group)); }
    private static void name(String n, String expected) {
        String r = Normalizer.fromName(n);
        eq("fromName(" + n + ")", expected, r == null ? "null" : r);
    }

    public static void main(String[] args) {
        cat("UK | MOVIES", "Movies");
        cat("VOD - Action", "Movies");
        cat("Films", "Movies");
        cat("Cinema", "Movies");
        cat("SPORTS HD", "Sports");
        cat("Sport", "Sports");
        cat("Football", "Sports");
        cat("Cricket", "Sports");
        cat("Kids & Family", "Kids");
        cat("Cartoons", "Kids");
        cat("Hindi Movies", "Movies");
        cat("IN: Hindi News", "News");
        cat("24/7 Series", "TV Series");
        cat("Music Hits", "Music");
        cat("Doc", "Documentary");
        cat("Nat Geo", "Documentary");
        cat("National Geographic", "Documentary");
        cat("Séries", "TV Series");
        cat("Películas", "Movies");
        cat("[LK] Sinhala Entertainment", "Entertainment");
        cat("Anime", "Animation");
        cat("Stand-up Comedy", "Comedy");
        cat("Cooking & Food", "Cooking");
        cat("Travel", "Travel");
        cat("Business News", "News");
        cat("Finance", "Business");
        cat("Weather", "Weather");
        cat("Teleshopping", "Shop");
        cat("Education", "Education");
        cat("Islamic", "Religious");
        cat("Fashion & Lifestyle", "Lifestyle");
        cat("Classic Movies", "Movies");
        cat("Classics", "Classic");
        cat("Outdoor", "Outdoor");
        cat("Motors", "Auto");
        cat("Parliament", "Legislative");
        cat("General", "General");
        cat("Undefined", "Other");
        cat("", "Other");
        cat("Random Folder 7", "Other");
        cat("Sports News", "Sports");
        cat("Kids Movies", "Kids");
        cat("Musical Specials", "Music");
        cat("Documentaries HD", "Documentary");

        cc("UK | MOVIES", "uk");
        cc("|UK| Sports", "uk");
        cc("IN: Hindi News", "in");
        cc("[LK] Sinhala Entertainment", "lk");
        cc("US-News", "us");
        cc("Movies | UK", "uk");
        cc("News [LK]", "lk");
        cc("Sri Lanka Music", "lk");
        cc("United Kingdom", "uk");
        cc("GB | Drama", "uk");
        cc("Tamil Movies", "");
        cc("Sinhala", "");
        cc("Hindi Movies", "");
        cc("TV - Series", "");
        cc("AR | Arabic", "");
        cc("in: lowercase is not a code", "");
        cc("Sports HD", "");
        cc("", "");

        name("CNN International", "News");
        name("BBC News", "News");
        name("Movie Channel 1", "Movies");
        name("Star Cinema", "Movies");
        name("Cartoon Network", "Kids");
        name("Disney Junior", "Kids");
        name("ESPN 2", "Sports");
        name("Cricket Live", "Sports");
        name("MTV Hits", "Music");
        name("Hiru TV", "null");

        // whole-channel apply: group wins, country from the group, folders kept as written
        Channel c = new Channel("", "Zee Cinema", "", "", new String[]{"IN: Hindi Movies"}, "", "http://h/x", "", "");
        Channel n = Normalizer.apply(c, "s1");
        eq("apply.group", "Movies", n.groups[0]);
        eq("apply.country", "in", n.country);
        eq("apply.folder", "IN: Hindi Movies", n.folders[0]);
        eq("apply.src", "s1", n.src);
        Channel e = Normalizer.apply(new Channel("", "Kids Fun", "", "", new String[0], "", "http://h/y", "", ""), "s1");
        eq("apply.nameFallback", "Kids", e.groups[0]);
        Channel k = Normalizer.apply(new Channel("x.lk", "Foo", "", "", new String[]{"[UK] News"}, "lk", "http://h/z", "", ""), "s1");
        eq("apply.keepsTvgCountry", "lk", k.country);

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
