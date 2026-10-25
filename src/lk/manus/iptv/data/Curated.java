package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure function: the M1 "curated" channel list. */
public final class Curated {
    public static final List<String> DEFAULT_COUNTRIES = java.util.Arrays.asList("lk", "in", "uk", "us");
    public static final List<String> DEFAULT_CATS = java.util.Arrays.asList("News", "Kids", "Sports", "Movies", "Entertainment");

    private Curated() {}

    /** Pinned countries' channels in the chosen (merged) categories; lk first, then group, name. */
    public static List<Channel> of(List<Channel> all, Set<String> countries, Set<String> groups) {
        List<Channel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Channel c : all) {
            if (!inCountries(c, countries) || !hasGroup(c, groups)) continue;
            if (seen.add(c.url)) out.add(c);
        }
        Collections.sort(out, new Comparator<Channel>() { // List.sort is stable
            @Override public int compare(Channel a, Channel b) {
                boolean la = a.country.equals("lk"), lb = b.country.equals("lk");
                if (la != lb) return la ? -1 : 1;
                int g = group(a).compareToIgnoreCase(group(b));
                return g != 0 ? g : a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
            }
        });
        return out;
    }

    private static boolean inCountries(Channel c, Set<String> countries) {
        if (countries.contains(c.country)) return true;
        if (c.areas != null) for (String a : c.areas) if (countries.contains(a)) return true;
        return false;
    }

    private static boolean hasGroup(Channel c, Set<String> groups) {
        for (String g : c.groups) if (groups.contains(Index.merged(g))) return true;
        return c.groups.length == 0 && groups.contains("Other");
    }

    private static String group(Channel c) { return c.groups.length > 0 ? Index.merged(c.groups[0]) : "Other"; }
}
