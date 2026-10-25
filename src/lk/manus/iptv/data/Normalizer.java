package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java. Maps arbitrary playlist group-titles ("UK | MOVIES", "VOD - Action", "SPORTS HD") onto the standard
 * categories and pulls an explicit country code out of prefixes/suffixes ("UK |", "IN:", "[LK]", "US-").
 * Languages are not countries: "Tamil" or "Hindi" never produce a country.
 */
public final class Normalizer {
    /** Lower index wins when a title matches several categories ("Kids Movies" -> Kids, "Sports News" -> Sports). */
    private static final String[][] SYN = {
        {"Kids", "kids", "kid", "children", "childrens", "cartoon", "cartoons", "junior", "toons", "nick", "nickelodeon", "disney", "boomerang"},
        {"Sports", "sport", "sports", "football", "soccer", "cricket", "tennis", "golf", "nba", "nfl", "nhl", "mlb", "ufc", "wwe",
            "boxing", "racing", "f1", "espn", "eurosport", "fifa", "rugby", "basketball", "baseball", "wrestling", "hockey", "olympics"},
        {"Movies", "movie", "movies", "film", "films", "cinema", "cinemas", "action", "thriller", "horror", "scifi", "bollywood",
            "kino", "peliculas", "filme", "filmes"},
        {"TV Series", "series", "serie", "soap", "soaps", "telenovela", "telenovelas", "novelas", "sitcom", "sitcoms", "dizi"},
        {"News", "news", "noticias", "nachrichten", "headlines", "cnn", "noticias"},
        {"Music", "music", "musica", "musique", "hits", "mtv", "vevo", "concert", "concerts", "karaoke", "clips"},
        {"Documentary", "doc", "docs", "documentary", "documentaries", "documentaire", "discovery", "history", "wildlife", "nature",
            "natgeo", "biography"},
        {"Animation", "animation", "animations", "animated", "anime"},
        {"Comedy", "comedy", "comedies", "humor", "humour", "standup", "funny"},
        {"Cooking", "cooking", "cook", "food", "kitchen", "recipes", "chef"},
        {"Travel", "travel", "tourism", "tourist"},
        {"Business", "business", "finance", "financial", "money", "markets", "economy", "bloomberg", "cnbc"},
        {"Science", "science", "tech", "technology", "space"},
        {"Weather", "weather", "meteo"},
        {"Shop", "shop", "shopping", "teleshopping", "qvc", "hsn"},
        {"Education", "education", "educational", "learning", "learn", "school", "university", "edu"},
        {"Religious", "religious", "religion", "islam", "islamic", "christian", "church", "gospel", "hindu", "buddhist", "bible",
            "quran", "catholic", "faith"},
        {"Lifestyle", "lifestyle", "fashion", "living", "health", "wellness", "beauty"},
        {"Culture", "culture", "cultural", "arts", "art"},
        {"Classic", "classic", "classics", "retro", "oldies", "vintage"},
        {"Family", "family", "families"},
        {"Outdoor", "outdoor", "outdoors", "hunting", "fishing", "adventure"},
        {"Auto", "auto", "autos", "motor", "motors", "cars", "car", "automotive"},
        {"Legislative", "legislative", "parliament", "government", "congress", "senate", "assembly"},
        {"Entertainment", "entertainment", "entertain", "shows", "show", "reality", "drama"},
        {"General", "general", "generalist", "variety", "mixed", "mix"},
    };
    private static final String[][] PHRASES = {
        {" nat geo ", "Documentary"}, {" national geographic ", "Documentary"}, {" tv series ", "TV Series"},
        {" tv shows ", "TV Series"}, {" talk show ", "Entertainment"}, {" talk shows ", "Entertainment"},
        {" bbc news ", "News"}, {" kids family ", "Kids"},
    };
    private static final String[] STEMS = {"sport", "movie", "music", "cinem", "docum", "entert", "comed", "educ", "relig", "weath",
        "busin", "scien", "anima", "cartoo"};
    private static final String[] STEM_CAT = {"Sports", "Movies", "Music", "Movies", "Documentary", "Entertainment", "Comedy",
        "Education", "Religious", "Weather", "Business", "Science", "Animation", "Kids"};
    private static final Set<String> OTHER = new HashSet<>(java.util.Arrays.asList("other", "others", "undefined", "uncategorized",
        "uncategorised", "unknown", "misc", "miscellaneous"));

    private static final Map<String, Integer> TOK = new HashMap<>();
    private static final Map<String, Integer> PRIO = new HashMap<>();
    private static final String[] COUNTRY_NAMES = {
        "sri lanka", "lk", "srilanka", "lk", "united kingdom", "uk", "great britain", "uk", "uk", "uk", "usa", "us", "united states", "us",
        "india", "in", "pakistan", "pk", "bangladesh", "bd", "nepal", "np", "canada", "ca", "australia", "au", "germany", "de",
        "france", "fr", "italy", "it", "spain", "es", "portugal", "pt", "brazil", "br", "turkey", "tr", "russia", "ru", "china", "cn",
        "japan", "jp", "south korea", "kr", "korea", "kr", "saudi arabia", "sa", "egypt", "eg", "qatar", "qa", "kuwait", "kw",
        "iran", "ir", "iraq", "iq", "israel", "il", "netherlands", "nl", "belgium", "be", "sweden", "se", "norway", "no",
        "denmark", "dk", "finland", "fi", "poland", "pl", "greece", "gr", "ukraine", "ua", "romania", "ro", "mexico", "mx",
        "argentina", "ar", "colombia", "co", "chile", "cl", "peru", "pe", "indonesia", "id", "malaysia", "my", "singapore", "sg",
        "thailand", "th", "vietnam", "vn", "philippines", "ph", "new zealand", "nz", "south africa", "za", "nigeria", "ng",
        "kenya", "ke", "ireland", "ie", "switzerland", "ch", "austria", "at", "albania", "al", "uae", "ae", "united arab emirates", "ae",
    };
    private static final Set<String> ISO = new HashSet<>(java.util.Arrays.asList(Locale.getISOCountries()));

