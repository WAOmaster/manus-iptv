package lk.manus.iptv.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Streaming M3U parser. One pass, no regex, no per-line allocation beyond the substrings it keeps.
 * Malformed entries are skipped, never thrown.
 */
public final class M3uParser {
    private static final String EXTINF = "#EXTINF:";
    private static final String VLCOPT = "#EXTVLCOPT:";

    private M3uParser() {}

    public static List<Channel> parse(Reader r) throws IOException {
        BufferedReader br = r instanceof BufferedReader ? (BufferedReader) r : new BufferedReader(r);
        List<Channel> out = new ArrayList<>(12000);
        Pending pending = null;
        boolean first = true;
        String line;
        while ((line = br.readLine()) != null) {
            if (first) {
                first = false;
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1); // BOM
            }
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith(EXTINF)) {
                pending = parseExtinf(line); // a new EXTINF drops any entry still waiting for its URL
            } else if (line.charAt(0) == '#') {
                if (pending != null && line.startsWith(VLCOPT)) pending.option(line);
            } else if (pending != null) {
                if (isStreamUrl(line)) out.add(pending.toChannel(line));
                pending = null;
            }
        }
        return out;
    }

    /** Scans one #EXTINF line: attributes up to the first comma outside quotes, then the display name. */
    private static Pending parseExtinf(String line) {
        int comma = -1;
        boolean quoted = false;
        for (int i = EXTINF.length(); i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { comma = i; break; }
        }
        if (comma < 0) comma = line.indexOf(',', EXTINF.length()); // unbalanced quote: fall back to first comma
        if (comma < 0) return null;

        Pending p = new Pending();
        int i = EXTINF.length();
        while (i < comma) {
            while (i < comma && line.charAt(i) == ' ') i++;
            int ks = i;
            while (i < comma && line.charAt(i) != '=' && line.charAt(i) != ' ') i++;
            int ke = i;
            if (i >= comma || line.charAt(i) != '=') continue; // bare token such as the "-1" duration
            i++;
            int vs;
            int ve;
            if (i < comma && line.charAt(i) == '"') {
                vs = ++i;
                while (i < comma && line.charAt(i) != '"') i++;
                ve = i;
                if (i < comma) i++; // closing quote
            } else {
                vs = i;
                while (i < comma && line.charAt(i) != ' ') i++;
                ve = i;
            }
            if (keyIs(line, ks, ke, "tvg-id")) p.id = line.substring(vs, ve);
            else if (keyIs(line, ks, ke, "tvg-logo")) p.logo = line.substring(vs, ve);
            else if (keyIs(line, ks, ke, "group-title")) p.group = line.substring(vs, ve);
            else if (keyIs(line, ks, ke, "tvg-country")) p.cc = line.substring(vs, ve);
            else if (keyIs(line, ks, ke, "http-user-agent")) p.ua = line.substring(vs, ve);
            else if (keyIs(line, ks, ke, "http-referrer")) p.ref = line.substring(vs, ve);
        }
        p.name = line.substring(comma + 1).trim();
        return p;
    }

    private static boolean keyIs(String line, int start, int end, String key) {
        return end - start == key.length() && line.regionMatches(start, key, 0, key.length());
    }

    private static boolean isStreamUrl(String u) {
        return u.regionMatches(true, 0, "http://", 0, 7)
                || u.regionMatches(true, 0, "https://", 0, 8)
                || u.regionMatches(true, 0, "rtmp://", 0, 7)
                || u.regionMatches(true, 0, "rtsp://", 0, 7);
    }

    /** True if s[from..to) looks like a quality tag: 3+ digits then p or i (720p), or "4K". */
    private static boolean isQuality(String s, int from, int to) {
        int d = from;
        while (d < to && s.charAt(d) >= '0' && s.charAt(d) <= '9') d++;
        int digits = d - from;
        if (digits == 0 || to - d != 1) return false;
        char c = Character.toLowerCase(s.charAt(d));
        return (digits >= 3 && (c == 'p' || c == 'i')) || (digits == 1 && c == 'k');
    }

    /** Lowercase 2-letter code between the last '.' and '@' of the tvg-id, or "". */
    private static String countryOf(String id) {
        int end = id.indexOf('@');
        if (end < 0) end = id.length();
        int dot = id.lastIndexOf('.', end - 1);
        if (dot < 0 || end - dot - 1 != 2) return "";
        char a = id.charAt(dot + 1);
        char b = id.charAt(dot + 2);
        if (!isLetter(a) || !isLetter(b)) return "";
        return id.substring(dot + 1, end).toLowerCase(Locale.ROOT);
    }

    /** First code of a tvg-country attribute (Free-TV: "GB", "IT"; others may list several) as lowercase, "gb" -> "uk"; "" if not a 2-letter code. */
    static String tvgCountry(String v) {
        if (v.length() < 2 || !isLetter(v.charAt(0)) || !isLetter(v.charAt(1)) || (v.length() > 2 && isLetter(v.charAt(2)))) return "";
        String c = v.substring(0, 2).toLowerCase(Locale.ROOT);
        return c.equals("gb") ? "uk" : c;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static String[] splitGroups(String g) {
        ArrayList<String> list = new ArrayList<>(2);
        int start = 0;
        for (int i = 0; i <= g.length(); i++) {
            if (i == g.length() || g.charAt(i) == ';') {
                String piece = g.substring(start, i).trim();
                if (!piece.isEmpty() && !piece.equals("Undefined")) list.add(piece);
                start = i + 1;
            }
        }
        return list.toArray(new String[0]);
    }

    /** An entry between its #EXTINF line and its URL line. */
    private static final class Pending {
        String id = "", logo = "", group = "", name = "", ua = "", ref = "", cc = "";

        void option(String line) {
            int eq = line.indexOf('=', VLCOPT.length());
            if (eq < 0) return;
            String key = line.substring(VLCOPT.length(), eq).trim();
            String val = line.substring(eq + 1).trim();
            if (val.isEmpty()) return;
            if (key.equals("http-user-agent")) ua = val;
            else if (key.equals("http-referrer")) ref = val;
        }

        Channel toChannel(String url) {
            String quality = "";
            String s = name;
            boolean changed = true;
            while (changed) {
                changed = false;
                int n = s.length();
                if (n > 0 && s.charAt(n - 1) == ']' && s.lastIndexOf('[') >= 0) {
                    s = s.substring(0, s.lastIndexOf('[')).trim();
                    changed = true;
                } else if (n > 0 && s.charAt(n - 1) == ')') {
                    int open = s.lastIndexOf('(');
                    if (open >= 0 && isQuality(s, open + 1, n - 1)) {
                        if (quality.isEmpty()) quality = s.substring(open + 1, n - 1);
                        s = s.substring(0, open).trim();
                        changed = true;
                    }
                }
            }
            String country = tvgCountry(cc);
            return new Channel(id, s, quality, logo, splitGroups(group), country.isEmpty() ? countryOf(id) : country, url, ua, ref);
        }
    }
}
