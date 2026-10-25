import lk.manus.iptv.player.AudioGuard;
import lk.manus.iptv.player.Vol;

/** Channel-volume clamp / step and the underrun window counter. */
public class AudioTest {
    private static int failures;

    private static void eq(String what, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) { failures++; System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]"); }
    }

    public static void main(String[] args) {
        eq("clamp high", 1.0f, Vol.clamp(2f));
        eq("clamp low", 0.3f, Vol.clamp(0f));
        eq("clamp nan", 1.0f, Vol.clamp(Float.NaN));
        eq("snap", 0.7f, Vol.clamp(0.68f));
        eq("down", 0.9f, Vol.step(1.0f, -1));
        eq("up at max", 1.0f, Vol.step(1.0f, 1));
        eq("down at min", 0.3f, Vol.step(0.3f, -1));
        float v = 1f;
        for (int i = 0; i < 10; i++) v = Vol.step(v, -1);
        eq("floor after 10", 0.3f, v);
        eq("bar", "▮▮▮▮▮▮▮▯▯▯ 70%", Vol.bar(0.7f));

        AudioGuard g = new AudioGuard();
        eq("3 in window is fine", false, g.hit(0) | g.hit(1000) | g.hit(2000));
        eq("4th trips", true, g.hit(3000));
        eq("reset after trip", false, g.hit(4000));
        AudioGuard s = new AudioGuard(); // spread out: never more than 3 inside 30 s
        boolean any = false;
        for (int i = 0; i < 20; i++) any |= s.hit(i * 11000L);
        eq("slow drip never trips", false, any);
        AudioGuard b = new AudioGuard();
        b.hit(0); b.hit(1000); b.hit(2000);
        eq("old hits expire", false, b.hit(40000));

        if (failures == 0) System.out.println("AudioTest: all passed"); else { System.out.println("AudioTest: " + failures + " FAILED"); System.exit(1); }
    }
}
