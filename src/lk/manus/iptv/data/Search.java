package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure Java channel-name search: case and accent insensitive, exact > prefix > word-prefix > substring, lk and favourites boosted. */
public final class Search {
    public static final int MAX = 200;

    private Search() {}

    /** Lowercase with accents removed. */
    public static String fold(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 127) { ascii = false; break; }
        if (ascii) return s.toLowerCase(Locale.ROOT);
        String d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) if (Character.getType(d.charAt(i)) != Character.NON_SPACING_MARK) b.append(d.charAt(i));
        return b.toString().toLowerCase(Locale.ROOT);
    }

    /** {@code folded[i]} must be fold(all.get(i).name). Ties keep the order of {@code all} (name order). */
    public static List<Channel> run(List<Channel> all, String[] folded, String query, Set<String> favs) {
        String q = fold(query).trim().replaceAll("\\s+", " ");
        List<Channel> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        String[] toks = q.split(" ");
        long[] keys = new long[all.size()];
        int n = 0;
        for (int i = 0; i < folded.length; i++) {
            String f = folded[i];
            int tier, at = f.indexOf(q);
            if (at < 0) {
                tier = 4;
                for (String t : toks) if (!f.contains(t)) { tier = -1; break; }
                if (tier < 0) continue;
            } else if (at == 0) {
                tier = f.length() == q.length() ? 0 : 1;
            } else {
                tier = 3; // substring, unless some hit starts a word
                for (int j = at; j > 0; j = f.indexOf(q, j + 1)) if (f.charAt(j - 1) == ' ') { tier = 2; break; }
            }
            Channel c = all.get(i);
            int score = tier * 4 - (favs.contains(c.url) ? 2 : 0) - (c.country.equals("lk") ? 1 : 0) + 3; // +3 keeps it positive
            keys[n++] = ((long) score << 32) | i;
        }
        Arrays.sort(keys, 0, n);
        for (int i = 0; i < n && i < MAX; i++) out.add(all.get((int) (keys[i] & 0xFFFFFFFFL)));
        return out;
    }
}
