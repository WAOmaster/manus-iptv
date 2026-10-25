import java.util.List;

import lk.manus.iptv.player.Hls;

/** Run from the project folder: javac -d build/test -cp build/test src/lk/manus/iptv/player/Hls.java test/HlsTest.java && java -cp build/test HlsTest */
public class HlsTest {
    private static int failures;

    private static void eq(String what, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) { failures++; System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]"); }
    }

    static final String BASE = "http://h.example/live/master.m3u8?tok=1";
    static final String MASTER = "#EXTM3U\n#EXT-X-VERSION:3\n"
        + "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\nhd/index.m3u8\n"
        + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nhttp://cdn.example/sd.m3u8\n"
        + "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=900,BANDWIDTH=5000000,RESOLUTION=1920x1080\n/abs/fhd.m3u8\n";

    public static void main(String[] args) {
        List<Hls.Variant> v = Hls.parseMaster(MASTER, BASE);
        eq("count", 3, v.size());
        eq("sorted asc", "800000,2500000,5000000", v.get(0).bandwidth + "," + v.get(1).bandwidth + "," + v.get(2).bandwidth);
        eq("relative", "http://h.example/live/hd/index.m3u8", v.get(1).url);
        eq("absolute", "http://cdn.example/sd.m3u8", v.get(0).url);
        eq("root-relative", "http://h.example/abs/fhd.m3u8", v.get(2).url);
        eq("resolution", "1280x720", v.get(1).width + "x" + v.get(1).height);
        eq("codecs comma does not break", 2500000, v.get(1).bandwidth);
        eq("average-bandwidth ignored", 5000000, v.get(2).bandwidth);
        eq("pick best", 5000000, Hls.pick(v, "best").bandwidth);
        eq("pick saver", 800000, Hls.pick(v, "saver").bandwidth);
        eq("pick auto", null, Hls.pick(v, "auto"));
        eq("pick empty", null, Hls.pick(v, "best") == null ? null : Hls.pick(Hls.parseMaster("", BASE), "best"));
        eq("media playlist is not master", 0, Hls.parseMaster("#EXTM3U\n#EXTINF:6,\nseg1.ts\n", BASE).size());
        eq("null text", 0, Hls.parseMaster(null, BASE).size());
        eq("crlf + no resolution", "0x0", Hls.parseMaster("#EXTM3U\r\n#EXT-X-STREAM-INF:BANDWIDTH=100\r\na.m3u8\r\n", BASE).get(0).width + "x0");
        eq("tag between info and uri", "http://h.example/live/b.m3u8",
            Hls.parseMaster("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n#EXT-X-FOO\nb.m3u8\n", BASE).get(0).url);
        eq("i-frame lines skipped", 0, Hls.parseMaster("#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1,URI=\"i.m3u8\"\n", BASE).size());
        eq("resolve query base", "http://h.example/live/x.m3u8", Hls.resolve(BASE, "x.m3u8"));
        eq("resolve garbage", null, Hls.resolve("not a url", "x"));
        System.out.println(failures == 0 ? "HlsTest OK" : failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
