package lk.manus.iptv.player;

import android.content.Context;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lk.manus.iptv.data.Channel;

/** Media3 / ExoPlayer engine (default): HLS + DASH, own buffering, live offset, text tracks, pause / rewind inside the live window. */
public final class Media3Engine implements Engine {
    private static final String TAG = "IPTV";
    private final Context ctx;
    private final Listener l;
    private ExoPlayer p;
    private MediaItem item;
    private boolean played, buffering, wasPaused, hlsGuess, logged;
    private int behindRetries;
    private final Leveler lev = new Leveler();
    private boolean even = true;
    private long gap0 = -1; // distance from the live end of the window when playback started = the normal position
    private final Timeline.Window win = new Timeline.Window();

    private int dropped;

    public Media3Engine(Context ctx, Listener l) { this.ctx = ctx; this.l = l; }

    @Override public int droppedFrames() { return dropped; }

    /**
     * HLS with a CEA-608 track exposed even when the playlist does not declare CLOSED-CAPTIONS (most free streams do not,
     * yet carry captions in the video); everything else goes to the default factory.
     */
    static MediaSource.Factory sources(DefaultHttpDataSource.Factory df) {
        final DefaultMediaSourceFactory def = new DefaultMediaSourceFactory(df);
        final HlsMediaSource.Factory hls = new HlsMediaSource.Factory(df).setExtractorFactory(new DefaultHlsExtractorFactory(0, true)).setAllowChunklessPreparation(false); // chunkless prepare would hide undeclared captions
        return new MediaSource.Factory() {
            @Override public MediaSource.Factory setDrmSessionManagerProvider(androidx.media3.exoplayer.drm.DrmSessionManagerProvider d) { def.setDrmSessionManagerProvider(d); hls.setDrmSessionManagerProvider(d); return this; }
            @Override public MediaSource.Factory setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy p) { def.setLoadErrorHandlingPolicy(p); hls.setLoadErrorHandlingPolicy(p); return this; }
            @Override public int[] getSupportedTypes() { return def.getSupportedTypes(); }
            @Override public MediaSource createMediaSource(MediaItem mi) {
                MediaItem.LocalConfiguration lc = mi.localConfiguration;
                boolean isHls = lc != null && androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(lc.uri, lc.mimeType) == C.CONTENT_TYPE_HLS;
                return isHls ? hls.createMediaSource(mi) : def.createMediaSource(mi);
            }
        };
    }

    @Override public String name() { return "media3"; }
    @Override public boolean smart() { return true; }

    @Override public void prepare(Channel c, String url, String quality, SurfaceHolder holder, Surface surface) {
        DefaultHttpDataSource.Factory df = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(true);
        if (!c.userAgent.isEmpty()) df.setUserAgent(c.userAgent);
        if (!c.referrer.isEmpty()) { Map<String, String> hd = new HashMap<>(); hd.put("Referer", c.referrer); df.setDefaultRequestProperties(hd); }
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(30000, 60000, 2500, 5000).build();
        p = new ExoPlayer.Builder(ctx, new DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
                .setLoadControl(lc).setMediaSourceFactory(sources(df)).build();
        p.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false);
        p.setHandleAudioBecomingNoisy(true);
        TrackSelectionParameters.Builder tp = p.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); // subtitles off until chosen
        if ("saver".equals(quality)) tp.setMaxVideoSize(640, 360).setMaxVideoBitrate(1000000); // nothing small enough = the lowest variant
        else if ("best".equals(quality)) tp.setForceHighestSupportedBitrate(true);
        else if ("auto720".equals(quality)) tp.setMaxVideoSize(1280, 720); // Low-tier TV, quality never chosen
        p.setTrackSelectionParameters(tp.build());
        if (surface != null) p.setVideoSurface(surface); else p.setVideoSurfaceHolder(holder);
        p.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onAudioUnderrun(AnalyticsListener.EventTime t, int size, long sizeMs, long sinceMs) { l.onAudioDrop(); }
            @Override public void onAudioSinkError(AnalyticsListener.EventTime t, Exception e) { l.onAudioDrop(); }
            @Override public void onAudioSessionIdChanged(AnalyticsListener.EventTime t, int id) { lev.update(id, even); }
            @Override public void onDroppedVideoFrames(AnalyticsListener.EventTime t, int n, long ms) { dropped += n; Log.i(TAG, "media3 dropped " + n + " frames (total " + dropped + ")"); }
        });
        lev.update(p.getAudioSessionId(), even);
        p.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() { fire(); }
            @Override public void onPlaybackStateChanged(int st) {
                if (p == null) return;
                if (st == Player.STATE_BUFFERING && played && !buffering) { buffering = true; l.onBuffering(true); }
                else if (st == Player.STATE_READY) {
                    if (buffering) { buffering = false; l.onBuffering(false); }
                    if (!played && p.getVideoFormat() == null) fire(); // audio-only
                    if (!logged) { logged = true; Format v = p.getVideoFormat(), au = p.getAudioFormat(); Log.i(TAG, "media3 ready: video " + (v == null ? "none" : v.sampleMimeType + " " + v.width + "x" + v.height) + ", audio " + (au == null ? "none" : au.sampleMimeType + " " + au.channelCount + "ch")); }
                } else if (st == Player.STATE_ENDED) l.onError("ended");
            }
            @Override public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) l.onVideoSize(Math.round(v.width * v.pixelWidthHeightRatio), v.height);
            }
            @Override public void onTracksChanged(Tracks t) {
                StringBuilder sb = new StringBuilder();
                for (Tracks.Group g : t.getGroups()) if (g.getType() == C.TRACK_TYPE_TEXT) for (int i = 0; i < g.length; i++) { Format f = g.getTrackFormat(i); sb.append(' ').append(f.sampleMimeType).append('/').append(f.language).append(g.isTrackSelected(i) ? "*" : ""); }
                Log.i(TAG, "media3 text tracks:" + (sb.length() == 0 ? " none" : sb));
                l.onTracks();
            }
            @Override public void onCues(CueGroup g) {
                StringBuilder sb = new StringBuilder();
                for (Cue q : g.cues) if (q.text != null) { if (sb.length() > 0) sb.append('\n'); sb.append(q.text); }
                l.onCues(sb.toString().trim());
            }
            @Override public void onPlayerError(PlaybackException e) { error(e); }
        });
        String low = url.toLowerCase(Locale.ROOT);
        int q = low.indexOf('?');
        String path = q >= 0 ? low.substring(0, q) : low;
        MediaItem.Builder b = new MediaItem.Builder().setUri(url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(30000).setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build());
        if (low.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (low.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD);
        hlsGuess = !path.matches(".*\\.(ts|mp4|mkv|flv|mp3|aac|m3u8|mpd)$");
        item = b.build();
        p.setMediaItem(item);
        p.setPlayWhenReady(true);
        p.prepare();
    }

    private void fire() { if (!played) { played = true; gap0 = gap(); l.onPlaying(); } }

    private void error(PlaybackException e) {
        if (p == null) return;
        int c = e.errorCode;
        if (c == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && behindRetries++ < 3) {
            Log.i(TAG, "media3: behind live window, re-preparing" + (wasPaused ? " from the oldest point" : ""));
            if (wasPaused) p.setMediaItem(item, 0L); else p.seekToDefaultPosition(); // after a long pause: the oldest point still available
            p.setPlayWhenReady(true);
            p.prepare();
            return;
        }
        if (hlsGuess && !played && behindRetries == 0 && (c == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || c == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            hlsGuess = false; // an endpoint without an extension that is really HLS
            Log.i(TAG, "media3: retrying as HLS");
            p.setMediaItem(item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build());
            p.prepare();
            return;
        }
        l.onError(e.getErrorCodeName() + (e.getCause() == null ? "" : " " + e.getCause().getClass().getSimpleName()));
    }

    @Override public void play() { if (p != null) { p.play(); } }
    @Override public void pause() { if (p != null) { wasPaused = true; p.pause(); freezeSpeed(); } }

    /** A user pause / rewind must not be undone by the live catch-up speed-up. */
    private void freezeSpeed() { if (p != null) p.setPlaybackParameters(new PlaybackParameters(1f)); }

    @Override public void release(Runnable done) {
        ExoPlayer x = p;
        p = null;
        lev.release();
        if (x != null) { try { x.clearVideoSurface(); x.release(); } catch (Exception ignored) { } }
        if (done != null) done.run();
    }

    @Override public List<Track> subtitleTracks() {
        List<Track> out = new ArrayList<>();
        if (p == null) return out;
        int n = 0;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT || g.length == 0) continue;
            Format f = g.getTrackFormat(0);
            Track t = new Track();
            t.id = n++;
            t.mime = f.sampleMimeType == null ? "" : f.sampleMimeType;
            t.lang = f.language == null ? "" : f.language;
            t.label = f.label == null ? "" : f.label;
            out.add(t);
        }
        return out;
    }

    @Override public boolean selectSubtitle(int id) {
        if (p == null) return false;
        TrackSelectionParameters.Builder tp = p.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT);
        int n = 0;
        boolean found = id < 0;
        if (id >= 0) for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT || g.length == 0) continue;
            if (n++ == id) { tp.setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)); found = true; break; }
        }
        if (!found) return false;
        p.setTrackSelectionParameters(tp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, id < 0).build());
        if (id < 0) l.onCues("");
        return true;
    }

    /** The live window of the current item, or null when the stream is not a live window. */
    private Timeline.Window liveWindow() {
        if (p == null) return null;
        Timeline tl = p.getCurrentTimeline();
        if (tl.isEmpty()) return null;
        tl.getWindow(p.getCurrentMediaItemIndex(), win);
        return win.isLive() && win.getDurationMs() != C.TIME_UNSET ? win : null;
    }

    @Override public long positionMs() { return p == null ? -1 : p.getCurrentPosition(); }

    @Override public long windowMs() { Timeline.Window w = liveWindow(); return w == null ? -1 : w.getDurationMs(); }

    /** How far the playhead is from the live end of the window (-1 = not a live window). */
    private long gap() { Timeline.Window w = liveWindow(); return w == null ? -1 : w.getDurationMs() - p.getCurrentPosition(); }

    /** Only the window's own geometry is used (many live HLS playlists carry no wall-clock time, so the live offset is unknown). */
    @Override public long behindMs() {
        long g = gap();
        if (g < 0) return 0;
        if (gap0 < 0) gap0 = g;
        return Math.max(0, g - gap0);
    }

    @Override public void seekBy(long ms) {
        Timeline.Window w = liveWindow();
        if (w == null) return;
        if (gap0 < 0) gap0 = gap();
        long to = Math.max(0, p.getCurrentPosition() + ms);
        if (ms > 0 && w.getDurationMs() - to - gap0 <= 1500) { goLive(); return; }
        freezeSpeed();
        p.seekTo(Math.min(to, w.getDurationMs()));
    }

    @Override public void goLive() {
        if (p == null) return;
        p.seekToDefaultPosition();
        p.play();
    }

    @Override public void setEven(boolean on) { even = on; if (p != null) lev.update(p.getAudioSessionId(), on); }

    @Override public void setVolume(float v) { if (p != null) p.setVolume(v); }

    @Override public List<Track> audioTracks() {
        List<Track> out = new ArrayList<>();
        if (p == null) return out;
        int n = 0;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO || g.length == 0) continue;
            Format f = g.getTrackFormat(0);
            Track t = new Track();
            t.id = n++;
            t.mime = f.sampleMimeType == null ? "" : f.sampleMimeType;
            t.lang = f.language == null ? "" : f.language;
            t.label = f.label == null ? "" : f.label;
            t.sel = g.isSelected();
            out.add(t);
        }
        return out;
    }

    @Override public boolean selectAudio(int id) {
        if (p == null) return false;
        int n = 0;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO || g.length == 0) continue;
            if (n++ == id) {
                p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), 0)).build());
                return true;
            }
        }
        return false;
    }

    /** 60% of the bitrate now playing (1.5 Mbit/s if unknown), floor 400 kbit/s. */
    @Override public boolean lowerQuality() {
        if (p == null) return false;
        Format v = p.getVideoFormat();
        int br = v != null && v.bitrate > 0 ? v.bitrate : 2500000;
        int to = br * 6 / 10;
        if (br <= 400000) return false;
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon().setMaxVideoBitrate(Math.max(to, 400000)).build());
        Log.i(TAG, "media3: video cap " + Math.max(to, 400000) / 1000 + " kbit/s (audio drops)");
        return true;
    }
}
