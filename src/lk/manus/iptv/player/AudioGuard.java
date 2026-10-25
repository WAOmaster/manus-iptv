package lk.manus.iptv.player;

import java.util.ArrayDeque;

/** Counts audio underruns in a sliding window; hit() is true when more than LIMIT happened within WINDOW_MS (then the count resets). */
public final class AudioGuard {
    public static final int LIMIT = 3;
    public static final long WINDOW_MS = 30000;
    private final ArrayDeque<Long> t = new ArrayDeque<>();

    public boolean hit(long nowMs) {
        t.addLast(nowMs);
        while (!t.isEmpty() && nowMs - t.peekFirst() > WINDOW_MS) t.removeFirst();
        if (t.size() > LIMIT) { t.clear(); return true; }
        return false;
    }

    public void reset() { t.clear(); }
}
