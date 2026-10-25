package lk.manus.iptv.player;

import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.util.Log;

/** "Even out volume": a compressor + limiter on one audio session (DynamicsProcessing, API 28+). If the TV refuses it: no processing, logged once. */
final class Leveler {
    private static boolean warned;
    private DynamicsProcessing dp;
    private int session;
    private boolean on;

    /** Idempotent: (re)builds only when the session or the switch changed. */
    void update(int sessionId, boolean enable) {
        if (enable == on && sessionId == session) return;
        release();
        on = enable;
        session = sessionId;
        if (!enable || sessionId == 0 || Build.VERSION.SDK_INT < 28) return;
        try {
            DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2, false, 0, true, 1, false, 0, true);
            b.setInputGainAllChannelsTo(0f);
            // one band: threshold -24 dBFS, 3:1, attack 10 ms, release 200 ms, knee 6 dB, post-gain +6 dB
            b.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f));
            DynamicsProcessing d = new DynamicsProcessing(0, sessionId, b.build()); // 2 channels = both stereo sides, same settings (linked)
            d.setMbcBandAllChannelsTo(0, new DynamicsProcessing.MbcBand(true, 20000f, 10f, 200f, 3f, -24f, 6f, -90f, 1f, 0f, 6f));
            d.setEnabled(true);
            dp = d;
            Log.i("IPTV", "even-volume on, session " + sessionId);
        } catch (Exception | LinkageError e) {
            if (!warned) { warned = true; Log.w("IPTV", "even-volume unavailable on this device: " + e); }
        }
    }

    void release() {
        DynamicsProcessing d = dp;
        dp = null;
        if (d != null) try { d.release(); } catch (Exception ignored) { }
    }
}
