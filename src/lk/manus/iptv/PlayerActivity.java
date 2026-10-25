package lk.manus.iptv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Curated;
import lk.manus.iptv.data.Dedup;
import lk.manus.iptv.data.CloudData;
import lk.manus.iptv.data.FavGuard;
import lk.manus.iptv.data.Health;
import lk.manus.iptv.data.Prober;
import lk.manus.iptv.data.Rank;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.ui.Guide;
import lk.manus.iptv.ui.NowBar;
import lk.manus.iptv.ui.Keys;
import lk.manus.iptv.ui.Modal;
import lk.manus.iptv.ui.Ring;
import lk.manus.iptv.ui.LogoLoader;
import lk.manus.iptv.ui.Ui;
import lk.manus.iptv.data.Redact;
import lk.manus.iptv.data.Sources;
import lk.manus.iptv.player.AudioGuard;
import lk.manus.iptv.player.Engine;
import lk.manus.iptv.player.SubPref;
import lk.manus.iptv.player.Vol;
import lk.manus.iptv.player.Hls;
import lk.manus.iptv.player.MediaPlayerEngine;
import lk.manus.iptv.player.Media3Engine;

public class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "IPTV";
    private static final int DEBOUNCE_MS = 400, TIMEOUT_MS = 15000, SPIKE_TIMEOUT_MS = 12000, MAX_FAILS = 5;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Sources sources;
    private SurfaceHolder holder;
    private boolean surfaceReady, paused = true;
    private SurfaceView sv;
    private TextureView tex;
    private Surface texSurface;
    private int vw, vh;          // decoded video size: the TextureView is letterboxed to this aspect
    private boolean useTex;      // mirror mode: video goes to the TextureView (capturable), applied from the next channel start
    private Object pendingStart[]; // {Channel, url, gen} waiting for the surface of a just-switched mode
    private LinearLayout bottomStack;   // subtitles above the banner (UX-SPEC 4): one column at the bottom of the safe area
    private Modal modal;               // one dimmed, centred layer for the options pill, version picker, subtitle chooser and failure card
    private int pillKind;              // the one pill (top-centre): 0 none, 1 status (buffering...), 2 message
    private TextView bNum, bName, bInfo, bState, bSpeed, bSec, bClock, message, toast;
    private ImageView bLogo;
    private View banner, bBar;
    private ValueAnimator barAnim;
    private Guide guide;
    private Index idx;
    private Store store;
    private String zapSection = "foryou", zapName = "For You";
    private Channel previewCh;   // channel shown by live preview while the guide is open (list/index are untouched)
    private boolean okDown;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);

    private List<Channel> all, list; // all = full playlist, list = what the remote zaps through
    private int index, dir = 1, fails, gen;
    private boolean playing, gaveUp;
    private Engine eng;                // Media3 (default) or MediaPlayer, chosen per start by the "engine" pref
    private boolean m3Broken;          // Media3 classes failed to load: stay on MediaPlayer
    private long t0;
    private final StringBuilder num = new StringBuilder(); // channel number being typed
    private TextView numTv, bHint, bNow, bNext, bVol;
    private NowBar bNowBar;
    private boolean hintShown;
    private final Runnable numR = new Runnable() { @Override public void run() { commitNum(); } };

    // ranking / health / failover (T15)
    private Health health;
    private Prober prober;
    private Channel playCh;                 // the stream actually playing (an alternate during failover)
    private Channel altFor;                 // channel whose alternates are being tried
    private int altI;
    private boolean failover, simFailFirst, simFailDone;

    // T27 optional auto-skip: connecting ring + failure card; T24 pause; DEBUG hooks (never persisted)
    private static final String KEYTAG = "IPTV-key";
    private LinearLayout connBox, failBox, failRow;
    private Ring connRing;
    private TextView failSub;
    private final List<TextView> failBtns = new ArrayList<>();
    private final List<String> failActs = new ArrayList<>();
    private int failSel;
    private boolean failUp, userPaused;
    private Boolean dbgAutoSkip;            // DEBUG: overrides the Skip setting in memory only
    private int simFailNext, bgSkipSec;     // DEBUG: fail the next N starts (health untouched) / fake a background auto-skip while the guide is open
    private final Runnable connR = new Runnable() {
        @Override public void run() {
            if (playing || autoSkip() || previewCh != null || guide.isOpen() || userPaused || failUp) return;
            connBox.setVisibility(View.VISIBLE);
            ringTick.run();
        }
    };
    private final Runnable ringTick = new Runnable() {
        @Override public void run() {
            if (connBox.getVisibility() != View.VISIBLE) return;
            connRing.set((SystemClock.elapsedRealtime() - t0) / (float) TIMEOUT_MS);
            h.postDelayed(this, 100);
        }
    };
    private final Runnable bgSkipR = new Runnable() {
        @Override public void run() {
            Log.i(TAG, "DEBUG bgskip: failing the playing stream while the guide is open (guide open=" + guide.isOpen() + ")");
            playing = false;
            fail("error", "simbgskip");
        }
    };

    // corner watermark (T18)
    private LinearLayout wm;
    private ImageView wmLogo;
    private TextView wmTitle;
    private boolean wmArm;
    private String nowTitle;                // programme title once EPG exists (M3); null = show the channel name
    private final Runnable wmHideR = new Runnable() {
        @Override public void run() {
            if (!Ui.anim()) { wm.setVisibility(View.GONE); return; }
            wm.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() { @Override public void run() { wm.setVisibility(View.GONE); } }).start();
        }
    };

    // subtitles (T19)
    private List<Engine.Track> subs = new ArrayList<>(), audios = new ArrayList<>();
    private final AudioGuard aGuard = new AudioGuard();
    private int audioTrips;
    private boolean audioRestored, infoUp; // infoUp: the banner was opened with INFO, so Left / Right set the channel volume
    private String curCue = "";
    private int dbgSub = -1;                // DEBUG: select this subtitle track once the list is known
    private int subSel = -1;                // index into subs; -1 = Off
    private boolean subRestored, shownAny;
    private TextView subTv, bCC;
    // T40 English subtitles: trSrc = source language of the chosen track ("" = unknown, detect from the text); trOn = this track needs translating
    private lk.manus.iptv.subs.SubTranslator tr;
    private boolean trOn, trWarned;
    private String trSrc = "", trCue = "", detCand = "";
    private int detAgree;
    private LinearLayout menuBox;
    private TextView menuTitle;
    private final List<TextView> menuRows = new ArrayList<>();
    private int menuSel;
    private MenuPick menuPick;
    private interface MenuPick { void pick(int i); }
    private final Runnable menuHideR = new Runnable() { @Override public void run() { closeMenu(); } };
    private final Runnable rescanR = new Runnable() { @Override public void run() { if (playing && subs.isEmpty()) scanTracks(); } };

    // T30 pause / rewind live TV (Media3 engine): slim bar top-left, chip "-0:42 behind live"
    private LinearLayout tsBox;
    private TextView tsChip;
    private View tsFill;
    private FrameLayout tsTrack;
    private boolean tsPaused;
    private long tsHideAt;
    private final Runnable tsR = new Runnable() { @Override public void run() { tsUpdate(); } };

    // spike mode
    private int spike;
    private List<Integer> spikeIdx;
    private int spikeI;
    private boolean curDone;
    private File spikeFile;

    // network resilience (T8d)
    private static final String NET = "IPTV-net";
    private boolean reconnecting, simDrop, simStall, simDead, simDropDone, simStallDone;
    private int rc;                       // reconnect attempts used in the current outage
    private String curUrl, quality = "auto";
    private List<Hls.Variant> variants;   // ascending bandwidth; null until a master playlist was parsed
    private int curVar = -1;              // -1 = master (player adapts)
    private final ArrayList<Long> stallTimes = new ArrayList<>();
    private long lastNetEvent, lastRx, lastRxT, sessionT0, lastSummary, statSamples;
    private double mbps, mbpsSum;
    private int stallsTotal, reconnectsTotal;
    private boolean buffering;

    private final Runnable reconnectR = new Runnable() {
        @Override public void run() {
            if (rc >= 2) curVar = -1; // the chosen variant may be the problem: go back to the master
            if (curVar == -1) curUrl = null;
            play(playCh != null ? playCh : cur(), curUrl, true);
        }
    };
    private final Runnable showBufR = new Runnable() { @Override public void run() { if (buffering && playing) setPill("Buffering…"); } };
    private final Runnable longBufR = new Runnable() { @Override public void run() { if (buffering && playing) { Log.i(NET, "buffering > 10 s"); fail("error", "buffer>10s"); } } };
    private final Runnable tickR = new Runnable() { @Override public void run() { tick(); h.postDelayed(this, 1000); } };
    private final Runnable startR = new Runnable() { @Override public void run() { play(cur()); } };
    private Channel pendingAlt;
    private final Runnable altR = new Runnable() {
        @Override public void run() {
            Channel a = pendingAlt;
            pendingAlt = null;
            if (a == null) return;
            failover = true;
            play(a);
            failover = false;
            setPill("Trying another link…"); // play() hides the pill of a normal start
        }
    };
    private final Runnable skipR = new Runnable() {
        @Override public void run() {
            index = (index + dir + list.size()) % list.size();
            updateBanner();
            play(list.get(index));
        }
    };
    private final Runnable watchdog = new Runnable() {
        @Override public void run() { fail("timeout", ""); }
    };
    private final Runnable hideBanner = new Runnable() {
        @Override public void run() {
            final Runnable end = new Runnable() { @Override public void run() { banner.setVisibility(View.GONE); bHint.setVisibility(View.GONE); bVol.setVisibility(View.GONE); infoUp = false; stopBar(); wmUpdate(); } };
            if (!Ui.anim()) { end.run(); return; }
            banner.animate().alpha(0f).translationY(Ui.u(24)).setDuration(220).setInterpolator(Ui.DEC).withEndAction(end).start();
        }
    };
    private final Runnable hideToast = new Runnable() {
        @Override public void run() { if (pillKind == 2) { toast.setVisibility(View.GONE); pillKind = 0; } }
    };
    private final Runnable retryLoad = new Runnable() { @Override public void run() { load(); } };
    private final Runnable spikeNextR = new Runnable() { @Override public void run() { spikeNext(); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("iptv", MODE_PRIVATE);
        store = new Store(prefs);
        lk.manus.iptv.ui.wizard.Wizard.maybeStart(this);
        Ui.init(this);
        FavGuard.init(getFilesDir());
        // hidden test hooks: only honoured in a DEBUG build (Config.DEBUG); release ignores every extra
        final android.content.Intent in = getIntent();
        if (Config.DEBUG) {
            spike = in.getIntExtra("spike", 0);
            simDead = in.getBooleanExtra("simdead", false); // drop after 20 s / fail every reconnect / fake buffering
            simDrop = simDead || in.getBooleanExtra("simdrop", false);
            simStall = in.getBooleanExtra("simstall", false);
            simFailFirst = in.getBooleanExtra("failfirst", false); // first stream of a channel with duplicates fails once
            simFailNext = in.getIntExtra("failnext", 0);           // the next N starts fail; health is not recorded
            bgSkipSec = in.getIntExtra("bgskip", 0);               // N s after the guide opens, the playing stream "fails to start"
            if (in.hasExtra("autoskip")) dbgAutoSkip = in.getBooleanExtra("autoskip", false);
            if (in.hasExtra("quality")) store.setQuality(in.getStringExtra("quality")); // writes the real prefs
            if (in.hasExtra("mirror")) store.setMirror(in.getBooleanExtra("mirror", false));
            if (in.hasExtra("engine")) prefs.edit().putString("engine", in.getStringExtra("engine")).apply(); // media3 | mediaplayer
            dbgSub = in.getIntExtra("sub", -1);
            if (in.hasExtra("subsdef")) store.setSubsDefault(in.getBooleanExtra("subsdef", false)); // writes the real prefs
            Log.i(TAG, "DEBUG hooks on: failnext=" + simFailNext + " simdrop=" + simDrop + " sub=" + dbgSub);
        }
        health = Health.get(this);
        CloudData.get(this).start(new Runnable() {
            @Override public void run() { // worker thread: new snapshot -> re-rank next time a list opens, refresh the now/next views
                h.post(new Runnable() { @Override public void run() { if (isFinishing()) return; if (idx != null) idx.markDirty(); if (list != null) refreshNow(); guide.onCloudData(); } });
            }
        });
        prober = Prober.get(this);
        prober.setListener(new Prober.Listener() { @Override public void onProbed(String url, boolean ok) { if (idx != null) idx.markDirty(); } });
        buildUi();
        sources = new Sources(prefs, getFilesDir(), Config.DEBUG ? in.getStringExtra("src") : null); // "src" extra: DEBUG-only test hook for the built-in source
        message.setText("Loading channels…");
        load();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        tex = new TextureView(this);
        tex.setVisibility(View.GONE);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                texSurface = new Surface(st);
                tryPending();
                ensurePlaying();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (useTex) stopAll();
                if (texSurface != null) texSurface.release();
                texSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        root.addView(tex, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { fitTex(); }
        });

        message = Ui.tv(this, Ui.TITLE, Color.WHITE, false);
        message.setSingleLine(false);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));

        guide = new Guide(this, new Guide.Host() {
            @Override public Index index() { return idx; }
            @Override public Store store() { return store; }
            @Override public Sources sources() { return sources; }
            @Override public void sourcesChanged() { load(); }
            @Override public String playingUrl() { return list == null ? "" : cur().url; }
            @Override public String activeSection() { return zapSection; }
            @Override public int mainDropped() { return eng == null ? 0 : eng.droppedFrames(); }
            @Override public void onCommit(String id, String name, List<Channel> l, int pos) { commit(id, name, l, pos); }
            @Override public void onClose(boolean restore) { closeGuide(restore); }
            @Override public String netSpeed() { return speedText(); }
            @Override public void exitApp() { finish(); }
            @Override public void onOptions(Channel c) { openOptionsFor(c, false); }
            @Override public void pill(String text) { showToast(text, 2500); }
            @Override public void restoreFavourites(List<String> urls) { store.setFavs(urls); guide.onFavChanged(); undoUrl = null; }
            @Override public void edgesChanged() { applySafe(); }
        });
        root.addView(guide, new FrameLayout.LayoutParams(-1, -1));

        // banner: glass bar, number | logo | name + chips | clock, loading line underneath
        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.VERTICAL);
        bl.setBackground(Ui.rect(Ui.GLASS, 18));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.u(24), Ui.u(14), Ui.u(28), Ui.u(12));
        bNum = Ui.tv(this, Ui.DISPLAY, Ui.CYAN, true);
        bNum.setMinWidth(Ui.u(64));
        bNum.getPaint().setShader(new LinearGradient(0, 0, Ui.u(80), 0, Ui.VIOLET, Ui.CYAN, Shader.TileMode.CLAMP));
        bLogo = new ImageView(this);
        bLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bLogo.setBackground(Ui.rect(Ui.SURF, 10));
        bLogo.setPadding(Ui.u(4), Ui.u(4), Ui.u(4), Ui.u(4));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(Ui.u(18), 0, Ui.u(12), 0);
        bName = Ui.tv(this, Ui.TITLE, Ui.TXT, true);
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, Ui.u(8), 0, 0);
        bSec = Ui.tv(this, Ui.MICRO, Ui.TXT, true);
        bSec.setBackground(Ui.rect(0x667C5CFF, 12));
        bSec.setPadding(Ui.u(10), Ui.u(4), Ui.u(10), Ui.u(4));
        bInfo = Ui.tv(this, Ui.CAPTION, Ui.SEC, false);
        bInfo.setPadding(Ui.u(12), 0, 0, 0);
        bState = Ui.tv(this, Ui.CAPTION, Ui.CYAN, true);
        bState.setPadding(Ui.u(12), 0, 0, 0);
        meta.addView(bSec);
        meta.addView(bInfo);
        meta.addView(bState);
        bSpeed = Ui.tv(this, Ui.CAPTION, Ui.SEC, false);
        bSpeed.setPadding(Ui.u(16), 0, 0, 0);
        meta.addView(bSpeed);
        bCC = Ui.tv(this, Ui.MICRO, Ui.TXT, true);
        bCC.setText("CC");
        bCC.setBackground(Ui.rect(0x5500D4FF, 12));
        bCC.setPadding(Ui.u(10), Ui.u(4), Ui.u(10), Ui.u(4));
        bCC.setVisibility(View.GONE);
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(-2, -2);
        ccp.leftMargin = Ui.u(14);
        meta.addView(bCC, ccp);
        mid.addView(bName);
        mid.addView(meta);
        bNow = Ui.tv(this, Ui.BODY, Ui.TXT, true);
        bNow.setPadding(0, Ui.u(8), 0, 0);
        bNowBar = new NowBar(this);
        LinearLayout.LayoutParams nbp = new LinearLayout.LayoutParams(-1, Ui.u(3));
        nbp.topMargin = Ui.u(6);
        nbp.rightMargin = Ui.u(40);
        bNext = Ui.tv(this, Ui.CAPTION, Ui.SEC, false);
        bNext.setPadding(0, Ui.u(6), 0, 0);
        mid.addView(bNow);
        mid.addView(bNowBar, nbp);
        mid.addView(bNext);
        bNow.setVisibility(View.GONE); bNowBar.setVisibility(View.GONE); bNext.setVisibility(View.GONE);
        bHint = Ui.tv(this, Ui.CAPTION, Ui.SEC, false);
        bHint.setPadding(0, Ui.u(6), 0, 0);
        bHint.setVisibility(View.GONE);
        mid.addView(bHint);
        bVol = Ui.tv(this, Ui.CAPTION, Ui.CYAN, false);
        bVol.setPadding(0, Ui.u(6), 0, 0);
        bVol.setVisibility(View.GONE);
        mid.addView(bVol);
        bClock = Ui.tv(this, Ui.TITLE, Ui.TXT, false);
        row.addView(bNum);
        row.addView(bLogo, new LinearLayout.LayoutParams(Ui.u(96), Ui.u(54)));
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(bClock);
        bl.addView(row);
        FrameLayout line = new FrameLayout(this);
        line.setClipChildren(true);
        bBar = new View(this);
        bBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.VIOLET, Ui.CYAN}));
        bBar.setVisibility(View.INVISIBLE);
        line.addView(bBar, new FrameLayout.LayoutParams(Ui.u(220), Ui.u(3)));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Ui.u(3));
        ll.setMargins(Ui.u(18), 0, Ui.u(18), 0);
        bl.addView(line, ll);
        banner = bl;
        banner.setVisibility(View.GONE);

        // subtitle line: shares the bottom column with the banner, so it always sits above it (UX-SPEC 4)
        subTv = Ui.tv(this, Ui.BODY, Color.WHITE, false);
        subTv.setSingleLine(false);
        subTv.setMaxLines(3);
        subTv.setGravity(Gravity.CENTER);
        subTv.setBackground(Ui.rect(0x99000000, 10));
        subTv.setPadding(Ui.u(16), Ui.u(6), Ui.u(16), Ui.u(6));
        subTv.setVisibility(View.GONE);
        bottomStack = new LinearLayout(this);
        bottomStack.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.bottomMargin = Ui.u(12);
        bottomStack.addView(subTv, sp);
        bottomStack.addView(bl, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottomStack, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // the one pill: top-centre (status, messages, undo); a new one replaces the old
        toast = Ui.tv(this, Ui.BODY, Ui.TXT, true);
        toast.setBackground(Ui.ring(0xF2141A2A));
        toast.setPadding(Ui.u(28), Ui.u(12), Ui.u(28), Ui.u(12));
        toast.setSingleLine(false);
        toast.setMaxLines(2); // the T40 download message is long
        toast.setGravity(Gravity.CENTER);
        toast.setVisibility(View.GONE);
        root.addView(toast, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // number box: top-right, alone there
        numTv = Ui.tv(this, Ui.DISPLAY, Ui.TXT, true);
        numTv.setBackground(Ui.ring(0xF2141A2A));
        numTv.setPadding(Ui.u(28), Ui.u(10), Ui.u(28), Ui.u(10));
        numTv.setVisibility(View.GONE);
        root.addView(numTv, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        // T27: connecting ring: non-modal, centre (no dim; keys still zap)
        connBox = new LinearLayout(this);
        connBox.setOrientation(LinearLayout.VERTICAL);
        connBox.setGravity(Gravity.CENTER_HORIZONTAL);
        connBox.setBackground(Ui.ring(0xF2141A2A));
        connBox.setPadding(Ui.u(36), Ui.u(24), Ui.u(36), Ui.u(22));
        connRing = new Ring(this);
        connBox.addView(connRing, new LinearLayout.LayoutParams(Ui.u(64), Ui.u(64)));
        TextView ct = Ui.tv(this, Ui.BODY, Ui.TXT, true);
        ct.setText("Connecting…");
        ct.setEllipsize(null); connBox.setMinimumWidth(Ui.u(230)); // the 64dp ring alone made the box too narrow: "Conn…"
        ct.setPadding(0, Ui.u(14), 0, 0);
        connBox.addView(ct);
        connBox.setVisibility(View.GONE);
        root.addView(connBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // corner watermark: logo + one line, 60% opacity
        wm = new LinearLayout(this);
        wm.setGravity(Gravity.CENTER_VERTICAL);
        wmLogo = new ImageView(this);
        wmLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wm.addView(wmLogo, new LinearLayout.LayoutParams(Ui.u(64), Ui.u(40)));
        wmTitle = Ui.tv(this, Ui.CAPTION, Ui.TXT, false);
        wmTitle.setShadowLayer(4, 0, 1, Color.BLACK);
        wmTitle.setPadding(Ui.u(10), 0, 0, 0);
        wm.addView(wmTitle);
        wm.setVisibility(View.GONE);
        root.addView(wm, new FrameLayout.LayoutParams(-2, -2));

        // T30 seek chip: top-left, alone there
        tsBox = new LinearLayout(this);
        tsBox.setOrientation(LinearLayout.VERTICAL);
        tsBox.setBackground(Ui.ring(0xF2141A2A));
        tsBox.setPadding(Ui.u(20), Ui.u(12), Ui.u(20), Ui.u(14));
        tsChip = Ui.tv(this, Ui.BODY, Ui.TXT, true);
        tsBox.addView(tsChip);
        tsTrack = new FrameLayout(this);
        tsTrack.setBackground(Ui.rect(0x44FFFFFF, 3));
        tsFill = new View(this);
        tsFill.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.VIOLET, Ui.CYAN}));
        tsTrack.addView(tsFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams tkp = new LinearLayout.LayoutParams(Ui.u(360), Ui.u(6));
        tkp.topMargin = Ui.u(10);
        tsBox.addView(tsTrack, tkp);
        tsBox.setVisibility(View.GONE);
        root.addView(tsBox, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // modal layer (top): options / version / subtitle chooser and the failure card, one at a time, dimmed 60%, centred, <= 50% of the safe width
        modal = new Modal(this);
        failBox = new LinearLayout(this);
        failBox.setOrientation(LinearLayout.VERTICAL);
        failBox.setGravity(Gravity.CENTER_HORIZONTAL);
        failBox.setBackground(Ui.ring(0xF5141A2A));
        failBox.setPadding(Ui.u(36), Ui.u(28), Ui.u(36), Ui.u(28));
        TextView ft = Ui.tv(this, Ui.TITLE, Ui.TXT, true);
        ft.setText("Channel isn't responding");
        failBox.addView(ft);
        failSub = Ui.tv(this, Ui.CAPTION, Ui.SEC, false);
        failSub.setPadding(0, Ui.u(6), 0, Ui.u(22));
        failBox.addView(failSub);
        failRow = new LinearLayout(this);
        failRow.setOrientation(LinearLayout.VERTICAL);
        failBox.addView(failRow, new LinearLayout.LayoutParams(-1, -2));
        failBox.setVisibility(View.GONE);
        modal.add(failBox, 0);
        menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuBox.setBackground(Ui.ring(0xF2141A2A));
        menuBox.setPadding(Ui.u(16), Ui.u(14), Ui.u(16), Ui.u(14));
        menuTitle = Ui.tv(this, Ui.CAPTION, Ui.SEC, true);
        menuTitle.setPadding(Ui.u(8), 0, 0, Ui.u(8));
        menuBox.addView(menuTitle);
        menuBox.setVisibility(View.GONE);
        modal.add(menuBox, 0);
        root.addView(modal, new FrameLayout.LayoutParams(-1, -1));
        applySafe();
        setContentView(root);
    }

    /** Places everything that hugs an edge inside the title-safe area (also re-run when Settings > Screen edges changes). */
    private void applySafe() {
        int mx = Ui.marginX(), my = Ui.marginY(), top = my + Ui.u(8);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bottomStack.getLayoutParams();
        lp.setMargins(mx, 0, mx, my);
        bottomStack.setLayoutParams(lp);
        subTv.setMaxWidth(Ui.safeW() * 6 / 10);
        toast.setMaxWidth(Ui.safeW() * 6 / 10);
        lp = (FrameLayout.LayoutParams) toast.getLayoutParams(); lp.setMargins(0, top, 0, 0); toast.setLayoutParams(lp);
        lp = (FrameLayout.LayoutParams) numTv.getLayoutParams(); lp.setMargins(0, top, mx, 0); numTv.setLayoutParams(lp);
        lp = (FrameLayout.LayoutParams) tsBox.getLayoutParams(); lp.setMargins(mx, top, 0, 0); tsBox.setLayoutParams(lp);
        wmTitle.setMaxWidth(Ui.safeW() * 2 / 10);
        modal.relayout();
        wm.setVisibility(View.GONE);
    }

    // ---- playlist ----

    private void load() {
        sources.load(new Sources.Callback() {
            @Override public void onLoaded(final Sources.Loaded ld) {
                if (isFinishing()) return;
                final List<Channel> a = ld.merged;
                all = a;
                if (spike > 0) { if (spikeIdx == null) startSpike(); return; }
                new Thread(new Runnable() {
                    @Override public void run() {
                        long t = SystemClock.elapsedRealtime();
                        final List<Channel> fy = Index.forYou(a, store);
                        final Index ix = Index.build(ld, fy);
                        Log.i(TAG, "index built in " + (SystemClock.elapsedRealtime() - t) + " ms: " + ix.byUrl.size()
                                + " channels, " + ix.cats.size() + " categories, " + ix.countries.size() + " countries");
                        h.post(new Runnable() { @Override public void run() { if (!isFinishing()) applyIndex(ix); } });
                    }
                }, "index").start();
            }
            @Override public void onError() {
                if (isFinishing() || list != null) return;
                message.setVisibility(View.VISIBLE);
                message.setText("Can't load channels — retrying…");
                h.removeCallbacks(retryLoad);
                h.postDelayed(retryLoad, 20000);
            }
        });
    }

    private void applyIndex(Index ix) {
        idx = ix;
        String kill = Config.DEBUG ? getIntent().getStringExtra("kill") : null; // DEBUG: record 3 failed starts (= DEAD) for lk channels whose name contains one of these (comma separated)
        if (kill != null) for (Channel c : ix.all) {
            if (!c.country.equals("lk")) continue;
            for (String t : kill.toLowerCase(Locale.ROOT).split(",")) if (!t.isEmpty() && c.name.toLowerCase(Locale.ROOT).contains(t)) { for (int i = 0; i < 3; i++) health.record(c.url, false); Log.i(TAG, "kill " + c.name); }
            health.flushAsync();
        }
        Map<String, String> fnames = new HashMap<>(); // favourites' names for the safety snapshots (T33)
        for (String fu : store.favs()) { Channel fc = ix.byUrl.get(fu); if (fc != null) fnames.put(fu, fc.name); }
        FavGuard.rememberNames(fnames);
        ix.setSignals(new Rank(store, health));
        ix.refresh(store);
        guide.onIndexChanged();
        String sec = Config.DEBUG && getIntent().hasExtra("sec") && list == null ? getIntent().getStringExtra("sec") : store.section(); // DEBUG: open on this section without saving it
        List<Channel> nl = ix.list(sec, store);
        if (nl.isEmpty()) { sec = "foryou"; nl = ix.forYou; }
        if (nl.isEmpty()) {
            message.setVisibility(View.VISIBLE);
            message.setText(ix.all.isEmpty() ? (Config.PLAY ? "No channels yet\n\nAdd your own M3U playlist in Settings \u2192 Sources\nPress OK to open Settings" : "No channels yet \u2014 add a playlist in Settings \u2192 Sources\n\nPress OK to open Settings") : "No channels found");
            return;
        }
        if (list == null) {
            zapSection = sec;
            zapName = ix.title(sec);
            list = nl;
            String last = prefs.getString("last", "");
            String tune = Config.DEBUG ? getIntent().getStringExtra("tune") : null; // DEBUG: start on the first channel whose name contains this
            if (tune != null) {
                List<Channel> al = ix.list("all", store);
                for (Channel c : al) if (c.name.toLowerCase(Locale.ROOT).contains(tune.toLowerCase(Locale.ROOT))) {
                    last = c.url; sec = "all"; nl = al; zapSection = sec; zapName = ix.title(sec); list = nl;
                    String dup = getIntent().getStringExtra("dupalt"); // DEBUG: pretend this other channel is a second stream of the tuned one
                    if (dup != null) for (Channel d : ix.all) if (d.name.toLowerCase(Locale.ROOT).contains(dup.toLowerCase(Locale.ROOT))) { Dedup.register(c, d); break; }
                    break;
                }
            }
            index = 0;
            for (int i = 0; i < nl.size(); i++) if (nl.get(i).url.equals(last)) { index = i; break; }
            message.setVisibility(View.GONE);
            ensurePlaying();
        } else { // refreshed playlist: keep the current channel and section
            String curUrl = list.get(index).url;
            if (zapSection.equals("search")) return; // a search result list is not part of the index
            nl = ix.list(zapSection, store);
            if (nl.isEmpty()) { zapSection = "foryou"; zapName = "For You"; nl = ix.forYou; }
            list = nl;
            index = Math.min(index, nl.size() - 1);
            for (int i = 0; i < nl.size(); i++) if (nl.get(i).url.equals(curUrl)) { index = i; break; }
        }
    }

    // ---- playback ----

    private void ensurePlaying() {
        if (list == null || list.isEmpty() || !(useTex ? texSurface != null : surfaceReady) || paused || eng != null || gaveUp || userPaused) return;
        if (h.hasCallbacks(startR) || h.hasCallbacks(skipR)) return;
        if (spike > 0) { spikeNext(); return; }
        updateBanner();
        play(cur());
    }

    private Channel cur() { return previewCh != null ? previewCh : list.get(index); }

    private void play(Channel c) { play(c, null, false); }

    /** keep = reconnect or variant switch of the same channel: keeps the outage/variant state and stays quiet. */
    private void play(Channel c, String url, boolean keep) {
        h.removeCallbacks(startR);
        h.removeCallbacks(watchdog);
        h.removeCallbacks(reconnectR);
        h.removeCallbacks(showBufR);
        h.removeCallbacks(longBufR);
        h.removeCallbacks(connR);
        hideFail();
        connBox.setVisibility(View.GONE);
        playing = false;
        buffering = false;
        curDone = false;
        playCh = c;
        if (!keep && !failover) altFor = null;
        prober.setPaused(true); // probes wait while a channel is starting
        wmUpdate();
        clearSubs();
        final int g = ++gen;
        t0 = SystemClock.elapsedRealtime();
        tsReset();
        if (!keep) {
            reconnecting = false; rc = 0; variants = null; curVar = -1; curUrl = null; stallTimes.clear();
            lastNetEvent = t0;
            aGuard.reset(); audioTrips = 0; infoUp = false;
            quality = store.quality();
            hidePill();
        }
        // mirror mode: swap the video target between channel starts; the unused view is hidden
        boolean want = store.mirror();
        if (want != useTex) {
            useTex = want;
            tex.setVisibility(want ? View.VISIBLE : View.GONE);
            sv.setVisibility(want ? View.GONE : View.VISIBLE);
            Log.i(TAG, "mirror mode " + (want ? "on" : "off"));
        }
        Log.i(TAG, "start #" + (index + 1) + " " + c.name + " " + Redact.host(c.url) + (keep ? " (retry)" : ""));
        if (spike == 0 && !guide.isOpen() && !keep) { setState("Loading…"); showBanner(); }
        h.postDelayed(watchdog, spike > 0 ? SPIKE_TIMEOUT_MS : TIMEOUT_MS);
        if (!keep && spike == 0) h.postDelayed(connR, 1500);
        final Engine old = eng;
        eng = null;
        final Channel cc = c;
        final String u = url;
        final boolean k = keep;
        if (old == null) { begin(cc, u, k, g); return; }
        // the old player must be fully released before a new one can attach to the same surface
        old.release(new Runnable() { @Override public void run() { if (g == gen) begin(cc, u, k, g); } });
    }

    private boolean media3() { return !m3Broken && !"mediaplayer".equals(prefs.getString("engine", "media3")); }

    /** MediaPlayer path: auto: start on the master at once and look at its variants in the background; best/saver: wait for the playlist. */
    /** Media3 picks variants itself (quality limits); only MediaPlayer needs the master playlist juggling below. */
    private void begin(final Channel c, String url, boolean keep, final int g) {
        if (url != null || keep || spike > 0 || media3() || !wantsHls(c.url)) { startPlayer(c, url != null ? url : c.url, g); return; }
        final boolean auto = !"best".equals(quality) && !"saver".equals(quality);
        if (auto) startPlayer(c, c.url, g);
        new Thread(new Runnable() {
            @Override public void run() {
                Hls.Fetched f = null;
                try { f = Hls.fetch(c.url, c.userAgent, c.referrer); } catch (Exception e) { Log.i(NET, "master fetch failed " + e.getClass().getSimpleName()); }
                final List<Hls.Variant> v = f == null ? null : Hls.parseMaster(f.body, f.url);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (g != gen) return;
                        if (v != null && !v.isEmpty()) {
                            variants = v;
                            StringBuilder b = new StringBuilder();
                            for (Hls.Variant x : v) b.append(' ').append(x.label());
                            Log.i(NET, "master " + Redact.host(c.url) + " variants:" + b);
                        }
                        if (auto) return;
                        Hls.Variant pick = v == null ? null : Hls.pick(v, quality);
                        if (pick != null) { curVar = v.indexOf(pick); curUrl = pick.url; Log.i(NET, quality + " -> " + pick.label() + " " + Redact.host(pick.url)); }
                        else Log.i(NET, quality + " -> no master, playing as is");
                        h.removeCallbacks(watchdog);
                        h.postDelayed(watchdog, TIMEOUT_MS);
                        startPlayer(c, pick != null ? pick.url : c.url, g);
                    }
                });
            }
        }, "hls").start();
    }

    /** Plain video endpoints are never probed: a second connection can cost a login slot. */
    private static boolean wantsHls(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u.startsWith("http") && !(u.endsWith(".ts") || u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".flv") || u.endsWith(".mp3") || u.endsWith(".aac"));
    }

    /** Mirror mode: size the TextureView to the video's aspect inside the screen (bars instead of stretching). */
    private void fitTex() {
        View root = (View) tex.getParent();
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0) return;
        int w = W, hh = H;
        if (vw > 0 && vh > 0) { hh = (int) ((long) W * vh / vw); if (hh > H) { hh = H; w = (int) ((long) H * vw / vh); } }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tex.getLayoutParams();
        if (lp.width != w || lp.height != hh) { lp.width = w; lp.height = hh; tex.setLayoutParams(lp); }
    }

    private boolean targetReady() { return useTex ? texSurface != null : surfaceReady; }

    private void tryPending() {
        Object[] p = pendingStart;
        if (p == null || !targetReady()) return;
        pendingStart = null;
        if ((Integer) p[2] == gen) startPlayer((Channel) p[0], (String) p[1], gen);
    }

    private void startPlayer(Channel c, String url, final int g) {
        if (!targetReady()) { pendingStart = new Object[]{c, url, g}; return; } // the switched view has no surface yet
        curUrl = url.equals(c.url) ? null : url;
        if (simDead && reconnecting) { h.post(new Runnable() { @Override public void run() { if (g == gen) fail("error", "simdead"); } }); return; }
        if (simFailFirst && !simFailDone && previewCh == null && !Dedup.alternates(c).isEmpty() && c == list.get(index)) {
            h.post(new Runnable() { @Override public void run() { if (g == gen) { simFailDone = true; fail("error", "simfailfirst"); } } }); // DEBUG: pretend the first stream of a duplicated channel never starts
            return;
        }
        if (simFailNext > 0 && previewCh == null) {
            h.post(new Runnable() { @Override public void run() { if (g == gen && simFailNext > 0) { simFailNext--; fail("error", "simfailnext"); } } }); // DEBUG: this start "fails"; health is not recorded
            return;
        }
        final Engine.Listener ls = new Engine.Listener() {
            @Override public void onPlaying() { if (g == gen) PlayerActivity.this.onPlaying(); }
            @Override public void onBuffering(boolean start) { if (g != gen) return; if (start) onBufferStart(); else onBufferEnd(); }
            @Override public void onError(String detail) { if (g == gen) fail("error", detail); }
            @Override public void onVideoSize(int w, int hh) { if (g == gen) { vw = w; vh = hh; fitTex(); Log.i(TAG, "video " + w + "x" + hh); } }
            @Override public void onTracks() { if (g == gen && playing) scanTracks(); }
            @Override public void onCues(String t) { if (g == gen) { curCue = t; trCue = ""; if (trOn && !t.isEmpty()) translateCue(t); showCue(); } }
            @Override public void onAudioDrop() { if (g == gen) audioDrop(); }
        };
        try {
            Engine e;
            try { e = media3() ? new Media3Engine(this, ls) : new MediaPlayerEngine(this, ls); eng = e; prepareEngine(e, c, url); }
            catch (LinkageError le) { // Media3 classes missing in this build: fall back for good
                Log.w(TAG, "Media3 unavailable, using MediaPlayer: " + le);
                m3Broken = true;
                if (eng != null) eng.release(null);
                e = new MediaPlayerEngine(this, ls); eng = e; prepareEngine(e, c, url);
            }
            Log.i(TAG, "engine " + e.name());
        } catch (Exception e) {
            Log.w(TAG, "play failed", e); fail("error", e.getClass().getSimpleName());
        }
    }

    private void prepareEngine(Engine e, Channel c, String url) throws Exception {
        String q = "auto".equals(quality) && Ui.low() && !store.qualityChosen() ? "auto720" : quality; // Low-tier default: 720p cap until the user picks a quality
        e.setEven(store.evenVolume());
        e.setVolume(previewCh == null ? store.volume(c.url) : 1f);
        e.prepare(c, url, q, useTex ? null : holder, useTex ? texSurface : null);
    }

    private void onPlaying() {
        final boolean wasReconnect = reconnecting;
        playing = true;
        fails = 0;
        h.removeCallbacks(connR);
        connBox.setVisibility(View.GONE);
        prober.setPaused(false);
        if (playCh != null && spike == 0) {
            health.record(playCh.url, true);
            health.flushAsync();
            if (idx != null) idx.markDirty();
        }
        if (!reconnecting) hidePill();
        if (rc > 0) Log.i(NET, "reconnected after " + rc + " tries");
        if (reconnecting) { reconnecting = false; rc = 0; hidePill(); }
        h.removeCallbacks(watchdog);
        final int g = gen;
        if (simDrop && !simDropDone && spike == 0 && previewCh == null) {
            simDropDone = true;
            h.postDelayed(new Runnable() { @Override public void run() { if (g == gen) fail("error", "simdrop"); } }, 20000);
        }
        if (simStall && !simStallDone && spike == 0 && previewCh == null) {
            simStallDone = true;
            h.postDelayed(new Runnable() {
                @Override public void run() { if (g == gen) { Log.i(NET, "simstall: fake buffering 5 s"); onBufferStart(); } }
            }, 8000);
            h.postDelayed(new Runnable() { @Override public void run() { if (g == gen) onBufferEnd(); } }, 13000);
        }
        Log.i(TAG, "playing after " + (SystemClock.elapsedRealtime() - t0) + " ms");
        if (spike > 0) { spikeRecord("ok", ""); return; }
        if (previewCh == null) {
            prefs.edit().putString("last", list.get(index).url).apply();
            store.pushRecent(list.get(index).url);
            if (!wasReconnect && playCh != null) { store.recordPlay(playCh.url); if (idx != null) idx.markDirty(); }
            wmArm = true;
        }
        if (eng != null && eng.smart()) tsUpdate();
        scanTracks();
        h.removeCallbacks(rescanR);
        h.postDelayed(rescanR, 2500);
        setState("");
        if (!hintShown && previewCh == null && store.recents().size() > 1) { hintShown = true; bHint.setText("0 = previous channel"); bHint.setVisibility(View.VISIBLE); }
        showBanner();
        wmUpdate();
    }

    private void fail(String result, String detail) {
        h.removeCallbacks(watchdog);
        h.removeCallbacks(connR);
        connBox.setVisibility(View.GONE);
        h.removeCallbacks(showBufR);
        h.removeCallbacks(longBufR);
        gen++;
        releasePlayer();
        tsReset();
        final boolean was = playing;
        playing = false;
        buffering = false;
        Log.i(TAG, "fail " + result + " " + detail);
        if (spike > 0) { spikeRecord(result, detail); return; }
        prober.setPaused(false);
        boolean sim = Config.DEBUG && detail.startsWith("sim"); // DEBUG-simulated failures never touch the saved health
        if (!was && !reconnecting && playCh != null && !sim) { // never started (a drop after playing is not the stream's fault)
            health.record(playCh.url, false);
            health.flushAsync();
            if (idx != null) idx.markDirty();
        }
        if (previewCh != null) { // preview never auto-skips or reconnects
            hidePill();
            guide.setPreviewFailed();
            return;
        }
        if (was || reconnecting) { reconnect(); return; } // it was working: stay on this channel
        hidePill();
        Channel rep = list.get(index); // same channel, other streams (duplicates folded into this row): try them before skipping
        List<Channel> alts = Dedup.alternates(rep);
        if (altFor != rep) { altFor = rep; altI = 0; }
        if (!autoSkip()) { showFail(rep, alts); return; }
        if (altI < alts.size()) {
            Channel a = alts.get(altI++);
            Log.i(TAG, "trying alternate " + altI + "/" + alts.size() + " " + a.name + " " + Redact.host(a.url));
            pendingAlt = a;
            setPill("Trying another link…");
            h.postDelayed(altR, 500); // the failed player is still being released: wait before reusing the surface
            return;
        }
        altFor = null;
        if (++fails >= MAX_FAILS) {
            gaveUp = true;
            showToast("Several channels failed — check your internet. Press OK to retry.", 0);
            return;
        }
        showToast("Channel unavailable — skipping", 1500);
        h.postDelayed(skipR, 1500);
    }

    /** Backoff 1, 2, 4, 8 s on the same channel, then give up without skipping. */
    private void reconnect() {
        if (rc >= 4) {
            reconnecting = false;
            gaveUp = true;
            hidePill();
            Log.i(NET, "reconnect gave up");
            showToast("Channel stopped — press OK to retry", 0);
            return;
        }
        long d = 1000L << rc++;
        reconnecting = true;
        reconnectsTotal++;
        Log.i(NET, "reconnect try " + rc + "/4 in " + d + " ms");
        setPill("Reconnecting…");
        h.removeCallbacks(reconnectR);
        h.postDelayed(reconnectR, d);
    }

    private void onBufferStart() {
        if (!playing) return; // start-up buffering is covered by "Loading…"
        buffering = true;
        prober.setPaused(true);
        recordStall();
        h.removeCallbacks(showBufR);
        h.removeCallbacks(longBufR);
        h.postDelayed(showBufR, 400);
        h.postDelayed(longBufR, 10000);
    }

    private void onBufferEnd() {
        buffering = false;
        prober.setPaused(false);
        h.removeCallbacks(showBufR);
        h.removeCallbacks(longBufR);
        if (!reconnecting) hidePill();
    }

    /** 3 stalls in 60 s on auto: one variant down. */
    private void recordStall() {
        long now = SystemClock.elapsedRealtime();
        stallsTotal++;
        Log.i(NET, "stall " + stallsTotal);
        lastNetEvent = now;
        stallTimes.add(now);
        while (!stallTimes.isEmpty() && now - stallTimes.get(0) > 60000) stallTimes.remove(0);
        if (stallTimes.size() >= 3 && previewCh == null && variants != null && variants.size() > 1 && quality.equals("auto")) {
            stallTimes.clear();
            int to = step(curVar == -1 ? variants.size() - 1 : curVar, -1);
            if (to >= 0) switchVariant(to, "Lower quality — slow connection");
        }
    }

    /** Nearest variant in direction d (+1/-1) with a different bandwidth than variants[from]; -1 if none. */
    private int step(int from, int d) {
        int bw = variants.get(from).bandwidth;
        for (int i = from + d; i >= 0 && i < variants.size(); i += d) if (variants.get(i).bandwidth != bw) return i;
        return -1;
    }

    private void switchVariant(int to, String msg) {
        curVar = to;
        Hls.Variant v = to < 0 ? null : variants.get(to);
        lastNetEvent = SystemClock.elapsedRealtime();
        Log.i(NET, "variant -> " + (v == null ? "master" : v.label() + " " + Redact.host(v.url)));
        showToast(msg, 3500);
        reconnecting = true; // a failed start of the new variant retries instead of skipping the channel
        play(playCh != null ? playCh : cur(), v == null ? null : v.url, true);
    }

    /** Status pill (Buffering, Reconnecting...): the one pill, replaces whatever shows. */
    private void setPill(String s) {
        h.removeCallbacks(hideToast);
        toast.animate().cancel();
        toast.setText(s);
        toast.setAlpha(1f);
        toast.setVisibility(View.VISIBLE);
        pillKind = 1;
    }

    /** Ends a status pill only; a message pill keeps its own timer. */
    private void hidePill() { if (pillKind == 1) { toast.setVisibility(View.GONE); pillKind = 0; } }

    private void clearToast() { h.removeCallbacks(hideToast); if (pillKind == 2) { toast.setVisibility(View.GONE); pillKind = 0; } }

    // ---- speed meter + stats (once a second) ----

    private void tick() {
        if (eng != null) eng.setEven(store.evenVolume()); // Settings toggle applies within a second
        long now = SystemClock.elapsedRealtime(), rx = TrafficStats.getUidRxBytes(Process.myUid());
        if (rx != TrafficStats.UNSUPPORTED && lastRxT > 0 && now > lastRxT) {
            double inst = (rx - lastRx) * 8.0 / ((now - lastRxT) / 1000.0) / 1e6;
            mbps = mbps == 0 ? inst : mbps * 0.7 + inst * 0.3;
            if (playing) { mbpsSum += mbps; statSamples++; }
        }
        lastRx = rx; lastRxT = now;
        if (bSpeed != null && banner.getVisibility() == View.VISIBLE) bSpeed.setText(speedText());
        if (playing && previewCh == null && variants != null && curVar != -1 && quality.equals("auto") && now - lastNetEvent >= 180000) {
            int to = step(curVar, 1);
            switchVariant(to, "Trying higher quality"); // no higher variant left = back to the master
        }
        if (now - lastSummary >= 300000) {
            lastSummary = now;
            double mins = Math.max(1, (now - sessionT0) / 60000.0);
            Log.i(NET, String.format(Locale.US, "stalls/min=%.2f reconnects=%d avgMbps=%.1f", stallsTotal / mins, reconnectsTotal, statSamples == 0 ? 0 : mbpsSum / statSamples));
        }
    }

    /** e.g. "4.2 Mbps ●●●" (good >= 3, fair 1-3, poor < 1). Also meant for the guide preview card. */
    public String speedText() {
        if (mbps <= 0) return "";
        return String.format(Locale.US, "%.1f Mbps %s", mbps, mbps >= 3 ? "●●●" : mbps >= 1 ? "●●○" : "●○○");
    }

    private void releasePlayer() {
        Engine old = eng;
        eng = null;
        if (old != null) old.release(null);
    }

    private void stopAll() {
        h.removeCallbacks(startR);
        h.removeCallbacks(skipR);
        h.removeCallbacks(altR);
        h.removeCallbacks(watchdog);
        h.removeCallbacks(spikeNextR);
        h.removeCallbacks(reconnectR);
        h.removeCallbacks(showBufR);
        h.removeCallbacks(longBufR);
        h.removeCallbacks(connR);
        gen++;
        releasePlayer();
        tsReset();
        playing = false;
        reconnecting = false;
        buffering = false;
        hidePill();
    }

    // ---- zapping / keys ----

    private void zap(int d) {
        if (list == null || list.isEmpty()) return;
        zapTo((index + d + list.size()) % list.size(), d);
    }

    private void zapTo(int ni, int d) {
        if (!playing && h.hasCallbacks(watchdog)) Log.i(TAG, "start abandoned by a zap: not recorded as a failure");
        clearPause();
        hideFail();
        h.removeCallbacks(connR);
        connBox.setVisibility(View.GONE);
        dir = d;
        index = ni;
        fails = 0;
        gaveUp = false;
        gen++; // ignore callbacks of the channel we are leaving
        h.removeCallbacks(skipR);
        h.removeCallbacks(altR);
        h.removeCallbacks(watchdog);
        h.removeCallbacks(reconnectR);
        reconnecting = false;
        hidePill();
        clearToast();
        updateBanner();
        setState("Loading…");
        showBanner();
        h.removeCallbacks(startR);
        h.postDelayed(startR, DEBOUNCE_MS);
    }

    private boolean autoSkip() { return dbgAutoSkip != null ? dbgAutoSkip : store.autoSkip(); }

    /** OK needs press/long-press tracking, so keys are intercepted before the views see them. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (spike > 0 || list == null) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode(), a = e.getAction();
        boolean first = a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        if (guide.isOpen()) guide.touch();
        if (guide.modalUp()) return guide.modalKey(e) || super.dispatchKeyEvent(e); // remote-keys modals own every key; volume stays with the system
        if (first && !guide.rawKeys()) logKey(e);
        boolean vol = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (failUp) { if (!vol && a == KeyEvent.ACTION_DOWN) failKey(k, e.getRepeatCount()); return !vol || super.dispatchKeyEvent(e); }
        if (menuBox.getVisibility() == View.VISIBLE) { if (a == KeyEvent.ACTION_DOWN) menuKey(k); return true; } // the chooser owns every key
        String act = Keys.action(k);
        if (act != null && (!guide.rawKeys() || act.equals(Keys.SEARCH))) { if (first) doAction(act); return true; }
        if (guide.rawKeys() && k != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e); // text boxes + settings handle their own keys
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (num.length() > 0 && !guide.isOpen()) { if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) commitNum(); return true; }
            if (a == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) okDown = true;
                else if (okDown) { okDown = false; toggleFav(); }
            } else if (okDown) { okDown = false; shortOk(); }
            return true;
        }
        if (num.length() > 0 && !guide.isOpen() && k == KeyEvent.KEYCODE_BACK) { // Back only cancels the number being typed
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) cancelNum();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK && guide.isOpen()) {
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) guide.onBack();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (spike > 0) return super.onKeyDown(k, e);
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { if (!guide.isOpen() && e.getRepeatCount() == 0) numKey(k - KeyEvent.KEYCODE_0); return true; }
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) { if (!guide.isOpen() && e.getRepeatCount() == 0) numKey(k - KeyEvent.KEYCODE_NUMPAD_0); return true; }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: if (!guide.isOpen()) zap(1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (!guide.isOpen()) zap(-1); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: if (guide.isOpen()) guide.page(1); else zap(1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: if (guide.isOpen()) guide.page(-1); else zap(-1); return true;
            case KeyEvent.KEYCODE_PAGE_UP: if (guide.isOpen()) guide.page(-1); else zap(1); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN: if (guide.isOpen()) guide.page(1); else zap(-1); return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL: if (!guide.isOpen() && e.getRepeatCount() == 0) prevChannel(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (infoUp && !guide.isOpen() && volKey(-1)) return true;
                if (e.getRepeatCount() == 0 && !(tsBar() && seekRel(-10000))) openGuide(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (infoUp && !guide.isOpen() && volKey(1)) return true;
                if (!guide.isOpen() && e.getRepeatCount() == 0 && !(tsBar() && eng != null && eng.behindMs() >= 1500 && seekRel(10000))) openOptions(); return true;
            case KeyEvent.KEYCODE_BACK: // never exits: full-screen Back opens the guide on the playing channel (exit = Settings > Exit)
                if (e.getRepeatCount() == 0 && !guide.isOpen()) openGuide();
                return true;
            default: return super.onKeyDown(k, e);
        }
    }

    @Override public void onBackPressed() { } // exit only through Settings > Exit

    // ---- number keys + last channel ----

    private void numKey(int d) {
        if (list == null || num.length() >= 4) return;
        num.append(d);
        numTv.setText(num.toString());
        numTv.setVisibility(View.VISIBLE);
        wmUpdate();
        h.removeCallbacks(numR);
        h.postDelayed(numR, 2000);
    }

    private void cancelNum() {
        h.removeCallbacks(numR);
        num.setLength(0);
        numTv.setVisibility(View.GONE);
        wmUpdate();
    }

    private void commitNum() {
        String s = num.toString();
        cancelNum();
        if (s.isEmpty() || list == null || list.isEmpty()) return;
        if (s.equals("0")) { prevChannel(); return; }
        int n = Integer.parseInt(s);
        if (n < 1 || n > list.size()) { showToast("No channel " + s, 2000); return; }
        if (n - 1 == index) { showBanner(); return; }
        zapTo(n - 1, 1);
    }

    /** Toggle to the channel watched before this one (recents[1]); switches list if it is not in the current one. */
    private void prevChannel() {
        if (list == null || idx == null) return;
        String curU = list.get(index).url;
        Channel c = null;
        for (String u : store.recents()) if (!u.equals(curU) && (c = idx.byUrl.get(u)) != null) break;
        if (c == null) { showToast("No previous channel", 1800); return; }
        int pos = list.indexOf(c);
        if (pos >= 0) { zapTo(pos, 1); return; }
        String sec = !c.country.isEmpty() && idx.countries.containsKey(c.country) ? "cc:" + c.country : "all";
        List<Channel> l = idx.list(sec, store);
        pos = l.indexOf(c);
        if (pos < 0) { sec = "all"; l = idx.list(sec, store); pos = l.indexOf(c); }
        if (pos < 0) { showToast("No previous channel", 1800); return; }
        commit(sec, idx.title(sec), l, pos);
    }

    private void shortOk() {
        if (guide.isOpen()) { guide.activate(); return; }
        if (tsPaused) { resumeVideo(); return; }
        if (tsBar() && eng != null && eng.behindMs() >= 1500) { goLive(); return; }
        if (gaveUp) {
            gaveUp = false;
            fails = 0;
            clearToast();
            updateBanner();
            play(cur());
        } else openGuide();
    }

    // ---- guide / favourites ----

    private void openSearch() {
        if (list == null) return;
        if (idx == null) { showToast("Loading channels…", 1500); return; }
        prepGuide();
        guide.openSearch();
        wmUpdate();
    }

    @Override public boolean onSearchRequested() { openSearch(); return true; }

    private void openGuide() {
        if (list == null) { // nothing to play yet (no sources): OK opens Settings so a playlist can be added
            if (idx != null && idx.all.isEmpty()) { prepGuide(); guide.openSettings(); wmUpdate(); }
            return;
        }
        if (idx == null) { showToast("Loading channels…", 1500); return; }
        prepGuide();
        guide.open();
        wmUpdate();
        if (Config.DEBUG && bgSkipSec > 0) { h.removeCallbacks(bgSkipR); h.postDelayed(bgSkipR, bgSkipSec * 1000L); bgSkipSec = 0; }
    }

    private void prepGuide() {
        h.removeCallbacks(hideBanner);
        banner.setVisibility(View.GONE);
        stopBar();
        previewCh = null;
    }

    // ---- pause / rewind live TV (T30) ----

    private boolean tsOk() { return eng != null && eng.smart() && playing && previewCh == null && !guide.isOpen(); }

    private void tsReset() {
        h.removeCallbacks(tsR);
        tsPaused = false; tsHideAt = 0;
        tsBox.setVisibility(View.GONE);
    }

    /** Bar visible = Left / Right rewind and fast-forward instead of opening the guide / options. */
    private boolean tsBar() { return tsBox.getVisibility() == View.VISIBLE && tsBox.getChildAt(1).getVisibility() == View.VISIBLE; }

    private static String mmss(long ms) { long s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60); }

    /** Shows the full bar for ms (0 = until resumed). */
    private void tsShow(long ms) {
        tsHideAt = ms == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() + ms;
        tsUpdate();
    }

    private void tsUpdate() {
        h.removeCallbacks(tsR);
        Engine e = eng;
        if (!tsOk() || e == null) { tsBox.setVisibility(View.GONE); return; }
        int was = tsBox.getVisibility();
        long behind = e.behindMs(), win = e.windowMs(), now = SystemClock.elapsedRealtime();
        boolean full = tsPaused || now < tsHideAt;
        if (full || behind >= 3000) {
            String t = behind >= 1500 ? "\u2212" + mmss(behind) + " behind live" + (full ? "   \u00b7   OK: Go live" : "") : "LIVE";
            tsChip.setText((tsPaused ? "\u275a\u275a  " : "") + t);
            tsChip.setTextColor(behind >= 1500 ? Ui.CYAN : Ui.TXT);
            tsTrack.setVisibility(full ? View.VISIBLE : View.GONE);
            if (full && win > 0) {
                int w = Math.round(Ui.u(360) * Math.min(1f, Math.max(0f, e.positionMs() / (float) win)));
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tsFill.getLayoutParams();
                if (lp.width != w) { lp.width = w; tsFill.setLayoutParams(lp); }
            }
            tsBox.setVisibility(View.VISIBLE);
        } else tsBox.setVisibility(View.GONE);
        if (was != tsBox.getVisibility()) wmUpdate();
        h.postDelayed(tsR, 500);
    }

    /** Left / Right / FF / Rew. Returns false when the engine can't (the caller falls back to its old meaning). */
    private boolean seekRel(long ms) {
        if (!tsOk()) return false;
        long win = eng.windowMs(), pos = eng.positionMs();
        if (win <= 0) { showToast("Rewind isn't available on this channel", 2000); return true; }
        if (ms < 0 && pos < 10000) { // how much rewind this stream really allows
            if (pos < 1000) { showToast("Rewind isn't available on this channel", 2000); tsShow(2500); return true; }
            showToast("This channel allows " + pos / 1000 + " s rewind", 3000);
        }
        long before = eng.behindMs();
        eng.seekBy(ms);
        Log.i(TAG, "seek " + ms / 1000 + " s, behind live " + before + " -> " + eng.behindMs() + " ms, window " + win + " ms, was at " + pos + " ms");
        tsShow(4000);
        return true;
    }

    private void goLive() {
        if (!tsOk()) return;
        eng.goLive();
        tsPaused = false;
        Log.i(TAG, "go live");
        tsShow(2500);
    }

    // ---- remote-key actions (T24) ----

    private void logKey(KeyEvent e) {
        int k = e.getKeyCode();
        String d = Keys.describe(k);
        Log.i(KEYTAG, KeyEvent.keyCodeToString(k) + " scan=" + e.getScanCode() + (d == null ? " UNMAPPED" : " -> " + d));
    }

    private void doAction(String act) {
        if (idx == null && !act.equals(Keys.PAUSE) && !act.equals(Keys.PLAY) && !act.equals(Keys.TOGGLE)) { showToast("Loading channels…", 1500); return; }
        switch (act) {
            case Keys.FAV: if (!undoFav()) toggleFavOf(guide.isOpen() ? guide.selected() : cur()); break;
            case Keys.SEARCH: openSearch(); break;
            case Keys.INFO:
                if (guide.isOpen()) break;
                updateBanner();
                showBanner();
                infoUp = playing && previewCh == null;
                if (infoUp) showVol();
                if (playing) { h.removeCallbacks(hideBanner); h.postDelayed(hideBanner, 6000); }
                break;
            case Keys.GUIDE: if (guide.isOpen()) guide.dismiss(true); else openGuide(); break;
            case Keys.CATS: prepGuide(); guide.openCategories(); wmUpdate(); break;
            case Keys.SETTINGS: prepGuide(); guide.openSettings(); wmUpdate(); break;
            case Keys.SUBS: openSubMenu(); break;
            case Keys.OPTIONS:
                if (!guide.isOpen()) openOptions();
                else if (!guide.onLongOk()) { Channel c = guide.selected(); if (c != null) openOptionsFor(c, false); }
                break;
            case Keys.TOGGLE: if (userPaused || tsPaused) resumeVideo(); else pauseVideo(); break;
            case Keys.FF: if (guide.isOpen() || !seekRel(10000)) { if (guide.isOpen()) guide.page(1); else zap(1); } break;
            case Keys.RW: if (guide.isOpen() || !seekRel(-10000)) { if (guide.isOpen()) guide.page(-1); else zap(-1); } break;
            case Keys.PAUSE: pauseVideo(); break;
            case Keys.PLAY: resumeVideo(); break;
            case Keys.NEXT: if (guide.isOpen()) guide.page(1); else zap(1); break;
            case Keys.PREV: if (guide.isOpen()) guide.page(-1); else zap(-1); break;
            default: break;
        }
    }

    /** Media3: a real pause (the buffer keeps filling, Play resumes at the same point). MediaPlayer: pause stops the video, Play starts the channel again. */
    private void pauseVideo() {
        if (userPaused || tsPaused || list == null) return;
        if (tsOk()) {
            eng.pause();
            tsPaused = true;
            Log.i(TAG, "paused (live window " + eng.windowMs() + " ms, pos " + eng.positionMs() + " ms)");
            tsShow(0);
            return;
        }
        userPaused = true;
        h.removeCallbacks(startR); h.removeCallbacks(skipR); h.removeCallbacks(altR); h.removeCallbacks(reconnectR); h.removeCallbacks(connR);
        hideFail();
        connBox.setVisibility(View.GONE);
        stopAll();
        gaveUp = false;
        setState("");
        message.setText("Paused — press Play");
        message.setBackgroundColor(0x99000000);
        message.setVisibility(View.VISIBLE);
        Log.i(TAG, "paused by remote");
        wmUpdate();
    }

    private void resumeVideo() {
        if (tsPaused && eng != null) {
            tsPaused = false;
            eng.play();
            Log.i(TAG, "resumed, behind live " + eng.behindMs() + " ms");
            tsShow(4000);
            return;
        }
        if (!userPaused) return;
        clearPause();
        fails = 0;
        gaveUp = false;
        Log.i(TAG, "resumed by remote");
        updateBanner();
        play(cur());
    }

    private void clearPause() {
        if (!userPaused) return;
        userPaused = false;
        message.setBackgroundColor(0);
        message.setVisibility(View.GONE);
    }

    private void commit(String id, String name, List<Channel> l, int pos) {
        Channel chosen = l.get(pos);
        boolean same = playing && cur().url.equals(chosen.url);
        zapSection = id;
        zapName = name;
        if (!id.equals("search")) store.setSection(id); // a result list can't be rebuilt after a restart
        list = l;
        index = pos;
        previewCh = null;
        clearPause();
        fails = 0;
        gaveUp = false;
        Log.i(TAG, "commit " + id + " #" + (pos + 1) + " " + chosen.name + " (" + l.size() + " in section)");
        updateBanner();
        if (same) {
            prefs.edit().putString("last", chosen.url).apply();
            store.pushRecent(chosen.url);
        } else {
            gen++;
            h.removeCallbacks(skipR);
            h.removeCallbacks(altR);
            h.removeCallbacks(watchdog);
            h.removeCallbacks(reconnectR);
            reconnecting = false;
            setState("Loading…");
            h.removeCallbacks(startR);
            h.post(startR);
        }
        showBanner();
    }

    private void closeGuide(boolean restore) {
        if (restore && previewCh != null) {
            Log.i(TAG, "restore -> " + list.get(index).name);
            previewCh = null;
            h.removeCallbacks(startR);
            gen++;
            play(list.get(index));
        }
        previewCh = null;
        if (list == null) return;
        wmUpdate();
        showCue();
        if (eng != null && eng.smart()) tsUpdate();
    }

    private void toggleFav() {
        if (idx == null) return;
        if (undoFav()) return;                         // within 6 s of a change the same key undoes it, whichever row is selected
        if (guide.isOpen() && guide.onLongOk()) return; // long OK on a country/category row = hide it
        toggleFavOf(guide.isOpen() ? guide.selected() : cur());
    }

    private static final int UNDO_MS = 6000;
    private String undoUrl, undoName;
    private boolean undoAdded;
    private long undoAt;

    private static String shortName(String n) { return n.length() > 24 ? n.substring(0, 23) + "…" : n; }

    /** T33: the pill after an add / remove says "hold OK to undo"; the same key again within 6 s puts the list back (a second stray press can never remove a second favourite). */
    private boolean undoFav() {
        if (undoUrl == null || SystemClock.elapsedRealtime() - undoAt > UNDO_MS) { undoUrl = null; return false; }
        boolean now = store.toggleFav(undoUrl, undoName);
        Log.i(TAG, "favourite undo " + (now ? "re-added " : "removed ") + undoName);
        showToast(undoAdded ? "Undone: " + shortName(undoName) + " is not a favourite" : "Undone: " + shortName(undoName) + " is a favourite again", 2500);
        undoUrl = null;
        if (guide.isOpen()) guide.onFavChanged();
        return true;
    }

    private void toggleFavOf(Channel c) {
        if (idx == null || c == null) return;
        boolean now = store.toggleFav(c.url, c.name);
        Log.i(TAG, "favourite " + (now ? "added " : "removed ") + c.name);
        undoUrl = c.url; undoName = c.name; undoAdded = now; undoAt = SystemClock.elapsedRealtime();
        showToast((now ? "★  Added " : "Removed ") + shortName(c.name) + "  ·  hold OK to undo", UNDO_MS);
        if (guide.isOpen()) guide.onFavChanged();
    }

    // ---- UI helpers ----

    private void updateBanner() {
        Channel c = list.get(index);
        bNum.setText(String.valueOf(index + 1));
        bName.setText(c.name);
        bSec.setText(zapName);
        StringBuilder s = new StringBuilder();
        if (!c.quality.isEmpty()) s.append(c.quality);
        String f = Ui.flag(c.country);
        if (!f.isEmpty()) s.append(s.length() > 0 ? "   " : "").append(f).append(' ').append(Ui.country(c.country));
        bInfo.setText(s);
        LogoLoader.get(this).load(c.logo, c.name, bLogo);
        refreshNow();
    }

    /** Now/next for the tuned channel: banner, watermark title. Re-runs on the minute and at programme changes (UI thread). */
    private void refreshNow() {
        h.removeCallbacks(nowR);
        if (list == null || list.isEmpty()) return;
        Channel c = list.get(index);
        long t = System.currentTimeMillis();
        CloudData.Slot n = CloudData.now(c.id, t), x = CloudData.next(c.id, t);
        setNowTitle(n == null ? null : n.title);
        bNow.setVisibility(n == null ? View.GONE : View.VISIBLE);
        bNowBar.setVisibility(n == null ? View.GONE : View.VISIBLE);
        bNext.setVisibility(x == null ? View.GONE : View.VISIBLE);
        if (n != null) { bNow.setText("Now: " + n.title + "  ·  " + CloudData.hm(n.start) + "–" + CloudData.hm(n.stop)); bNowBar.set(n.progress(t)); }
        if (x != null) bNext.setText("Next: " + x.title + "  " + CloudData.hm(x.start));
        if (wm.getVisibility() == View.VISIBLE) wmTitle.setText(nowTitle != null ? nowTitle : c.name);
        long d = 60000 - t % 60000 + 200;
        if (n != null) d = Math.min(d, Math.max(1000, n.stop * 1000 - t + 500));
        h.postDelayed(nowR, d);
    }

    private final Runnable nowR = new Runnable() { @Override public void run() { refreshNow(); } };

    private void setState(String s) {
        bState.setText(s);
        bState.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        if (s.isEmpty()) stopBar(); else if (banner.getVisibility() == View.VISIBLE) startBar();
    }

    private void startBar() {
        bBar.setVisibility(View.VISIBLE);
        if (barAnim != null && barAnim.isRunning()) return;
        final int w = Math.max(banner.getWidth(), Ui.u(900)), bw = Ui.u(220);
        barAnim = ValueAnimator.ofFloat(-bw, w);
        barAnim.setDuration(1100);
        barAnim.setRepeatCount(ValueAnimator.INFINITE);
        barAnim.setInterpolator(Ui.DEC);
        barAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { bBar.setTranslationX((Float) a.getAnimatedValue()); }
        });
        barAnim.start();
    }

    private void stopBar() {
        if (barAnim != null) barAnim.cancel();
        bBar.setVisibility(View.INVISIBLE);
    }

    private void showBanner() {
        if (list == null || guide.isOpen()) return;
        h.removeCallbacks(hideBanner);
        banner.animate().cancel();
        bClock.setText(clock.format(new Date()));
        bSpeed.setText(speedText());
        if (banner.getVisibility() != View.VISIBLE || banner.getAlpha() < 1f) {
            if (!Ui.anim()) { banner.setAlpha(1f); banner.setTranslationY(0); banner.setVisibility(View.VISIBLE); }
            else {
                if (banner.getVisibility() != View.VISIBLE) { banner.setAlpha(0f); banner.setTranslationY(Ui.u(28)); }
                banner.setVisibility(View.VISIBLE);
                banner.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(Ui.DEC).start();
            }
        }
        if (bState.getVisibility() == View.VISIBLE) startBar();
        if (playing) h.postDelayed(hideBanner, 3500); // while loading, stay visible
        wmUpdate();
    }

    // ---- corner watermark ----

    /** Programme title for the watermark; null = show the channel name. */
    public void setNowTitle(String t) { nowTitle = t; }

    /** Shows / hides the watermark for the current state; hidden whenever the banner, guide or a chooser is up. */
    private void wmUpdate() {
        String pos = store.wmPos();
        boolean on = !pos.equals("off") && playing && previewCh == null && list != null && !guide.isOpen()
                && banner.getVisibility() != View.VISIBLE && modal.getVisibility() != View.VISIBLE
                && numTv.getVisibility() != View.VISIBLE && tsBox.getVisibility() != View.VISIBLE; // corners are never shared (UX-SPEC 4)
        if (!on) {
            h.removeCallbacks(wmHideR);
            wm.animate().cancel();
            wm.setVisibility(View.GONE);
            return;
        }
        boolean always = store.wmMode().equals("always");
        if (wm.getVisibility() == View.VISIBLE || (!always && !wmArm)) return;
        wmArm = false;
        Channel c = list.get(index);
        CloudData.Slot ns = CloudData.now(c.id, System.currentTimeMillis());
        setNowTitle(ns == null ? null : ns.title);
        LogoLoader.get(this).load(c.logo, c.name, wmLogo);
        wmTitle.setText(nowTitle != null ? nowTitle : c.name);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wm.getLayoutParams();
        lp.gravity = (pos.charAt(0) == 't' ? Gravity.TOP : Gravity.BOTTOM) | (pos.charAt(1) == 'l' ? Gravity.START : Gravity.END);
        boolean bottom = pos.charAt(0) == 'b';
        lp.setMargins(Ui.marginX(), Ui.marginY() + Ui.u(8), Ui.marginX(), Ui.marginY() + Ui.u(8) + (bottom ? Ui.u(96) : 0)); // bottom corners sit above the subtitle lines
        wm.setLayoutParams(lp);
        wm.animate().cancel();
        wm.setVisibility(View.VISIBLE);
        if (Ui.anim()) { wm.setAlpha(0f); wm.animate().alpha(0.6f).setDuration(200).start(); } else wm.setAlpha(0.6f);
        h.removeCallbacks(wmHideR);
        if (!always) h.postDelayed(wmHideR, 8000);
    }

    // ---- channel volume (INFO banner), audio drops ----

    private void showVol() {
        if (playCh == null) return;
        bVol.setText("Channel volume  ◀ " + Vol.bar(store.volume(playCh.url)) + " ▶");
        bVol.setVisibility(View.VISIBLE);
    }

    private boolean volKey(int dir) {
        if (playCh == null || eng == null || banner.getVisibility() != View.VISIBLE) { infoUp = false; return false; }
        float v = Vol.step(store.volume(playCh.url), dir);
        store.setVolume(playCh.url, v);
        eng.setVolume(v);
        showVol();
        h.removeCallbacks(hideBanner);
        h.postDelayed(hideBanner, 6000);
        return true;
    }

    /** More than 3 underruns in 30 s: first time step the video down quietly (auto quality); if it persists, say so. */
    private void audioDrop() {
        if (!playing || previewCh != null || !aGuard.hit(SystemClock.elapsedRealtime())) return;
        audioTrips++;
        boolean lowered = audioTrips == 1 && "auto".equals(quality) && eng != null && eng.lowerQuality();
        Log.i(NET, "audio drops: trip " + audioTrips + (lowered ? ", video stepped down" : ""));
        if (audioTrips >= 2) showToast("Audio unstable on this channel", 4000);
    }

    // ---- subtitles + options ----

    private void clearSubs() {
        h.removeCallbacks(rescanR);
        subs = new ArrayList<>();
        curCue = "";
        trCue = "";
        trOn = false;
        subSel = -1;
        subRestored = false;
        audios = new ArrayList<>();
        audioRestored = false;
        shownAny = false;
        subTv.setVisibility(View.GONE);
        bCC.setVisibility(View.GONE);
        guide.setCC(false);
    }

    /** Lists the stream's subtitle tracks; shows the CC badge; re-applies the choice remembered for this channel. */
    private void scanTracks() {
        Engine e = eng;
        if (e == null || !playing) return;
        subs = e.subtitleTracks();
        StringBuilder log = new StringBuilder();
        for (Engine.Track x : subs) log.append(' ').append(x.mime).append('/').append(x.lang);
        if (!subs.isEmpty() || !h.hasCallbacks(rescanR)) Log.i(TAG, "subtitle tracks:" + (subs.isEmpty() ? " none" : log));
        audios = e.audioTracks();
        if (audios.size() > 1 && !audioRestored && previewCh == null && playCh != null) {
            audioRestored = true;
            String key = store.audioChoice(playCh.url);
            for (int i = 0; i < audios.size() && !key.isEmpty(); i++) if (audioKey(i).equals(key)) { if (!audios.get(i).sel) e.selectAudio(audios.get(i).id); break; }
        }
        boolean any = !subs.isEmpty();
        boolean real = shownAny; // an undeclared CC track (exposed on every HLS stream) only counts once a caption has shown
        for (Engine.Track x : subs) if (!x.mime.contains("cea-") || !x.lang.isEmpty() && !x.lang.equals("und")) real = true;
        bCC.setVisibility(real ? View.VISIBLE : View.GONE);
        guide.setCC(real);
        if (any && !subRestored && previewCh == null && playCh != null) {
            subRestored = true;
            String key = store.subChoice(playCh.url);
            if (dbgSub >= 0 && dbgSub < subs.size()) selectSub(dbgSub, false);
            else if (!key.isEmpty()) { for (int i = 0; i < subs.size(); i++) if (subKey(i).equals(key)) { selectSub(i, false); break; } }
            else if (store.subsDefault()) selectSub(SubPref.pick(subs), false);
        }
    }

    private String subKey(int i) {
        String l = subs.get(i).lang;
        return l.isEmpty() || l.equals("und") ? "#" + i : l;
    }

    private String subName(int i) { return trackName(subs.get(i), i); }

    /** Chooser label: English tracks as they are; others say what will happen to them. */
    private String subLabel(int i) {
        Engine.Track x = subs.get(i);
        String n = subName(i);
        if (!store.subTranslate() || SubPref.isEnglish(x.lang)) return n;
        String l = SubPref.norm(x.lang);
        if (l.isEmpty()) return n + " → English if needed";
        return (n.endsWith(" (CC)") ? n.substring(0, n.length() - 5) : n) + " → English (translated)" + (n.endsWith(" (CC)") ? " (CC)" : "");
    }

    private String audioKey(int i) {
        String l = audios.get(i).lang;
        return l.isEmpty() || l.equals("und") ? "#" + i : l;
    }

    private String trackName(Engine.Track x, int i) {
        String n = "";
        if (!x.lang.isEmpty() && !x.lang.equals("und")) {
            Locale lo = new Locale(x.lang);
            n = lo.getDisplayLanguage(Locale.ENGLISH);
            if (n.equals(x.lang) && x.lang.length() == 3) // ISO 639-2 ("eng")
                for (String c : Locale.getISOLanguages()) { Locale t = new Locale(c); try { if (t.getISO3Language().equals(x.lang)) { n = t.getDisplayLanguage(Locale.ENGLISH); break; } } catch (Exception ignored) { } }
        }
        boolean cea = x.mime.contains("cea-");
        if (n.isEmpty()) n = x.label.isEmpty() ? (cea ? "Captions" : "Track " + (i + 1)) : x.label;
        return cea ? n + " (CC)" : n;
    }

    /** si = -1 turns subtitles off. persist = remember the choice for this channel. */
    private void selectSub(int si, boolean persist) {
        Engine e = eng;
        if (e != null && !e.selectSubtitle(si < 0 ? -1 : subs.get(si).id)) si = -1;
        subSel = si;
        curCue = "";
        trCue = "";
        shownAny = false;
        startTranslation(si);
        subTv.setVisibility(View.GONE);
        Log.i(TAG, "subtitles " + (si < 0 ? "off" : "on " + subs.get(si).mime + "/" + subs.get(si).lang));
        if (persist && playCh != null) store.setSubChoice(playCh.url, si < 0 ? "-" : subKey(si)); // "-" = the user chose Off here
    }

    /** T40: decides whether track si needs translating and, if so, gets the model going. Never runs with subtitles Off or in the mini preview. */
    private void startTranslation(int si) {
        trOn = false;
        trSrc = "";
        detCand = "";
        detAgree = 0;
        if (si < 0 && tr != null) tr.close(); // Off: free the models at once
        if (si < 0 || previewCh != null || !store.subTranslate()) return;
        String l = SubPref.norm(subs.get(si).lang);
        if (l.equals("en")) return;
        if (!translator().ok()) { warnOnce("Translation not available on this TV"); return; }
        trOn = true;
        Log.i(TAG, "subtitle translation on, source " + (l.isEmpty() ? "unknown (detecting)" : l));
        if (!l.isEmpty()) useLang(l);
    }

    private lk.manus.iptv.subs.SubTranslator translator() {
        if (tr == null) tr = new lk.manus.iptv.subs.SubTranslator(this, new lk.manus.iptv.subs.SubTranslator.Listener() {
            @Override public void downloading(String l) { showToast("Downloading " + lk.manus.iptv.subs.SubTranslator.langName(l) + "→English subtitles (once, ~30 MB)…", 0); }
            @Override public void ready(String l) {
                if (!l.equals(trSrc)) return;
                showToast(lk.manus.iptv.subs.SubTranslator.langName(l) + "→English subtitles ready", 2500);
                if (trOn && !curCue.isEmpty()) translateCue(curCue);
            }
            @Override public void failed(String l) { if (l.equals(trSrc)) { trOn = false; showToast("Couldn't get " + lk.manus.iptv.subs.SubTranslator.langName(l) + "→English subtitles (needs internet once)", 5000); showCue(); } }
        });
        return tr;
    }

    private void warnOnce(String msg) { if (!trWarned) { trWarned = true; Log.w(TAG, msg); } showToast(msg, 4000); }

    /** The track's language is known (tag or detected): English needs nothing, a language ML Kit lacks stays original, the rest gets its model. */
    private void useLang(String l) {
        trSrc = l;
        if (l.equals("en")) { trOn = false; return; }
        if (!lk.manus.iptv.subs.SubTranslator.supported(l)) { trOn = false; showToast("No offline translation for " + lk.manus.iptv.subs.SubTranslator.langName(l), 4000); return; }
        translator().ensure(l);
    }

    private void translateCue(final String orig) {
        Log.i(TAG, "cue to translate: src=" + trSrc + " len=" + orig.length());
        if (trSrc.isEmpty()) { // no language tag: two matching detections lock the language for this track
            if (orig.length() < 8) return;
            translator().detect(orig, new lk.manus.iptv.subs.SubTranslator.Cb() {
                @Override public void done(String d) {
                    if (d == null || !trOn || !trSrc.isEmpty()) return;
                    if (d.equals(detCand)) detAgree++; else { detCand = d; detAgree = 1; }
                    if (detAgree >= 2) { useLang(d); if (trOn) translateCue(curCue); showCue(); }
                }
            });
            return;
        }
        final lk.manus.iptv.subs.SubTranslator t = translator();
        if (!t.isReady(trSrc)) { t.ensure(trSrc); return; } // after an idle close the model reloads in a moment
        t.translate(trSrc, orig, new lk.manus.iptv.subs.SubTranslator.Cb() {
            @Override public void done(String out) { if (out != null && orig.equals(curCue)) { trCue = out; showCue(); } }
        });
    }

    /** The engine's current cue, shown bottom-centre unless something else owns the screen. */
    private void showCue() {
        if (subSel < 0 || curCue.isEmpty() || !playing || guide.isOpen() || previewCh != null) { subTv.setVisibility(View.GONE); return; }
        String shown = trCue.isEmpty() ? curCue : trCue;
        if (!shown.contentEquals(subTv.getText())) subTv.setText(shown);
        subTv.setAlpha(trOn && trCue.isEmpty() && !trSrc.equals("en") ? 0.55f : 1f); // the original shows faintly until its English is ready
        subTv.setVisibility(View.VISIBLE);
        if (!shownAny) { shownAny = true; bCC.setVisibility(View.VISIBLE); guide.setCC(true); }
    }

    private void openMenu(String title, List<String> labels, int sel, MenuPick pick) {
        menuBox.removeViews(1, menuBox.getChildCount() - 1);
        menuRows.clear();
        menuTitle.setText(title);
        for (String l : labels) {
            TextView t = Ui.tv(this, Ui.BODY, Ui.TXT, true);
            t.setText(l);
            t.setPadding(Ui.u(14), Ui.u(10), Ui.u(14), Ui.u(10));
            menuBox.addView(t);
            menuRows.add(t);
        }
        menuPick = pick;
        menuSel = Math.max(0, Math.min(sel, labels.size() - 1));
        paintMenu();
        modal.show(menuBox);
        wmUpdate();
        h.removeCallbacks(menuHideR);
        h.postDelayed(menuHideR, 20000);
    }

    private void paintMenu() {
        for (int i = 0; i < menuRows.size(); i++) menuRows.get(i).setBackground(i == menuSel ? Ui.rect(0x667C5CFF, 12) : null);
    }

    private void closeMenu() {
        h.removeCallbacks(menuHideR);
        modal.hide(menuBox);
        menuPick = null;
        wmUpdate();
        showCue();
    }

    private void menuKey(int k) {
        h.removeCallbacks(menuHideR);
        h.postDelayed(menuHideR, 20000);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: menuSel = (menuSel + menuRows.size() - 1) % menuRows.size(); paintMenu(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: menuSel = (menuSel + 1) % menuRows.size(); paintMenu(); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: {
                MenuPick mpk = menuPick;
                int i = menuSel;
                closeMenu();
                if (mpk != null) mpk.pick(i);
                break;
            }
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_CAPTIONS: closeMenu(); break;
            default: break;
        }
    }

    /** Right / Menu in full-screen. */
    private void openOptions() {
        if (list == null || idx == null) return;
        openOptionsFor(list.get(index), true);
    }

    /** The options pill: version choice (when the channel has several), and in full-screen subtitles + previous channel; always favourite. */
    private void openOptionsFor(final Channel c, boolean full) {
        if (list == null || idx == null) return;
        h.removeCallbacks(hideBanner);
        final List<Channel> vs = Dedup.versions(c);
        final List<Runnable> acts = new ArrayList<>();
        List<String> l = new ArrayList<>();
        if (vs.size() > 1) { l.add("Choose version / region  ›  " + vs.size()); acts.add(new Runnable() { @Override public void run() { openVersionMenu(c, vs); } }); }
        if (full) {
            l.add("Subtitles  ›"); acts.add(new Runnable() { @Override public void run() { openSubMenu(); } });
            if (audios.size() > 1) {
                String cur = "";
                for (int i = 0; i < audios.size(); i++) if (audios.get(i).sel) cur = trackName(audios.get(i), i);
                l.add("Audio: " + (cur.isEmpty() ? "default" : cur) + "  ›"); acts.add(new Runnable() { @Override public void run() { openAudioMenu(); } });
            }
            l.add("Previous channel"); acts.add(new Runnable() { @Override public void run() { prevChannel(); } });
        } else if (vs.size() <= 1) { l.add("Close"); acts.add(new Runnable() { @Override public void run() { } }); } // the first item is always harmless: Favourite is never the default focus
        l.add(store.isFav(c.url) ? "Remove from favourites" : "Add to favourites");
        acts.add(new Runnable() { @Override public void run() { toggleFavOf(c); } });
        openMenu("Options", l, 0, new MenuPick() { @Override public void pick(int i) { acts.get(i).run(); } });
    }

    private static String feedOf(Channel c) {
        int at = c.id.indexOf('@');
        return at >= 0 && at < c.id.length() - 1 ? c.id.substring(at + 1) : Redact.host(c.url);
    }

    private void openVersionMenu(final Channel rep, final List<Channel> vs) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < vs.size(); i++) {
            Channel v = vs.get(i);
            int st = health.status(v.url);
            String f = Ui.flag(v.country);
            l.add((i == 0 ? "✓ " : "") + (f.isEmpty() ? "" : f + " ") + feedOf(v) + "  ·  " + (v.quality.isEmpty() ? "—" : v.quality)
                    + "  ·  " + (st == Health.ALIVE ? "alive" : st == Health.LIKELY ? "likely up" : st == Health.DEAD ? "down" : "untested"));
        }
        openMenu("Choose version / region", l, 0, new MenuPick() { @Override public void pick(int i) { chooseVersion(rep, vs.get(i)); } });
    }

    /** Remembers the choice for the whole group, re-ranks, and re-tunes if this is the channel being watched. */
    private void chooseVersion(Channel rep, Channel pick) {
        if (idx == null) return;
        store.chooseVersion(Dedup.choiceKeys(pick), pick.url); // also when it is already the row: pins it
        if (pick == rep) { showToast("Version saved", 1500); return; }
        Dedup.promote(rep, pick);
        idx.rebuildNow();
        boolean cur = list.get(index) == rep && previewCh == null;
        int at = list.indexOf(rep);
        if (at >= 0) { list = new ArrayList<>(list); list.set(at, pick); }
        Log.i(TAG, "version chosen for " + pick.name + ": " + feedOf(pick) + " " + Redact.host(pick.url));
        guide.onVersionsChanged();
        if (!cur) { showToast("Version saved", 1500); return; }
        altFor = null; fails = 0; gaveUp = false;
        updateBanner();
        gen++;
        h.removeCallbacks(skipR); h.removeCallbacks(altR); h.removeCallbacks(watchdog); h.removeCallbacks(reconnectR);
        reconnecting = false;
        setState("Loading…");
        h.removeCallbacks(startR);
        h.post(startR);
        showBanner();
    }

    // ---- T27 failure card ----

    private void showFail(final Channel rep, List<Channel> alts) {
        h.removeCallbacks(hideBanner); // one thing at a time: no "Loading…" banner or pill under the card
        banner.animate().cancel();
        banner.setVisibility(View.GONE);
        setState("");
        clearToast();
        hidePill();
        failActs.clear();
        failActs.add("Retry");
        if (altI < alts.size()) failActs.add("Try another version");
        failActs.add("Next channel");
        failActs.add("Back to guide");
        failRow.removeAllViews();
        failBtns.clear();
        for (String a : failActs) {
            TextView b = Ui.tv(this, Ui.BODY, Ui.TXT, true);
            b.setText(a);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.u(18), Ui.u(12), Ui.u(18), Ui.u(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = failBtns.isEmpty() ? 0 : Ui.u(8);
            failRow.addView(b, lp);
            failBtns.add(b);
        }
        failSub.setText(rep.name);
        failSel = 0;
        paintFail();
        failUp = true;
        gaveUp = true; // nothing restarts by itself while the card is up
        modal.show(failBox);
        Log.i(TAG, "failure card: " + failActs);
    }

    private void paintFail() {
        for (int i = 0; i < failBtns.size(); i++) failBtns.get(i).setBackground(i == failSel ? Ui.ring(Ui.SURF) : Ui.rect(0x66141A2A, 14));
    }

    private void hideFail() {
        failUp = false;
        modal.hide(failBox);
    }

    private void failKey(int k, int repeat) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_LEFT: failSel = Math.max(0, failSel - 1); paintFail(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_RIGHT: failSel = Math.min(failBtns.size() - 1, failSel + 1); paintFail(); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: if (repeat == 0) failDo(failActs.get(failSel)); break;
            case KeyEvent.KEYCODE_BACK: if (repeat == 0) failDo("Back to guide"); break;
            default: break;
        }
    }

    private void failDo(String act) {
        final Channel rep = list.get(index);
        hideFail();
        gaveUp = false;
        if (act.equals("Retry")) {
            Log.i(TAG, "failure card: retry");
            fails = 0;
            failover = true; // keep the version cursor
            play(playCh != null ? playCh : rep);
            failover = false;
        } else if (act.equals("Try another version")) {
            List<Channel> alts = Dedup.alternates(rep);
            if (altFor != rep) { altFor = rep; altI = 0; }
            if (altI >= alts.size()) { zap(1); return; }
            Channel a = alts.get(altI++);
            Log.i(TAG, "failure card: version " + altI + "/" + alts.size() + " " + feedOf(a) + " " + Redact.host(a.url));
            failover = true;
            play(a);
            failover = false;
        } else if (act.equals("Next channel")) {
            zap(1);
        } else {
            openGuide();
        }
    }

    private void openSubMenu() {
        if (list == null || !playing) return;
        if (subs.isEmpty()) { showToast("No subtitles on this channel", 2000); return; }
        List<String> l = new ArrayList<>();
        l.add("Off");
        for (int i = 0; i < subs.size(); i++) l.add(subLabel(i));
        openMenu("Subtitles", l, (subSel < 0 ? SubPref.pick(subs) : subSel) + 1, new MenuPick() { @Override public void pick(int i) { selectSub(i - 1, true); } });
    }

    private void openAudioMenu() {
        List<String> l = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < audios.size(); i++) { l.add(trackName(audios.get(i), i)); if (audios.get(i).sel) sel = i; }
        openMenu("Audio", l, sel, new MenuPick() { @Override public void pick(int i) {
            Engine e = eng;
            if (e == null || i >= audios.size() || !e.selectAudio(audios.get(i).id)) return;
            if (playCh != null) store.setAudioChoice(playCh.url, audioKey(i));
            Log.i(TAG, "audio " + audios.get(i).lang);
            h.postDelayed(new Runnable() { @Override public void run() { scanTracks(); } }, 500); // refresh the selected flags
        } });
    }

    /** Message pill (top-centre, one line): replaces any pill on screen; ms = 0 stays until the next zap. */
    private void showToast(String s, int ms) {
        h.removeCallbacks(hideToast);
        toast.animate().cancel();
        toast.setText(s);
        pillKind = 2;
        toast.setVisibility(View.VISIBLE);
        if (Ui.anim()) { toast.setAlpha(0f); toast.animate().alpha(1f).setDuration(160).start(); } else toast.setAlpha(1f);
        if (ms > 0) h.postDelayed(hideToast, ms);
    }

    // ---- lifecycle ----

    @Override protected void onResume() {
        super.onResume();
        if (idx != null) lk.manus.iptv.ui.wizard.Wizard.sync(store, idx);
        if (sources != null && list == null && idx != null && idx.all.isEmpty()) { sources.reread(); load(); } // the wizard may have added a playlist while we waited
        paused = false;
        sessionT0 = lastSummary = SystemClock.elapsedRealtime();
        stallsTotal = 0; reconnectsTotal = 0; statSamples = 0; mbpsSum = 0; lastRxT = 0;
        h.removeCallbacks(tickR);
        h.postDelayed(tickR, 1000);
        ensurePlaying();
    }

    @Override protected void onPause() {
        paused = true;
        h.removeCallbacks(tickR);
        guide.hideNow();
        previewCh = null;
        clearPause();
        hideFail();
        h.removeCallbacks(connR);
        h.removeCallbacks(bgSkipR);
        connBox.setVisibility(View.GONE);
        prober.setPaused(true);
        closeMenu();
        stopAll();
        super.onPause();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (tr != null) tr.close();
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder sh) {
        holder = sh;
        surfaceReady = true;
        tryPending();
        ensurePlaying();
    }
    @Override public void surfaceChanged(SurfaceHolder sh, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder sh) {
        surfaceReady = false;
        if (!useTex) stopAll(); // in mirror mode the hidden SurfaceView going away is expected
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---- spike (hidden test mode: --ei spike N) ----

    private void startSpike() {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) idx.add(i);
        Collections.shuffle(idx, new Random(42));
        spikeIdx = idx.subList(0, Math.min(spike, idx.size()));
        spikeFile = new File(getExternalFilesDir(null), "spike.csv");
        spikeFile.delete();
        list = all;
        message.setVisibility(View.VISIBLE);
        ensurePlaying();
    }

    private void spikeNext() {
        if (spikeI >= spikeIdx.size()) { finish(); return; }
        index = spikeIdx.get(spikeI);
        message.setText("Spike " + (spikeI + 1) + "/" + spikeIdx.size() + "\n" + all.get(index).name);
        play(all.get(index));
    }

    private static String q(String s) { return '"' + s.replace("\"", "\"\"") + '"'; }

    private void spikeRecord(String result, String detail) {
        if (curDone) return;
        curDone = true;
        Channel c = all.get(index);
        long ms = SystemClock.elapsedRealtime() - t0;
        try (FileWriter w = new FileWriter(spikeFile, true)) {
            w.write(index + "," + q(c.name) + "," + q(c.url) + "," + result + "," + ms + "," + q(detail)
                    + "," + q(c.userAgent) + "," + q(c.referrer) + "\n");
        } catch (Exception e) {
            Log.w(TAG, "spike write failed: " + e);
        }
        Log.i(TAG, "spike " + (spikeI + 1) + " " + result + " " + detail);
        spikeI++;
        h.removeCallbacks(watchdog);
        gen++;
        releasePlayer();
        h.postDelayed(spikeNextR, 500);
    }
}
