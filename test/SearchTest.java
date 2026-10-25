import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Search;

/** Run from the project folder: java -cp build/test SearchTest */
public class SearchTest {
    private static int failures;

    private static Channel ch(String name, String country, String url) {
        return new Channel("", name, "", "", new String[0], country, url, "", "");
    }

    private static void eq(String what, String expected, String actual) {
        if (!expected.equals(actual)) { failures++; System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]"); }
    }

    private static String names(List<Channel> l) {
        StringBuilder b = new StringBuilder();
        for (Channel c : l) b.append(b.length() > 0 ? "," : "").append(c.name);
        return b.toString();
    }

    public static void main(String[] args) {
        List<Channel> all = Arrays.asList(ch("Al News", "", "u1"), ch("Daily News Now", "", "u2"), ch("News", "", "u3"),
            ch("Newsroom", "", "u4"), ch("Rupavahini", "lk", "u5"), ch("Télé Rupa", "", "u6"), ch("Sky News", "lk", "u7"));
        String[] f = new String[all.size()];
        for (int i = 0; i < f.length; i++) f[i] = Search.fold(all.get(i).name);
        Set<String> favs = new HashSet<>();
        eq("tiers", "News,Newsroom,Sky News,Al News,Daily News Now", names(Search.run(all, f, "news", favs)));
        eq("accent+case", "Rupavahini,Télé Rupa", names(Search.run(all, f, "RUPA", favs)));
        eq("accent query", "Télé Rupa", names(Search.run(all, f, "tele", favs)));
        favs.add("u2");
        eq("fav boost", "News,Newsroom,Daily News Now,Sky News,Al News", names(Search.run(all, f, "news", favs)));
        favs.add("u1");
        eq("multi token", "Daily News Now", names(Search.run(all, f, "now daily", new HashSet<String>())));
        eq("empty", "", names(Search.run(all, f, "  ", favs)));
        System.out.println(failures == 0 ? "SearchTest ok" : failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
