package lk.manus.iptv.player;

import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.List;

import lk.manus.iptv.data.Channel;

/** What PlayerActivity needs from a playback engine (Media3 by default, MediaPlayer as the fallback). All calls and callbacks run on the UI thread. */
public interface Engine {
    interface Listener {
        void onPlaying();                 // first frame rendered (or audio started on an audio-only stream)
        void onBuffering(boolean start);  // a stall began / ended after playback had started
        void onError(String detail);      // fatal for this start; the activity reconnects or fails over
        void onVideoSize(int w, int h);   // display size, pixel aspect applied
        void onTracks();                  // the subtitle list changed: pull subtitleTracks()
        void onCues(String text);         // the text to show now; "" = none
        default void onAudioDrop() { }    // one audio underrun / sink error (the activity counts them)
    }

    final class Track { public int id; public String mime = "", lang = "", label = ""; public boolean sel; }

    String name();
    /** True when the engine adapts quality itself (quality() limits) and can pause / rewind live TV. */
    boolean smart();
    /** Exactly one of holder / surface is the video target. quality = auto | saver | best (only used when smart()). */
    void prepare(Channel c, String url, String quality, SurfaceHolder holder, Surface surface) throws Exception;
    void play();
    void pause();
    /** Releases without blocking the UI thread on a hung read; done (may be null) runs on the UI thread afterwards. */
    void release(Runnable done);

    List<Track> subtitleTracks();
    /** id from subtitleTracks(), -1 = off. False if the engine refused. */
    boolean selectSubtitle(int id);

    // timeshift (smart engines); -1 = unknown
    long positionMs();        // within the seekable window
    long windowMs();          // length of the seekable window, -1 if the stream is not a live window
    long behindMs();          // how far behind the normal live position (0 = live)
    void seekBy(long ms);
    void goLive();

    // audio (all optional)
    /** "Even out volume" on / off; cheap to call repeatedly. */
    default void setEven(boolean on) { }
    /** Per-channel multiplier 0..1 (see Vol). */
    default void setVolume(float v) { }
    /** Audio languages; only worth showing when there are two or more. */
    default List<Track> audioTracks() { return new java.util.ArrayList<>(); }
    default boolean selectAudio(int id) { return false; }
    /** Video frames dropped so far (the mini preview watches this for a degraded main stream). */
    default int droppedFrames() { return 0; }
    /** Caps video bitrate one step lower to free bandwidth; false if already at the floor / not supported. */
    default boolean lowerQuality() { return false; }
}
