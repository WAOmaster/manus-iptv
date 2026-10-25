import java.util.*;
import lk.manus.iptv.player.*;

public class SubTrTest {
    static int fails = 0;
    static void eq(Object a, Object b, String n) { if (!Objects.equals(a, b)) { fails++; System.out.println("FAIL " + n + ": " + a + " != " + b); } }
    static Engine.Track t(String mime, String lang) { Engine.Track x = new Engine.Track(); x.mime = mime; x.lang = lang; return x; }

    public static void main(String[] a) {
        eq(SubPref.norm("eng"), "en", "eng"); eq(SubPref.norm("hin"), "hi", "hin"); eq(SubPref.norm("pt-BR"), "pt", "pt-BR");
        eq(SubPref.norm("und"), "", "und"); eq(SubPref.norm(null), "", "null"); eq(SubPref.norm("EN_us"), "en", "EN_us");
        List<Engine.Track> l = new ArrayList<>();
        eq(SubPref.pick(l), -1, "empty");
        l.add(t("application/cea-608", "")); eq(SubPref.pick(l), 0, "cc only");
        l.add(t("text/vtt", "hi")); eq(SubPref.pick(l), 1, "text beats cc");
        l.add(t("text/vtt", "ru")); eq(SubPref.pick(l), 1, "first other");
        l.add(t("application/cea-608", "en")); eq(SubPref.pick(l), 3, "english cc beats other text");
        l.add(t("text/vtt", "eng")); eq(SubPref.pick(l), 4, "english text wins");
        CueCache c = new CueCache(2);
        c.put("hi", "a", "A"); c.put("hi", "b", "B"); c.get("hi", "a"); c.put("hi", "c", "C");
        eq(c.get("hi", "b"), null, "lru evicts oldest"); eq(c.get("hi", "a"), "A", "lru keeps used"); eq(c.get("ru", "a"), null, "lang in key"); eq(c.size(), 2, "size");
        System.out.println(fails == 0 ? "SubTrTest ok" : "SubTrTest FAILED " + fails);
        if (fails > 0) System.exit(1);
    }
}
