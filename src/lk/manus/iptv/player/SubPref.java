package lk.manus.iptv.player;

import java.util.List;
import java.util.Locale;

/** T40: which subtitle track to turn on. English first (en / eng / en-*), then any other track (it gets translated to English), then a CC track. */
public final class SubPref {
    private SubPref() { }

    /** Two-letter lowercase language ("hi") from "hi", "hin", "hi-IN", "pt_BR"; "" when missing or "und". */
    public static String norm(String tag) {
        if (tag == null) return "";
        String t = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int d = t.indexOf('-');
        if (d > 0) t = t.substring(0, d);
        if (t.equals("und") || t.equals("zxx")) return "";
        if (t.length() == 3) for (String c : Locale.getISOLanguages()) { // ISO 639-2 ("eng", "hin")
            try { if (new Locale(c).getISO3Language().equals(t)) return c; } catch (Exception ignored) { }
        }
        return t;
    }

    public static boolean isEnglish(String tag) { return norm(tag).equals("en"); }

    /** Index into tracks, -1 when there are none. English text track, English CC, first other text track, first CC track. */
    public static int pick(List<Engine.Track> t) {
        int enCc = -1, other = -1, otherCc = -1;
        for (int i = 0; i < t.size(); i++) {
            Engine.Track x = t.get(i);
            boolean cc = x.mime.contains("cea-");
            if (isEnglish(x.lang)) { if (!cc) return i; if (enCc < 0) enCc = i; }
            else if (cc) { if (otherCc < 0) otherCc = i; }
            else if (other < 0) other = i;
        }
        return enCc >= 0 ? enCc : other >= 0 ? other : otherCc >= 0 ? otherCc : -1;
    }
}
