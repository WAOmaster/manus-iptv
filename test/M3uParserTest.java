import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.M3uParser;

/** Run from the project folder: java -cp build/test M3uParserTest [path-to-full-playlist] */
public class M3uParserTest {
    private static int failures = 0;
    private static int checks = 0;

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!same(expected, actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected [" + show(expected) + "] got [" + show(actual) + "]");
        }
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof String[] && b instanceof String[]) return Arrays.equals((String[]) a, (String[]) b);
        return a == null ? b == null : a.equals(b);
    }

    private static String show(Object o) {
        return o instanceof String[] ? Arrays.toString((String[]) o) : String.valueOf(o);
    }

    private static void checkSample() throws Exception {
        List<Channel> ch;
        try (java.io.Reader r = Files.newBufferedReader(Paths.get("test/sample.m3u"), StandardCharsets.UTF_8)) {
            ch = M3uParser.parse(r);
        }
        eq("sample count", 8, ch.size());
        if (ch.size() < 8) return;

        Channel c = ch.get(0);
        eq("bbc id", "BBCNews.uk@SD", c.id);
        eq("bbc name", "BBC News", c.name);
        eq("bbc quality", "720p", c.quality);
        eq("bbc logo", "https://logo.example/bbc.png", c.logo);
        eq("bbc groups", new String[] {"News", "Public"}, c.groups);
        eq("bbc country", "uk", c.country);
        eq("bbc url", "https://example.com/bbc.m3u8", c.url);
        eq("bbc ua", "", c.userAgent);
        eq("bbc ref", "", c.referrer);

        c = ch.get(1);
        eq("plain id", "", c.id);
        eq("plain name", "Plain Channel", c.name);
        eq("plain quality", "", c.quality);
        eq("plain logo", "", c.logo);
        eq("plain groups", new String[0], c.groups);
        eq("plain country", "", c.country);
        eq("plain url", "http://example.com/plain.ts", c.url);

        c = ch.get(2);
        eq("comma name", "News, Sports \"Live\"", c.name);
        eq("comma id", "Foo.us", c.id);
        eq("comma country", "us", c.country);
        eq("comma groups (Undefined)", new String[0], c.groups);

        c = ch.get(3);
        eq("multi tag name", "Kids Channel", c.name);
        eq("multi tag quality", "", c.quality);
        eq("multi tag groups", new String[] {"Kids", "Music"}, c.groups);
        eq("multi tag country", "de", c.country);

        c = ch.get(4);
        eq("opt name", "Option Channel", c.name);
        eq("opt quality", "1080p", c.quality);
        eq("opt ua", "Mozilla/5.0 (X11; Linux) Test/1.0", c.userAgent);
        eq("opt ref", "https://example.com/", c.referrer);
        eq("opt url", "https://example.com/opt.m3u8", c.url);

        c = ch.get(5);
        eq("next name (no-URL entry dropped)", "Next Channel", c.name);
        eq("next country", "uk", c.country);

        c = ch.get(6);
        eq("geo name", "3Cat Exclusiu 1", c.name);
        eq("geo quality", "1080p", c.quality);
        eq("geo groups", new String[] {"News"}, c.groups);

        c = ch.get(7);
        eq("rtmp name", "Rtmp Channel", c.name);
        eq("rtmp groups (comma in quoted attr)", new String[] {"Sports, Live"}, c.groups);
        eq("rtmp url", "rtmp://example.com/live/stream", c.url);
    }

    private static void timeFull(String path) throws Exception {
        long t0 = System.nanoTime();
        int count;
        try (java.io.Reader r = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
            count = M3uParser.parse(r).size();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("full playlist: " + count + " channels parsed in " + ms + " ms");
    }

    public static void main(String[] args) throws Exception {
        checkSample();
        System.out.println("sample checks: " + checks + ", failures: " + failures);
        if (args.length > 0) timeFull(args[0]);
        else System.out.println("no playlist path given, timing skipped");
        System.exit(failures == 0 ? 0 : 1);
    }
}
