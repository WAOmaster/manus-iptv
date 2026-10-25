package lk.manus.iptv.player;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;
import android.view.TextureView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import lk.manus.iptv.data.Channel;

/**
 * T38: the small muted video inside the guide's preview card. A second, light ExoPlayer (lowest variant, no audio, tiny buffer);
 * never more than one; the full-screen player is not touched. All calls on the UI thread.
 */
public final class MiniPreview {
    private static final String TAG = "IPTV";

    public interface Listener {
        void onFirstFrame();
        void onVideoSize(int w, int h);
        /** Decoder or stream trouble; software = the second decoder was a software one (too heavy for this TV). */
        void onFail(String why, boolean decoderProblem);
    }

    private final Context ctx;
    private final Listener l;
    private ExoPlayer p;

    public MiniPreview(Context ctx, Listener l) { this.ctx = ctx.getApplicationContext(); this.l = l; }

    /** Largest instance count any H.264 hardware decoder claims (0 = no hardware decoder found). Static check only. */
    public static int avcInstances() {
        int best = 0;
        try {
            for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (i.isEncoder() || isSoftware(i.getName())) continue;
                for (String t : i.getSupportedTypes())
                    if (t.equalsIgnoreCase("video/avc")) best = Math.max(best, i.getCapabilitiesForType(t).getMaxSupportedInstances());
            }
        } catch (Exception e) { Log.w(TAG, "mini: codec list failed " + e); }
        return best;
    }

    static boolean isSoftware(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.endsWith(".sw");
    }

    public boolean active() { return p != null; }

    public int dropped = 0;

    public void start(Channel c, String url, TextureView tv) {
        stop();
        DefaultHttpDataSource.Factory df = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(6000).setReadTimeoutMs(6000).setAllowCrossProtocolRedirects(true);
        if (!c.userAgent.isEmpty()) df.setUserAgent(c.userAgent);
        if (!c.referrer.isEmpty()) { Map<String, String> hd = new HashMap<>(); hd.put("Referer", c.referrer); df.setDefaultRequestProperties(hd); }
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(2000, 6000, 1000, 1500).build();
        final ExoPlayer x = new ExoPlayer.Builder(ctx, new DefaultRenderersFactory(ctx).setEnableDecoderFallback(false))
                .setLoadControl(lc).setMediaSourceFactory(Media3Engine.sources(df)).build();
        p = x;
        dropped = 0;
        x.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), false); // never takes audio focus
        x.setVolume(0f);
        x.setTrackSelectionParameters(x.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxVideoSize(426, 240).setMaxVideoBitrate(600000).build());
        x.setVideoTextureView(tv);
        x.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime t, String name, long initMs) {
                Log.i(TAG, "mini: decoder " + name + (isSoftware(name) ? " (software)" : " (hardware)"));
                if (isSoftware(name) && p == x) l.onFail("software decoder " + name, true);
            }
            @Override public void onDroppedVideoFrames(EventTime t, int n, long ms) { dropped += n; }
        });
        x.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() { if (p == x) { Log.i(TAG, "mini: first frame " + c.name); l.onFirstFrame(); } }
            @Override public void onVideoSizeChanged(VideoSize v) { if (p == x && v.width > 0) l.onVideoSize(Math.round(v.width * v.pixelWidthHeightRatio), v.height); }
            @Override public void onPlayerError(PlaybackException e) {
                if (p != x) return;
                boolean dec = e.errorCode >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.errorCode <= PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
                Log.i(TAG, "mini: error " + e.getErrorCodeName());
                l.onFail(e.getErrorCodeName(), dec);
            }
        });
        String low = url.toLowerCase(Locale.ROOT);
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        if (low.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (low.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD);
        x.setMediaItem(b.build());
        x.setPlayWhenReady(true);
        x.prepare();
        Log.i(TAG, "mini: start " + c.name);
    }

    public void stop() {
        ExoPlayer x = p;
        if (x == null) return;
        p = null;
        try { x.clearVideoSurface(); x.release(); } catch (Exception ignored) { }
        Log.i(TAG, "mini: stop");
    }
}
