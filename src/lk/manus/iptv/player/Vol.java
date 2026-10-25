package lk.manus.iptv.player;

/** Per-channel volume multiplier. Max is 1.0 on purpose: a player can only attenuate, never exceed the system volume
 *  (loudness above that comes from the "Even out volume" compressor's post-gain). Min 0.3 so a channel can't be lost to silence. */
public final class Vol {
    public static final float MIN = 0.3f, MAX = 1.0f;
    private Vol() { }

    /** Clamped to MIN..MAX and snapped to whole 10% steps. */
    public static float clamp(float v) {
        if (Float.isNaN(v)) return MAX;
        return Math.round(Math.max(MIN, Math.min(MAX, v)) * 10) / 10f;
    }

    /** One 10% step up (dir > 0) or down. */
    public static float step(float cur, int dir) { return clamp(Math.round(clamp(cur) * 10) / 10f + (dir > 0 ? 0.1f : -0.1f)); }

    /** "▮▮▮▮▮▮▮▯▯▯ 70%" */
    public static String bar(float v) {
        int n = Math.round(clamp(v) * 10);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(i < n ? '▮' : '▯');
        return b.append(' ').append(n * 10).append('%').toString();
    }
}
