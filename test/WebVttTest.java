import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import lk.manus.iptv.player.WebVtt;

/** Run from the project folder: javac -d vtt-tmp src/lk/manus/iptv/player/WebVtt.java test/WebVttTest.java && java -cp vtt-tmp WebVttTest */
public class WebVttTest {
    private static int checks, failures;

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures++;
            System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]");
        }
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** "start-end text | " per cue, newlines shown as literal \n. */
    static String dump(List<WebVtt.Cue> cs) {
        StringBuilder sb = new StringBuilder();
        for (WebVtt.Cue c : cs) sb.append(c.startUs).append('-').append(c.endUs).append(' ').append(c.text.replace("\n", "\\n")).append(" | ");
        return sb.toString();
    }

    public static void main(String[] args) {
        // 1 basic
        eq("basic", "1000000-2500000 Hello | ", dump(WebVtt.parse(b("WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello\n\n"))));

        // 2 hours optional
        eq("hours and no-hours", "1000000-2000000 A | 3600000000-3601000000 B | ",
            dump(WebVtt.parse(b("WEBVTT\n\n00:01.000 --> 00:02.000\nA\n\n01:00:00.000 --> 01:00:01.000\nB\n\n"))));

        // 3 cue identifiers
        eq("identifiers", "0-1000000 A | 1000000-2000000 B | ",
            dump(WebVtt.parse(b("WEBVTT\n\nintro\n00:00.000 --> 00:01.000\nA\n\n1\n00:01.000 --> 00:02.000\nB\n\n"))));

        // 4 multi-line text
        eq("multi-line", "0-1000000 line one\\nline two | ",
            dump(WebVtt.parse(b("WEBVTT\n\n00:00.000 --> 00:01.000\nline one\nline two\n\n"))));

        // 5 tags stripped
        eq("tags", "0-2000000 bold it y S: k | ", dump(WebVtt.parse(b(
            "WEBVTT\n\n00:00.000 --> 00:02.000\n<b>bold</b> <i>it</i> <c.yellow>y</c> <v Speaker>S:</v> <00:00:01.500>k\n\n"))));

        // 6 entities decoded once, encoded tags survive as text
        eq("entities", "0-1000000 a & b <i> x\u00A0\u200Ey\u200F\\n&lt; | ", dump(WebVtt.parse(b(
            "WEBVTT\n\n00:00.000 --> 00:01.000\na &amp; b &lt;i&gt; x&nbsp;&lrm;y&rlm;\n&amp;lt;\n\n"))));

        // 7 NOTE / STYLE / REGION skipped
        eq("note style region skipped", "2000000-3000000 Keep | ", dump(WebVtt.parse(b(
            "WEBVTT\n\nNOTE this is a note\n00:00.000 --> 00:01.000 in note\n\nSTYLE\n::cue { color: red }\n\n"
            + "REGION\nid:r\nwidth:40%\n\n00:02.000 --> 00:03.000\nKeep\n\n"))));

        // 8 CRLF, CR-only and LF line endings
        eq("CRLF", "0-1000000 X\\nY | 2000000-3000000 Z | ", dump(WebVtt.parse(b(
            "WEBVTT\r\n\r\n00:00.000 --> 00:01.000\r\nX\r\nY\r\n\r\n00:02.000 --> 00:03.000\r\nZ\r\n"))));
        eq("CR only", "0-1000000 Q | ", dump(WebVtt.parse(b("WEBVTT\r\r00:00.000 --> 00:01.000\rQ\r\r"))));

        // 9 UTF-8 BOM
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] withBom = new byte[bom.length + b("WEBVTT\n\n00:00.000 --> 00:01.000\nBOM\n").length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(b("WEBVTT\n\n00:00.000 --> 00:01.000\nBOM\n"), 0, withBom, bom.length, withBom.length - bom.length);
        eq("BOM", "0-1000000 BOM | ", dump(WebVtt.parse(withBom)));

        // 10 malformed timing skipped, valid cue after them kept
        eq("malformed timing skipped", "5000000-6000000 ok | ", dump(WebVtt.parse(b(
            "WEBVTT\n\n00:00.000 -> 00:01.000\nbad arrow\n\n00:00.000 --> 00:61.000\nbad seconds\n\n"
            + "00:00 --> 00:01.000\nno ms\n\n1:00.000 --> 1:01.000\nbad minutes\n\n00:05.000 --> 00:06.000\nok\n\n"))));

        // 11 empty, null, header only
        eq("empty", "", dump(WebVtt.parse(new byte[0])));
        eq("null", "", dump(WebVtt.parse(null)));
        eq("header only", "", dump(WebVtt.parse(b("WEBVTT\n\n"))));
        eq("whitespace only", "", dump(WebVtt.parse(b("   \n\n\t\n"))));

        // 12 X-TIMESTAMP-MAP offset applied to cues, and reported by timestampOffsetUs
        byte[] mapped = b("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n00:00:01.000 --> 00:00:02.000\nT\n\n");
        eq("map cues", "11000000-12000000 T | ", dump(WebVtt.parse(mapped)));
        eq("map offset", 10000000L, WebVtt.timestampOffsetUs(mapped));
        eq("map LOCAL after MPEGTS", 10000000L, WebVtt.timestampOffsetUs(b(
            "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:05.000,MPEGTS:1350000\n\n")));
        eq("map with BOM and 33-bit clock", 2040000000L, WebVtt.timestampOffsetUs(concat(bom, b(
            "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:183600000,LOCAL:00:00:00.000\n\n00:00:00.500 --> 00:00:01.000\nM\n\n"))));
        eq("map cue with BOM", "2040500000-2041000000 M | ", dump(WebVtt.parse(concat(bom, b(
            "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:183600000,LOCAL:00:00:00.000\n\n00:00:00.500 --> 00:00:01.000\nM\n\n")))));

        // 13 no map, malformed map, or map outside the header gives offset 0
        eq("no map offset", 0L, WebVtt.timestampOffsetUs(b("WEBVTT\n\n00:00.000 --> 00:01.000\nX\n\n")));
        eq("malformed map offset", 0L, WebVtt.timestampOffsetUs(b("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:abc,LOCAL:00:00:00.000\n\n")));
        eq("map after blank line ignored", 0L, WebVtt.timestampOffsetUs(b(
            "WEBVTT\n\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n")));

        // 14 settings after timing line
        eq("settings", "0-2000000 S | 3000000-4000000 T | ", dump(WebVtt.parse(b(
            "WEBVTT\n\n00:00.000 --> 00:02.000 align:start position:10%\nS\n\n00:03.000 --> 00:04.000\tline:0\nT\n\n"))));

        // 15 garbage never throws
        boolean threw = false;
        try {
            Random r = new Random(42);
            for (int k = 0; k < 300; k++) {
                byte[] junk = new byte[r.nextInt(400)];
                r.nextBytes(junk);
                WebVtt.parse(junk);
                WebVtt.timestampOffsetUs(junk);
            }
            WebVtt.parse(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, '-', '-', '>', (byte) 0xC3});
            WebVtt.parse(new byte[]{(byte) 0xC3});
            WebVtt.parse(b("00:00.000 --> 99:99.999\n\n\n-->\n\n"));
        } catch (RuntimeException e) {
            threw = true;
            e.printStackTrace();
        }
        eq("garbage no throw", false, threw);

        // 16 no header tolerated; lone '<' and '>' are text
        eq("no header, literal angle brackets", "0-1000000 1 < 2 & 3 > 2 x <b | ", dump(WebVtt.parse(b(
            "00:00.000 --> 00:01.000\n1 < 2 & 3 > 2 x <b\n\n"))));

        // 17 whitespace-only lines separate blocks; order kept
        eq("whitespace separators", "0-1000000 A | 2000000-3000000 B | ", dump(WebVtt.parse(b(
            "WEBVTT\n   \n\t\n00:00.000 --> 00:01.000\nA\n   \n00:02.000 --> 00:03.000\nB\n"))));

        int passed = checks - failures;
        System.out.println("WebVttTest: passed " + passed + "/" + checks + " checks");
        System.exit(failures == 0 ? 0 : 1);
    }

    static byte[] concat(byte[] a, byte[] c) {
        byte[] out = new byte[a.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(c, 0, out, a.length, c.length);
        return out;
    }
}