    static {
        for (int i = 0; i < SYN.length; i++) {
            PRIO.put(SYN[i][0], i);
            for (int j = 1; j < SYN[i].length; j++) TOK.put(SYN[i][j], i);
        }
        ISO.add("UK");
        ISO.remove("AR"); // GB is mapped to uk by code(); "AR"/"TV" are Arabic / television far more often than Argentina / Tuvalu
        ISO.remove("TV");
    }

    private Normalizer() {}

    // ---- public API ----

    /** Standard category for a group-title, "Other" if nothing matches. */
    public static String category(String group) {
        String c = match(tokens(group));
        return c == null ? "Other" : c;
    }

    /** Category guessed from a channel name, or null. */
    public static String fromName(String name) {
        return match(tokens(name));
    }

    /** Lowercase 2-letter code from an explicit country token in the group-title, "" if none ("gb" is returned as "uk"). */
    public static String country(String group) {
        if (group == null) return "";
        String s = group.trim();
        int n = s.length(), i = 0;
        while (i < n && "[(| ".indexOf(s.charAt(i)) >= 0) i++;
        if (i + 2 <= n && upper(s.charAt(i)) && upper(s.charAt(i + 1))) {
            int j = i + 2;
            while (j < n && s.charAt(j) == ' ') j++;
            if (j < n && "|:-])".indexOf(s.charAt(j)) >= 0) {
                String c = code(s.substring(i, i + 2));
                if (!c.isEmpty()) return c;
            }
        }
        // suffix: "... | UK", "... [LK]", "... (US)", "... - IN"
        int e = n;
        while (e > 0 && "]) ".indexOf(s.charAt(e - 1)) >= 0) e--;
        if (e >= 2 && upper(s.charAt(e - 1)) && upper(s.charAt(e - 2))) {
            int b = e - 2;
            while (b > 0 && s.charAt(b - 1) == ' ') b--;
            if (b > 0 && "|[(-:".indexOf(s.charAt(b - 1)) >= 0) {
                String c = code(s.substring(e - 2, e));
                if (!c.isEmpty()) return c;
            }
        }
        String t = " " + join(tokens(s)) + " ";
        for (int k = 0; k < COUNTRY_NAMES.length; k += 2) if (t.contains(" " + COUNTRY_NAMES[k] + " ")) return COUNTRY_NAMES[k + 1];
        return "";
    }

    /** Standard categories and country for one channel of a non-iptv-org source; the original group-titles stay in {@code folders}. */
    public static Channel apply(Channel c, String src) {
        List<String> cats = new ArrayList<>(2);
        String cc = c.country;
        for (String g : c.groups) {
            String l = category(g);
            if (!l.equals("Other") && !cats.contains(l)) cats.add(l);
            if (cc.isEmpty()) cc = country(g);
        }
        if (cats.isEmpty()) {
            String n = fromName(c.name);
            cats.add(n == null ? "Other" : n);
        }
        return new Channel(c.id, c.name, c.quality, c.logo, cats.toArray(new String[0]), cc, c.url, c.userAgent, c.referrer,
                src, c.groups);
    }

    // ---- helpers ----

    private static boolean upper(char ch) { return ch >= 'A' && ch <= 'Z'; }

    private static String code(String two) {
        if (two.equals("GB")) return "uk";
        if (!ISO.contains(two)) return "";
        return two.toLowerCase(Locale.ROOT);
    }

    private static String join(List<String> t) { return String.join(" ", t); }

    /** Lowercase, accent-free word list; everything that is not a letter or digit separates words. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String f = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i <= f.length(); i++) {
            char ch = i < f.length() ? f.charAt(i) : ' ';
            if (Character.getType(ch) == Character.NON_SPACING_MARK) continue; // accents
            if (Character.isLetterOrDigit(ch)) b.append(ch);
            else if (b.length() > 0) { out.add(b.toString()); b.setLength(0); }
        }
        return out;
    }

    private static String match(List<String> toks) {
        if (toks.isEmpty()) return null;
        if (toks.size() == 1 && OTHER.contains(toks.get(0))) return "Other";
        int best = Integer.MAX_VALUE;
        String padded = " " + join(toks) + " ";
        for (String[] p : PHRASES) if (padded.contains(p[0])) best = Math.min(best, PRIO.get(p[1]));
        for (String t : toks) {
            Integer p = TOK.get(t);
            if (p == null && t.length() >= 5) {
                for (int i = 0; i < STEMS.length; i++) if (t.startsWith(STEMS[i])) { p = PRIO.get(STEM_CAT[i]); break; }
            }
            if (p != null && p < best) best = p;
        }
        return best == Integer.MAX_VALUE ? null : SYN[best][0];
    }
}
