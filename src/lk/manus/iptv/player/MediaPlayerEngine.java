package lk.manus.iptv.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.SubtitleData;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lk.manus.iptv.data.Channel;

/** The original android.media.MediaPlayer path (fallback engine). Own WebVTT cue timing lives here. */
public final class MediaPlayerEngine implements Engine {
    private static final class SCue { long s, e; String t; }
    private final Context ctx;
    private final Listener l;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private final Leveler lev = new Leveler();
    private boolean even = true;
    private boolean dead;
    private int subSel = -1;
    private final List<SCue> cues = new ArrayList<>();
    private final List<Long> kSamples = new ArrayList<>();
    private long kUs;
    private int cuesLogged;
    private String lastText = "";
    private final Runnable subR = new Runnable() { @Override public void run() { subTick(); } };

    public MediaPlayerEngine(Context ctx, Listener l) { this.ctx = ctx; this.l = l; }

    @Override public String name() { return "mediaplayer"; }
    @Override public boolean smart() { return false; }

    @Override public void prepare(Channel c, String url, String quality, SurfaceHolder holder, Surface surface) throws Exception {
        final MediaPlayer m = new MediaPlayer();
        mp = m;
        m.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        lev.update(m.getAudioSessionId(), even);
        if (surface != null) m.setSurface(surface); else m.setDisplay(holder);
        Map<String, String> hd = new HashMap<>();
        if (!c.userAgent.isEmpty()) hd.put("User-Agent", c.userAgent);
        if (!c.referrer.isEmpty()) hd.put("Referer", c.referrer);
        m.setDataSource(ctx, Uri.parse(url), hd);
        m.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override public void onVideoSizeChanged(MediaPlayer p, int w, int hh) { if (!dead) l.onVideoSize(w, hh); }
        });
        m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(final MediaPlayer p) {
                if (dead) return;
                p.start();
                h.postDelayed(new Runnable() { // audio-only streams never render video
                    @Override public void run() { if (!dead && p.isPlaying() && p.getVideoWidth() == 0) l.onPlaying(); }
                }, 1500);
            }
        });
        m.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override public boolean onInfo(MediaPlayer p, int what, int extra) {
                if (dead) return true;
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) l.onPlaying();
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) l.onBuffering(true);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) l.onBuffering(false);
                return true;
            }
        });
        m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { // live streams should not end
            @Override public void onCompletion(MediaPlayer p) { if (!dead) l.onError("completed"); }
        });
        m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer p, int what, int extra) { if (!dead) l.onError(what + "/" + extra); return true; }
        });
        if (Build.VERSION.SDK_INT >= 28) m.setOnSubtitleDataListener(new MediaPlayer.OnSubtitleDataListener() {
            @Override public void onSubtitleData(MediaPlayer p, SubtitleData d) { if (!dead) onSubData(d); }
        }, h);
        m.prepareAsync();
    }

    @Override public void play() { }
    @Override public void pause() { }

    @Override public void release(final Runnable done) {
        dead = true;
        h.removeCallbacks(subR);
        lev.release();
        final MediaPlayer old = mp;
        mp = null;
        new Thread(new Runnable() { // MediaPlayer.release() can block on a hung network read
            @Override public void run() {
                if (old != null) try { old.release(); } catch (Exception ignored) { }
                if (done != null) h.post(done);
            }
        }, "release").start();
    }

    private static String mimeOf(MediaPlayer.TrackInfo t) {
        try { MediaFormat f = t.getFormat(); String m = f == null ? null : f.getString(MediaFormat.KEY_MIME); return m == null ? "" : m; }
        catch (Exception e) { return ""; }
    }

    @Override public List<Track> subtitleTracks() {
        List<Track> out = new ArrayList<>();
        MediaPlayer p = mp;
        if (p == null) return out;
        MediaPlayer.TrackInfo[] ti;
        try { ti = p.getTrackInfo(); } catch (Exception e) { return out; }
        if (ti == null) return out;
        for (int i = 0; i < ti.length; i++) {
            if (ti[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) continue;
            Track x = new Track();
            x.id = i;
            x.mime = mimeOf(ti[i]);
            String lg = ti[i].getLanguage();
            x.lang = lg == null ? "" : lg;
            out.add(x);
        }
        return out;
    }

    @Override public boolean selectSubtitle(int id) {
        MediaPlayer p = mp;
        boolean ok = true;
        try {
            if (p != null && subSel >= 0) p.deselectTrack(subSel);
            if (p != null && id >= 0) p.selectTrack(id);
        } catch (Exception e) { Log.i("IPTV", "subtitle select failed " + e.getClass().getSimpleName()); id = -1; ok = false; }
        subSel = id;
        cues.clear(); kSamples.clear(); kUs = 0; lastText = "";
        h.removeCallbacks(subR);
        if (id >= 0) h.post(subR);
        l.onCues("");
        return ok;
    }

    // HLS WebVTT on MediaPlayer: cue times are on the stream's own clock (often minutes ahead of getCurrentPosition) and the
    // chunk times are no use, so the clock offset is estimated from when chunks arrive (median of the last 5). Sync is approximate.
    private void onSubData(SubtitleData d) {
        MediaPlayer p = mp;
        if (subSel < 0 || p == null) return;
        byte[] b = d.getData();
        List<WebVtt.Cue> list = WebVtt.parse(b);
        long posUs;
        try { posUs = p.getCurrentPosition() * 1000L; } catch (Exception e) { return; }
        int before = cues.size();
        if (!list.isEmpty()) {
            kSamples.add(list.get(0).startUs - posUs);
            if (kSamples.size() > 5) kSamples.remove(0);
            List<Long> sorted = new ArrayList<>(kSamples);
            Collections.sort(sorted);
            kUs = sorted.get(sorted.size() / 2);
        }
        for (WebVtt.Cue c : list) {
            boolean dup = false;
            for (int i = cues.size() - 1; i >= 0 && i >= cues.size() - 12 && !dup; i--) dup = cues.get(i).s == c.startUs && cues.get(i).t.equals(c.text);
            if (dup) continue;
            SCue x = new SCue(); x.s = c.startUs; x.e = c.endUs; x.t = c.text; cues.add(x);
        }
        if (list.isEmpty() && b != null && b.length > 0 && subMime().contains("vtt")) { // not WebVTT text at all: show it for the chunk's own time
            String t = new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!t.isEmpty() && !t.startsWith("WEBVTT")) { SCue x = new SCue(); x.s = d.getStartTimeUs() + kUs; x.e = x.s + Math.max(d.getDurationUs(), 2000000L); x.t = t; cues.add(x); }
        }
        if (cues.size() > before && cuesLogged++ < 6)
            Log.i("IPTV", "subtitle data: " + (cues.size() - before) + " cues, first " + cues.get(before).s / 1000 + " ms, pos " + posUs / 1000 + " ms, offset " + kUs / 1000 + " ms");
    }

    private String subMime() {
        for (Track t : subtitleTracks()) if (t.id == subSel) return t.mime;
        return "";
    }

    /** 10 times a second while subtitles are on: the cue covering the playback position. */
    private void subTick() {
        MediaPlayer p = mp;
        if (subSel < 0 || p == null || dead) return;
        h.postDelayed(subR, 100);
        long pos;
        try { pos = p.getCurrentPosition() * 1000L; } catch (Exception e) { return; }
        String t = "";
        for (int i = cues.size() - 1; i >= 0; i--) {
            SCue c = cues.get(i);
            if (c.e - kUs < pos - 30000000L) { cues.remove(i); continue; } // 30 s behind: never needed again
            if (t.isEmpty() && c.s - kUs <= pos && pos < c.e - kUs) t = c.t;
        }
        if (cues.size() > 400) cues.subList(0, cues.size() - 400).clear();
        if (!t.equals(lastText)) { lastText = t; l.onCues(t); }
    }

    @Override public long positionMs() { return -1; }
    @Override public long windowMs() { return -1; }
    @Override public long behindMs() { return 0; }
    @Override public void seekBy(long ms) { }
    @Override public void goLive() { }

    @Override public void setEven(boolean on) { even = on; if (mp != null) lev.update(mp.getAudioSessionId(), on); }

    @Override public void setVolume(float v) { if (mp != null) try { mp.setVolume(v, v); } catch (IllegalStateException ignored) { } }
}
