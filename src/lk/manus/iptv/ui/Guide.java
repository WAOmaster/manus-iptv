package lk.manus.iptv.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.net.Uri;
import android.widget.EditText;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Curated;
import lk.manus.iptv.data.Dedup;
import lk.manus.iptv.data.CloudData;
import lk.manus.iptv.data.Health;
import lk.manus.iptv.data.Prober;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Sources;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.data.YouTubeLive;
import lk.manus.iptv.player.MiniPreview;

/** Immersive overlay guide: rail | channel list | preview card, over the playing video. */
public final class Guide extends FrameLayout {
    public interface Host {
        Index index();
        Store store();
        Sources sources();
        void sourcesChanged();
        String playingUrl();
        String activeSection();
        int mainDropped();       // video frames the full-screen player has dropped so far (the mini preview must not hurt it)
        void onCommit(String sectionId, String name, List<Channel> list, int pos);
        void onClose(boolean restore);
        String netSpeed();       // e.g. "4.2 Mbps ●●●", "" when unknown
        void exitApp();
        void onOptions(Channel c);   // Right / Menu on a channel row: the options pill (versions, favourite)
        void edgesChanged();         // Settings > Screen edges moved: re-apply the safe area
        void pill(String text);      // the one pill (top-centre), owned by the activity
        void restoreFavourites(List<String> urls); // Settings > Restore favourites
    }

    private static final int CARD_MS = 700, LIVE_MS = 1200, RAIL_MS = 250, IDLE_MS = 45000;
    private static final String TAG = "IPTV";

    private static final class Rail {
        final String id, label; int count; boolean nav, divider; // nav = opens a level-2 list
        Rail(String i, String l, int c) { id = i; label = l; count = c; }
        static Rail nav(String i, String l, int c) { Rail r = new Rail(i, l, c); r.nav = true; return r; }
        static Rail divider() { Rail r = new Rail("", "", -1); r.divider = true; return r; }
    }
    /** A folder-like row: a country or one of a source's folders. id = the list id it opens ("cc:lk", "sf:id TAB folder"). */
    private static final class CC {
        final String id, name, flag, countText;
        CC(String i, String n, String f, String c) { id = i; name = n; flag = f; countText = c; }
    }

    private final Host host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
    private final View scrim;
    private final FrameLayout col2;
    private final LinearLayout content;
    private final ListView rail, list;
    private final LinearLayout railCol;
    private final TextView railHead;
    private final Modal dlg;
    private final KeyModal kmodal;
    private final TextView dlgTitle, dlgSub, dlgYes, dlgNo;
    private final SafeFrame frame;
    private Runnable dlgAct;
    private View dlgPrev;
    private final TextView title, sub, empty;
    private boolean cardOk = true;   // the preview card needs >= 260 scaled dp
    private final EditText searchEt;
    private final SettingsView settings;
    private final BaseAdapter railAd, listAd;
    private final LinearLayout card, chips;
    private final ImageView cLogo;
    private final FrameLayout cMini;      // 16:9 window: logo underneath, the muted preview video fades in on top
    private final TextureView cTex;
    private final MiniPreview mini;
    private int miniDrop0;
    private int avc = -1;                  // H.264 hardware decoder instances (checked once)
    private final TextView cName, cInfo, cSpeed, cStatus, cNow, cTimes, cNext;
    private final NowBar cBar;

    private final List<Rail> l1 = new ArrayList<>(), l2 = new ArrayList<>();
    private List<Rail> rails = l1;            // the level on screen
    private int level = 1, sel1;              // level 2 shows a country / category / source list in the rail column
    private String l2Kind = "";
    private final List<Object> items = new ArrayList<>();
    private final Map<String, Integer> memo = new HashMap<>();
    private final Set<String> favSet = new HashSet<>();
    private List<Channel> chanList;
    private int railSel, listSel;
    private String shownId, drill, liveUrl = "";
    private boolean open, fast;
    private long lastNav;
    private int searchSeq;
    private String lastQuery = "";
    private static final List<String> NUMS = new ArrayList<>();

    private final Runnable railDwell = new Runnable() { @Override public void run() { if (railSel < rails.size()) showSection(rails.get(railSel)); } };
    private final Runnable speedR = new Runnable() {
        @Override public void run() {
            if (card.getVisibility() != VISIBLE) return;
            cSpeed.setText(host.netSpeed());
            h.postDelayed(this, 1000);
        }
    };
    /** Only the rows on screen get probed, 400 ms after the list settles. */
    private final Runnable probeR = new Runnable() {
        @Override public void run() {
            if (!open) return;
            Prober pr = Prober.get(getContext());
            int b = Math.min(list.getLastVisiblePosition(), items.size() - 1);
            for (int i = b; i >= Math.max(0, list.getFirstVisiblePosition()); i--) if (items.get(i) instanceof Channel) pr.request((Channel) items.get(i));
        }
    };
    private boolean cc; // the playing channel carries subtitles
    private final Runnable cardR = new Runnable() { @Override public void run() { showCard(); } };
    private final Runnable liveR = new Runnable() {
        @Override public void run() {
            Channel c = selected();
            if (c == null || c.url.equals(liveUrl) || !miniOn()) return; // the playing channel is already on screen
            miniDrop0 = host.mainDropped();
            mini.start(c, c.url, cTex);
            h.removeCallbacks(miniWatchR);
            h.postDelayed(miniWatchR, 5000);
        }
    };
    /** While a preview runs: if the full-screen video starts dropping frames, give the mini window up on this TV for good. */
    private final Runnable miniWatchR = new Runnable() {
        @Override public void run() {
            if (!mini.active()) return;
            int d = host.mainDropped() - miniDrop0;
            Log.i(TAG, "mini: main dropped " + d + " frames since the preview started");
            if (d > 60) { stopMini(); blockMini("main video dropped " + d + " frames"); return; }
            miniDrop0 = host.mainDropped();
            h.postDelayed(this, 5000);
        }
    };
    private final Runnable calmR = new Runnable() { @Override public void run() { fast = false;
            View sel = list.getSelectedView();
            if (sel instanceof Row && Ui.anim()) ((Row) sel).animate().scaleX(1.03f).scaleY(1.03f).setDuration(160).setInterpolator(Ui.DEC).start(); // selected row grows once scrolling stops (not on a Low-tier TV)
            LogoLoader.quiet = false;
            if (LogoLoader.deferred) { LogoLoader.deferred = false; listAd.notifyDataSetChanged(); } // load the logos skipped while scrolling
        }
    };
    private final Runnable searchR = new Runnable() { @Override public void run() { runSearch(); } };
    private final Runnable idleR = new Runnable() {
        @Override public void run() {
            // never close under a text box (search / add-source form) or an open dialog
            if (searchEt.hasFocus() || settings.hasTextFocus() || dlg.getVisibility() == VISIBLE) { h.postDelayed(idleR, IDLE_MS); return; }
            Log.i(TAG, "guide idle timeout");
            dismiss(true);
        }
    };

    public Guide(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setVisibility(GONE);
        int sw = Ui.screenW();
        scrim = new View(ctx);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xEB0B0F19, 0xEB0B0F19, 0xB00B0F19, 0x000B0F19});
        scrim.setBackground(g);
        addView(scrim, new LayoutParams(sw * 3 / 4, -1, Gravity.START));

        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(Ui.marginX(), Ui.marginY(), 0, Ui.marginY());
        addView(content, new LayoutParams(-2, -1, Gravity.START));
        frame = new SafeFrame(ctx);

        rail = newList();
        railAd = new BaseAdapter() {
            @Override public int getCount() { return rails.size(); }
            @Override public Object getItem(int i) { return rails.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return rails.get(i).divider ? 1 : 0; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int i) { return !rails.get(i).divider; }
            @Override public View getView(int i, View v, ViewGroup p) {
                if (rails.get(i).divider) {
                    if (v != null) return v;
                    FrameLayout d = new FrameLayout(getContext());
                    d.setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(18)));
                    View line = new View(getContext());
                    line.setBackgroundColor(0x44FFFFFF);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, Ui.u(1), Gravity.CENTER_VERTICAL);
                    lp.setMargins(Ui.u(14), 0, Ui.u(14), 0);
                    d.addView(line, lp);
                    return d;
                }
                RailRow r = v instanceof RailRow ? (RailRow) v : new RailRow(getContext());
                r.bind(rails.get(i), i);
                return r;
            }
        };
        rail.setAdapter(railAd);
        railCol = new LinearLayout(ctx);
        railCol.setOrientation(LinearLayout.VERTICAL);
        railHead = Ui.tv(ctx, Ui.BODY, Ui.CYAN, true);
        railHead.setPadding(Ui.u(26), Ui.u(2), 0, Ui.u(8));
        railHead.setVisibility(GONE);
        railCol.addView(railHead);
        railCol.addView(rail, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(railCol, new LinearLayout.LayoutParams(0, -1));

        col2 = new FrameLayout(ctx);
        LinearLayout c2 = new LinearLayout(ctx);
        c2.setOrientation(LinearLayout.VERTICAL);
        title = Ui.tv(ctx, Ui.TITLE, Ui.TXT, true);
        sub = Ui.tv(ctx, Ui.CAPTION, Ui.SEC, false);
        title.setPadding(Ui.u(18), Ui.u(2), 0, 0);
        sub.setPadding(Ui.u(18), Ui.u(3), 0, Ui.u(6));
        c2.addView(title);
        c2.addView(sub);
        searchEt = new EditText(ctx);
        searchEt.setSingleLine(true);
        Ui.size(searchEt, Ui.BODY);
        searchEt.setTextColor(Ui.TXT);
        searchEt.setHintTextColor(Ui.SEC);
        searchEt.setHint("🔍  Type or speak to search");
        searchEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchEt.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        searchEt.setPadding(Ui.u(18), Ui.u(10), Ui.u(18), Ui.u(10));
        android.graphics.drawable.StateListDrawable sbg = new android.graphics.drawable.StateListDrawable();
        sbg.addState(new int[]{android.R.attr.state_focused}, Ui.ring(0xFF0B0F19));
        sbg.addState(new int[]{}, Ui.rect(0xFF141A2A, 14));
        searchEt.setBackground(sbg);
        searchEt.setVisibility(GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(Ui.u(12), Ui.u(4), Ui.u(12), Ui.u(6));
        c2.addView(searchEt, sp);
        list = newList();
        listAd = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return items.get(i) instanceof CC ? 1 : 0; }
            @Override public View getView(int i, View v, ViewGroup p) {
                Object o = items.get(i);
                if (o instanceof CC) {
                    CountryRow r = v instanceof CountryRow ? (CountryRow) v : new CountryRow(getContext());
                    r.bind((CC) o, i);
                    return r;
                }
                ChanRow r = v instanceof ChanRow ? (ChanRow) v : new ChanRow(getContext());
                r.bind((Channel) o, i);
                return r;
            }
        };
        list.setAdapter(listAd);
        c2.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        col2.addView(c2, new LayoutParams(-1, -1));
        empty = Ui.tv(ctx, Ui.BODY, Ui.SEC, false);
        empty.setSingleLine(false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.u(40), 0, Ui.u(40), 0);
        col2.addView(empty, new LayoutParams(-1, -1));
        content.addView(col2, new LinearLayout.LayoutParams(0, -1));
        settings = new SettingsView(ctx, new SettingsView.Host() {
            @Override public Index index() { return Guide.this.host.index(); }
            @Override public Store store() { return Guide.this.host.store(); }
            @Override public Sources sources() { return Guide.this.host.sources(); }
            @Override public void sourcesChanged() { Guide.this.host.sourcesChanged(); }
            @Override public void forYouChanged() { Guide.this.forYouChanged(); }
            @Override public void countsChanged() { Guide.this.refreshCounts(); }
            @Override public void focusRail() { rail.requestFocus(); }
            @Override public void recheckHidden() {
                confirm("Re-check hidden channels?", "Hidden channels get another chance.", "Re-check", "Cancel", new Runnable() {
                    @Override public void run() {
                        Health.get(getContext()).resetDead();
                        Guide.this.host.index().rebuildNow();
                        refreshCounts();
                        listAd.notifyDataSetChanged();
                        pill("Hidden channels will be re-checked");
                    }
                });
            }
            @Override public void pill(String text) { Guide.this.pill(text); }
            @Override public void confirm(String t, String s, String yes, String no, Runnable act) { Guide.this.confirm(t, s, yes, no, act); }
            @Override public void restoreFavourites(List<String> urls) { Guide.this.host.restoreFavourites(urls); }
            @Override public void frame(boolean on) { if (on) frame.show(); else frame.hideNow(); }
            @Override public void edgesChanged() { applyLayout(); Guide.this.host.edgesChanged(); frame.flash(); }
            @Override public void showRemoteKeys() { Guide.this.kmodal.showHelp(); }
            @Override public void showRemoteTest() { Guide.this.kmodal.showTest(); }
            @Override public void exit() { confirm("Exit Manus IPTV?", "", "Yes", "No", new Runnable() { @Override public void run() { Guide.this.host.exitApp(); } }); }
        });
        content.addView(settings, new LinearLayout.LayoutParams(0, -1));

        // preview card
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rect(0xF0141A2A, 18));
        card.setPadding(Ui.u(20), Ui.u(20), Ui.u(20), Ui.u(18));
        card.setAlpha(0f);
        card.setVisibility(INVISIBLE);
        cLogo = new ImageView(ctx);
        cLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cLogo.setPadding(Ui.u(12), Ui.u(10), Ui.u(12), Ui.u(10));
        cLogo.setBackground(Ui.rect(0xFF0B0F19, 12));
        cMini = new FrameLayout(ctx);
        cMini.setBackground(Ui.rect(0xFF0B0F19, 12));
        cMini.setClipToOutline(true);
        cMini.addView(cLogo, new FrameLayout.LayoutParams(-1, -1));
        cTex = new TextureView(ctx);
        cTex.setAlpha(0f);
        Ui.blurVideo(cTex, 12);
        cMini.addView(cTex, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        card.addView(cMini, new LinearLayout.LayoutParams(-1, Ui.u(110)));
        mini = new MiniPreview(ctx, new MiniPreview.Listener() {
            @Override public void onFirstFrame() { cTex.animate().cancel(); if (Ui.anim()) cTex.animate().alpha(1f).setDuration(220).start(); else cTex.setAlpha(1f); }
            @Override public void onVideoSize(int w, int hh) { fitMini(w, hh); }
            @Override public void onFail(String why, boolean decoderProblem) {
                stopMini();
                if (decoderProblem) blockMini(why);
            }
        });
        cName = Ui.tv(ctx, Ui.TITLE, Ui.TXT, true);
        cName.setPadding(0, Ui.u(14), 0, Ui.u(8));
        card.addView(cName);
        chips = new LinearLayout(ctx);
        card.addView(chips);
        cInfo = Ui.tv(ctx, Ui.CAPTION, Ui.SEC, false);
        cInfo.setPadding(0, Ui.u(10), 0, 0);
        card.addView(cInfo);
        cNow = Ui.tv(ctx, Ui.BODY, Ui.TXT, true);
        cNow.setPadding(0, Ui.u(10), 0, 0);
        cNow.setSingleLine(false);
        cNow.setMaxLines(2);
        cNow.setVisibility(GONE);
        card.addView(cNow);
        cTimes = Ui.tv(ctx, Ui.CAPTION, Ui.SEC, false);
        cTimes.setPadding(0, Ui.u(4), 0, 0);
        cTimes.setVisibility(GONE);
        card.addView(cTimes);
        cBar = new NowBar(ctx);
        cBar.setVisibility(GONE);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-1, Ui.u(3));
        cbp.topMargin = Ui.u(6);
        card.addView(cBar, cbp);
        cNext = Ui.tv(ctx, Ui.CAPTION, Ui.SEC, false);
        cNext.setPadding(0, Ui.u(6), 0, 0);
        cNext.setVisibility(GONE);
        card.addView(cNext);
        cSpeed = Ui.tv(ctx, Ui.CAPTION, Ui.CYAN, true);
        cSpeed.setPadding(0, Ui.u(6), 0, 0);
        card.addView(cSpeed);
        cStatus = Ui.tv(ctx, Ui.CAPTION, Ui.BAD, true);
        cStatus.setText("Not available");
        cStatus.setVisibility(GONE);
        cStatus.setPadding(0, Ui.u(8), 0, 0);
        card.addView(cStatus);
        TextView hint = Ui.tv(ctx, Ui.CAPTION, Ui.SEC, false);
        hint.setText("OK watch  ·  hold OK ★");
        hint.setPadding(0, Ui.u(14), 0, 0);
        card.addView(hint);
        addView(card, new LayoutParams(0, -2, Gravity.END | Gravity.BOTTOM));
        addView(frame, new LayoutParams(-1, -1));

        dlg = new Modal(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackground(Ui.rect(0xFF141A2A, 18));
        box.setPadding(Ui.u(32), Ui.u(26), Ui.u(32), Ui.u(26));
        dlgTitle = Ui.tv(ctx, Ui.TITLE, Ui.TXT, true);
        dlgSub = Ui.tv(ctx, Ui.BODY, Ui.SEC, false);
        dlgSub.setPadding(0, Ui.u(8), 0, 0);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.CENTER);
        dlgYes = dlgButton(); dlgNo = dlgButton();
        btns.addView(dlgYes, new LinearLayout.LayoutParams(0, Ui.u(52), 1));
        LinearLayout.LayoutParams nb = new LinearLayout.LayoutParams(0, Ui.u(52), 1);
        nb.leftMargin = Ui.u(16);
        btns.addView(dlgNo, nb);
        box.addView(dlgTitle);
        box.addView(dlgSub);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.u(22);
        box.addView(btns, bp);
        dlg.add(box, 0);
        addView(dlg, new LayoutParams(-1, -1));
        kmodal = new KeyModal(ctx);
        addView(kmodal, new LayoutParams(-1, -1));
        dlgYes.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { Runnable a = dlgAct; closeDlg(); if (a != null) a.run(); } });
        dlgNo.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { closeDlg(); } });

        applyLayout();
        wire();
    }

    /** The grid of UX-SPEC 4, as fractions of the safe width: rail / level-2 22%, list 42%, card 30%, gutters 3%. Re-run when the screen edges change. */
    public void applyLayout() {
        int sw = Ui.safeW(), g = sw * 3 / 100, rw = sw * 22 / 100, lw = sw * 42 / 100, cw = sw * 30 / 100;
        content.setPadding(Ui.marginX(), Ui.marginY(), 0, Ui.marginY());
        setW(railCol, rw, 0);
        setW(col2, lw, g);
        setW(settings, lw + g + cw, g);
        cardOk = cw >= Ui.u(240); // 30% of the safe width is ~259 dp at baseline: 260 hid the card on the KTC
        LayoutParams cp = (LayoutParams) card.getLayoutParams();
        cp.width = cw;
        cp.setMargins(0, 0, Ui.marginX(), Ui.marginY());
        card.setLayoutParams(cp);
        cMini.getLayoutParams().height = (cw - Ui.u(40)) * 9 / 16; // the logo area is a 16:9 window
    }

    private static void setW(View v, int w, int left) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.width = w;
        lp.leftMargin = left;
        v.setLayoutParams(lp);
    }

    private TextView dlgButton() {
        final TextView b = Ui.tv(getContext(), Ui.BODY, Ui.TXT, true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, Ui.ring(Ui.SURF));
        sl.addState(new int[]{}, Ui.rect(0xFF0B0F19, 14));
        b.setBackground(sl);
        b.setOnKeyListener(new OnKeyListener() { // keep focus on the two buttons
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) (v == dlgYes ? dlgNo : dlgYes).requestFocus();
                    return true;
                }
                return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
            }
        });
        return b;
    }

    /** Modal question; "No" has focus. Back just closes it. */
    void confirm(String title, String sub, String yes, String no, Runnable onYes) {
        dlgPrev = findFocus();
        dlgAct = onYes;
        dlgTitle.setText(title);
        dlgSub.setText(sub);
        dlgSub.setVisibility(sub.isEmpty() ? GONE : VISIBLE);
        dlgYes.setText(yes);
        dlgNo.setText(no);
        dlg.setVisibility(VISIBLE);
        dlgNo.requestFocus();
    }

    private void closeDlg() {
        dlg.setVisibility(GONE);
        dlgAct = null;
        if (dlgPrev != null && dlgPrev.isAttachedToWindow() && dlgPrev.getVisibility() == VISIBLE) dlgPrev.requestFocus(); else rail.requestFocus();
        dlgPrev = null;
    }

    private ListView newList() {
        ListView l = new ListView(getContext());
        l.setSelector(new ColorDrawable(0));
        l.setDivider(null);
        l.setCacheColorHint(0);
        l.setOverScrollMode(OVER_SCROLL_NEVER);
        l.setVerticalScrollBarEnabled(false);
        l.setClipChildren(false);
        l.setPadding(Ui.u(12), Ui.u(4), Ui.u(12), Ui.u(4));
        l.setItemsCanFocus(false);
        l.setScrollingCacheEnabled(false);
        return l;
    }

    private void wire() {
        rail.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == railSel && !rail.hasFocus()) return;
                railSel = pos;
                if (level == 2) memo.put("L2:" + l2Kind, pos);
                refreshRail();
                if (rail.hasFocus()) { h.removeCallbacks(railDwell); h.postDelayed(railDwell, RAIL_MS); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!list.hasFocus()) return;
                listSel = pos;
                memo.put(memoKey(), pos);
                long now = SystemClock.uptimeMillis();
                fast = now - lastNav < 160; // key-repeat scrolling: skip the row scale animation and logo fades
                LogoLoader.quiet = fast;
                lastNav = now;
                h.removeCallbacks(calmR);
                h.postDelayed(calmR, 200);
                dwell();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        OnFocusChangeListener fl = new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                refreshRail();
                if (v == list) { if (f) dwell(); else { h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini(); hideCard(); } }
            }
        };
        searchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { h.removeCallbacks(searchR); h.postDelayed(searchR, 150); }
        });
        searchEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                hideIme();
                h.removeCallbacks(searchR);
                runSearch();
                list.requestFocus();
                return true;
            }
        });
        searchEt.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (e.getAction() == KeyEvent.ACTION_UP) {
                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(v, 0);
                    }
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && searchEt.getSelectionStart() <= 0) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) rail.requestFocus();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN && !items.isEmpty()) { hideIme(); list.requestFocus(); }
                    return true;
                }
                return false;
            }
        });
        rail.setOnFocusChangeListener(fl);
        list.setOnFocusChangeListener(fl);
        rail.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (level == 2) exitLevel2(true); else dismiss(true); }
                    else activate();
                }
                return true;
            }
        });
        list.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    if (k == KeyEvent.KEYCODE_DPAD_LEFT) rail.requestFocus();
                    else { Channel c = selected(); if (c != null) host.onOptions(c); }
                }
                return true;
            }
        });
    }

    // ---- public API ----

    public boolean isOpen() { return open; }

    public void touch() { if (open) { h.removeCallbacks(idleR); h.postDelayed(idleR, IDLE_MS); } }

    public void open() {
        host.index().refresh(host.store());
        refreshFavs();
        liveUrl = host.playingUrl();
        buildRails();
        level = 1;
        rails = l1;
        showLevel(1);
        drill = null;
        String act = host.activeSection(), railId = act, kind = null, selId = null, drillId = null;
        if (act.startsWith("cc:")) { kind = "countries"; selId = act; }
        else if (act.startsWith("cat:")) { kind = "cats"; selId = act; }
        else if (act.startsWith("sf:")) { kind = "srcs"; selId = "src:" + act.substring(3, Math.max(3, act.indexOf('\t'))); drillId = act; }
        if (kind != null) railId = kind;
        railSel = 0;
        for (int i = 0; i < l1.size(); i++) if (l1.get(i).id.equals(railId)) railSel = i;
        railAd.notifyDataSetChanged();
        rail.setSelection(railSel);
        shownId = null;
        if (kind != null) enterLevel2(kind, selId, false);
        else showSection(rails.get(railSel));
        if (drillId != null && drillList(drillId) != null) enterDrill(drillId);
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (o instanceof Channel && ((Channel) o).url.equals(liveUrl)) { selectList(i); break; }
        }
        open = true;
        h.removeCallbacks(nowR);
        h.postDelayed(nowR, 60000 - System.currentTimeMillis() % 60000 + 200);
        setVisibility(VISIBLE);
        content.animate().cancel();
        scrim.animate().cancel();
        if (Ui.anim()) {
            content.setTranslationX(-Ui.u(48));
            content.setAlpha(0f);
            content.animate().translationX(0).alpha(1f).setDuration(200).setInterpolator(Ui.DEC).start();
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(200).start();
        } else { content.setTranslationX(0); content.setAlpha(1f); scrim.setAlpha(1f); }
        focusContent();
        touch();
    }

    /** Closes with the slide-out animation and tells the host (restore = put back the channel that played before). */
    public void dismiss(boolean restore) {
        if (!open) return;
        hide();
        host.onClose(restore);
    }

    public void hide() {
        if (!open) return;
        open = false;
        dlg.setVisibility(GONE);
        kmodal.setVisibility(GONE);
        settings.hide();
        hideIme();
        h.removeCallbacks(searchR);
        h.removeCallbacks(nowR); h.removeCallbacks(speedR); h.removeCallbacks(railDwell); h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini(); h.removeCallbacks(idleR);
        card.setAlpha(0f);
        frame.hideNow();
        if (!Ui.anim()) { content.animate().cancel(); scrim.animate().cancel(); setVisibility(GONE); return; }
        content.animate().translationX(-Ui.u(48)).alpha(0f).setDuration(160).setInterpolator(Ui.DEC).start();
        scrim.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
            @Override public void run() { if (!open) setVisibility(GONE); }
        }).start();
    }

    public void hideNow() {
        open = false;
        dlg.setVisibility(GONE);
        kmodal.setVisibility(GONE);
        settings.hide();
        frame.hideNow();
        hideIme();
        h.removeCallbacks(nowR); h.removeCallbacks(speedR); h.removeCallbacks(railDwell); h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini(); h.removeCallbacks(idleR);
        content.animate().cancel();
        scrim.animate().cancel();
        setVisibility(GONE);
    }

    /** Short OK. */
    public void activate() {
        if (!list.hasFocus()) {
            Rail r = railSel < rails.size() ? rails.get(railSel) : null;
            if (level == 1 && r != null && r.nav) enterLevel2(r.id, null, true); else focusList();
            return;
        }
        if (listSel < 0 || listSel >= items.size()) return;
        Object o = items.get(listSel);
        if (o instanceof CC) {
            String cid = ((CC) o).id;
            if (cid.startsWith("yt:")) openYouTube(cid.substring(3)); else enterDrill(cid);
            return;
        }
        String id = drill != null ? drill : shownId;
        String name = "search".equals(id) ? "Search: " + lastQuery : host.index().title(id);
        host.onCommit(id, name, chanList, listSel);
        hide();
    }

    /** True while a text box or the settings screen has focus: the activity then hands every key to the views. */
    public boolean rawKeys() { return open && (dlg.getVisibility() == VISIBLE || searchEt.hasFocus() || (settings.getVisibility() == VISIBLE && settings.hasFocus())); }

    /** Back: dialog, keyboard, search text, settings page, folder, level 2, then close. Never leaves the app. */
    public void onBack() {
        if (dlg.getVisibility() == VISIBLE) { closeDlg(); return; }
        if (imeVisible()) { hideIme(); return; }
        if (searchEt.hasFocus() && searchEt.getText().length() > 0) { searchEt.setText(""); return; }
        if (settings.getVisibility() == VISIBLE && settings.hasFocus()) { if (!settings.back()) rail.requestFocus(); return; }
        if (drill != null) { drill = null; shownId = null; showSection(rails.get(railSel)); return; }
        if (!rail.hasFocus()) { if (level == 2 || "search".equals(shownId)) rail.requestFocus(); else dismiss(true); return; }
        if (level == 2) { exitLevel2(true); return; }
        if (lk.manus.iptv.Config.PLAY) { // Play TV quality: Back from the top level must eventually reach Home
            confirm("Exit Manus IPTV?", "", "Yes", "No", new Runnable() { @Override public void run() { host.exitApp(); } });
            return;
        }
        dismiss(true);
    }

    /** Long OK / Menu on a country or category row: offer to hide it. False = not applicable (caller favourites instead). */
    public boolean onLongOk() {
        if (!open || !rail.hasFocus() || level != 2 || !(l2Kind.equals("countries") || l2Kind.equals("cats"))) return false;
        if (railSel < rails.size() && !rails.get(railSel).divider) {
            final Rail r = rails.get(railSel);
            confirm("Hide this group?", r.label, "Hide", "Cancel", new Runnable() {
                @Override public void run() {
                    host.store().hide(r.id);
                    int keep = railSel;
                    buildL2();
                    railSel = Math.min(keep, l2.size() - 1);
                    if (railSel >= 0 && l2.get(railSel).divider) railSel = Math.max(0, railSel - 1);
                    railAd.notifyDataSetChanged();
                    rail.setSelection(railSel);
                    shownId = null;
                    if (!l2.isEmpty()) showSection(l2.get(railSel));
                    pill("Hidden. Unhide in Settings > For You");
                }
            });
        }
        return true;
    }

    /** CH+/CH- inside the guide: move the channel list a page (8 rows). */
    public void page(int d) {
        if (!list.hasFocus() || items.isEmpty()) return;
        int p = Math.max(0, Math.min(items.size() - 1, listSel + 8 * d));
        selectList(p);
    }

    // ---- two levels ----

    private void showLevel(int lv) {
        railHead.setVisibility(lv == 2 ? VISIBLE : GONE);
        if (lv == 2 && Ui.anim() && open) { // level 2 replaces the rail: it slides in from the left
            railCol.setTranslationX(-Ui.u(24));
            railCol.setAlpha(0f);
            railCol.animate().translationX(0).alpha(1f).setDuration(180).setInterpolator(Ui.DEC).start();
        }
    }

    private void enterLevel2(String kind, String selId, boolean focus) {
        if (level == 1) sel1 = railSel;
        level = 2;
        l2Kind = kind;
        buildL2();
        rails = l2;
        railHead.setText("‹ " + host.index().title(kind));
        showLevel(2);
        int pos = -1;
        if (selId != null) for (int i = 0; i < l2.size(); i++) if (l2.get(i).id.equals(selId)) pos = i;
        if (pos < 0 && memo.containsKey("L2:" + kind)) pos = memo.get("L2:" + kind);
        if (pos < 0 || pos >= l2.size() || l2.get(pos).divider) { pos = 0; while (pos < l2.size() - 1 && l2.get(pos).divider) pos++; }
        railSel = pos;
        shownId = null;
        railAd.notifyDataSetChanged();
        rail.setSelection(pos);
        if (!l2.isEmpty()) showSection(l2.get(pos));
        else { items.clear(); chanList = null; applyItems(0); }
        if (focus) rail.requestFocus();
    }

    private void exitLevel2(boolean focus) {
        level = 1;
        rails = l1;
        showLevel(1);
        railSel = Math.max(0, Math.min(sel1, l1.size() - 1));
        shownId = null;
        drill = null;
        railAd.notifyDataSetChanged();
        rail.setSelection(railSel);
        showSection(l1.get(railSel));
        if (focus) rail.requestFocus();
    }

    /** Level-2 rows for l2Kind: countries = pinned, divider, the rest by channel count; categories; sources. Hidden groups left out. */
    private void buildL2() {
        Index ix = host.index();
        Store st = host.store();
        Set<String> hid = st.hidden();
        l2.clear();
        if (l2Kind.equals("countries")) {
            Set<String> pinned = new java.util.LinkedHashSet<>();
            for (String c : st.fyCountries()) if (ix.countries.containsKey(c) && !hid.contains("cc:" + c)) pinned.add(c);
            for (String c : pinned) l2.add(countryRail(ix, st, c));
            if (!pinned.isEmpty()) l2.add(Rail.divider());
            for (String c : ix.countryOrder) if (!pinned.contains(c) && !hid.contains("cc:" + c)) l2.add(countryRail(ix, st, c));
        } else if (l2Kind.equals("cats")) {
            for (String c : ix.catOrder) if (!hid.contains("cat:" + c)) l2.add(new Rail("cat:" + c, c, ix.list("cat:" + c, st).size()));
        } else {
            for (Sources.Source sr : ix.sources) l2.add(new Rail("src:" + sr.id, sr.name, ix.folderOrder(sr.id).size()));
        }
    }

    private Rail countryRail(Index ix, Store st, String c) {
        return new Rail("cc:" + c, Ui.flag(c) + "  " + Ui.country(c), ix.list("cc:" + c, st).size());
    }

    /** Search key / voice search: open the guide on the Search section with the keyboard ready. */
    public void openSearch() {
        if (!open) open();
        if (level == 2) { rails = l1; level = 1; showLevel(1); }
        for (int i = 0; i < rails.size(); i++) if (rails.get(i).id.equals("search")) { railSel = i; break; }
        railAd.notifyDataSetChanged();
        rail.setSelectionFromTop(railSel, Ui.u(150));
        showSection(rails.get(railSel));
        searchEt.requestFocus();
    }

    /** Green key: open the guide on the Categories list. */
    public void openCategories() {
        if (!open) open();
        if (level == 2) { rails = l1; level = 1; showLevel(1); }
        for (int i = 0; i < l1.size(); i++) if (l1.get(i).id.equals("cats")) { railSel = i; break; }
        railAd.notifyDataSetChanged();
        rail.setSelection(railSel);
        shownId = null;
        enterLevel2("cats", null, true);
    }

    /** Settings key: open the guide on Settings. */
    public void openSettings() {
        if (!open) open();
        if (level == 2) { rails = l1; level = 1; showLevel(1); }
        for (int i = 0; i < rails.size(); i++) if (rails.get(i).id.equals("settings")) { railSel = i; break; }
        railAd.notifyDataSetChanged();
        rail.setSelectionFromTop(railSel, Ui.u(150));
        shownId = null;
        showSection(rails.get(railSel));
        focusContent();
    }

    /** A version choice changed the row of some group: redraw the list on screen from the re-ranked index. */
    public void onVersionsChanged() {
        if (!open) return;
        String id = drill != null ? drill : shownId;
        if (chanList != null && id != null && !"search".equals(id) && !id.startsWith("src:")) {
            chanList = host.index().list(id, host.store());
            items.clear();
            items.addAll(chanList);
            applyItems(listSel);
        } else listAd.notifyDataSetChanged();
    }

    /** A remote-keys modal is up: the activity routes every key to {@link #modalKey}. */
    public boolean modalUp() { return open && kmodal.isUp(); }
    public boolean modalKey(KeyEvent e) { return kmodal.key(e); }

    /** Playlists were reloaded (source toggled, refreshed…): rebuild the rail and redraw what is shown. */
    public void onIndexChanged() {
        if (!open) return;
        String keep = railSel < rails.size() ? rails.get(railSel).id : "foryou";
        buildRails();
        if (level == 2) buildL2();
        railSel = 0;
        for (int i = 0; i < rails.size(); i++) if (rails.get(i).id.equals(keep)) railSel = i;
        railAd.notifyDataSetChanged();
        rail.setSelection(railSel); // the ListView keeps the old index, the rows moved
        settings.refresh();
        if (!keep.equals("settings") && !keep.equals("search") && drill == null) { shownId = null; showSection(rails.get(railSel)); }
    }

    /** Rail counts follow the channels that are visible now (after "Show unavailable" flips). */
    private void refreshCounts() {
        Index ix = host.index();
        for (Rail r : l1) if (r.id.equals("foryou") || r.id.equals("lk")) r.count = ix.list(r.id, host.store()).size();
        railAd.notifyDataSetChanged();
    }

    /** For You picker closed with changes: recompute the list off the UI thread. */
    private void forYouChanged() {
        final Index ix = host.index();
        final Store st = host.store();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Channel> fy = Index.forYou(ix.all, st);
                ix.forYou = fy;
                h.post(new Runnable() { @Override public void run() { refreshCounts(); } });
            }
        }, "foryou").start();
    }

    private boolean imeVisible() {
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        android.view.WindowInsets wi = getRootWindowInsets();
        return wi != null && wi.isVisible(android.view.WindowInsets.Type.ime());
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    /** Channel under the cursor, or null (rail focused / country row). */
    public Channel selected() {
        if (!list.hasFocus() || listSel < 0 || listSel >= items.size()) return null;
        Object o = items.get(listSel);
        return o instanceof Channel ? (Channel) o : null;
    }

    public void onFavChanged() {
        refreshFavs();
        for (Rail r : l1) if (r.id.equals("fav")) r.count = host.index().list("fav", host.store()).size();
        railAd.notifyDataSetChanged();
        if ("fav".equals(shownId) && drill == null) {
            chanList = host.index().list("fav", host.store());
            items.clear();
            items.addAll(chanList);
            applyItems(Math.min(listSel, Math.max(0, items.size() - 1)));
        } else listAd.notifyDataSetChanged();
    }

    public void setPreviewFailed() {
        cStatus.setVisibility(VISIBLE);
        if (card.getAlpha() < 1f) showCard();
    }

    // ---- sections ----

    private void refreshFavs() { favSet.clear(); favSet.addAll(host.store().favs()); }

    private void buildRails() {
        Index ix = host.index();
        Store s = host.store();
        l1.clear();
        l1.add(new Rail("fav", "★ Favourites", ix.list("fav", s).size()));
        l1.add(new Rail("recent", "⟲ Recent", ix.list("recent", s).size()));
        l1.add(new Rail("foryou", "For You", ix.list("foryou", s).size()));
        l1.add(new Rail("lk", "Sri Lanka", ix.list("lk", s).size()));
        Set<String> hid = s.hidden();
        int nc = 0, nk = 0;
        for (String c : ix.countryOrder) if (!hid.contains("cc:" + c)) nc++;
        for (String c : ix.catOrder) if (!hid.contains("cat:" + c)) nk++;
        l1.add(Rail.nav("countries", "🌍 Countries", nc));
        l1.add(Rail.nav("cats", "▦ Categories", nk));
        if (!(lk.manus.iptv.Config.DEBUG && Ui.demoBlur)) l1.add(new Rail("yt", "▶ YouTube Live", YouTubeLive.ALL.size())); // demo recordings hide this rail
        if (ix.sources.size() > 1) l1.add(Rail.nav("srcs", "▤ My Sources", ix.sources.size()));
        l1.add(new Rail("search", "🔍 Search", -1));
        l1.add(new Rail("settings", "⚙ Settings", -1));
    }

    private String memoKey() { return drill != null ? drill : String.valueOf(shownId); }

    /** Channels of a drill id ("cc:xx" or "sf:id TAB folder"), or null if it no longer exists. */
    private List<Channel> drillList(String id) {
        List<Channel> l = host.index().list(id, host.store());
        return l.isEmpty() ? null : l;
    }

    private void showSection(Rail r) {
        h.removeCallbacks(railDwell);
        if (r.id.equals(shownId)) return;
        host.index().refresh(host.store()); // new ranking is picked up here, never under the user's cursor
        shownId = r.id;
        drill = null;
        boolean isSettings = r.id.equals("settings"), isSearch = r.id.equals("search");
        searchEt.setVisibility(isSearch ? VISIBLE : GONE);
        settings.setVisibility(GONE);
        col2.setVisibility(isSettings ? GONE : VISIBLE);
        if (isSettings) { settings.show(); items.clear(); chanList = null; listAd.notifyDataSetChanged(); hideCard(); return; }
        settings.hide();
        if (r.nav) { items.clear(); chanList = null; applyItems(0); return; } // level-1 "›" row: a hint until OK / Right opens it
        if (r.id.equals("yt")) { showYouTube(); return; }
        if (r.id.startsWith("src:")) { showFolders(r.id.substring(4)); return; }
        items.clear();
        chanList = null;
        if (isSearch) { items.addAll(lastResults); chanList = lastResults; }
        else {
            chanList = host.index().list(r.id, host.store());
            items.addAll(chanList);
        }
        applyItems(memo.containsKey(r.id) ? memo.get(r.id) : 0);
    }

    /** YouTube Live rows are not streams: no preview, no zap. OK hands the channel's live page to the YouTube app. */
    private void showYouTube() {
        items.clear();
        chanList = null;
        for (YouTubeLive.Entry e : YouTubeLive.ALL) items.add(new CC("yt:" + e.channelId, e.name, "▶", "opens YouTube"));
        applyItems(memo.containsKey("yt") ? memo.get("yt") : 0);
    }

    private void openYouTube(String channelId) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/channel/" + channelId + "/live"));
        try {
            getContext().startActivity(i.setPackage(YouTubeLive.TV_PACKAGE));
        } catch (ActivityNotFoundException e) {
            try {
                getContext().startActivity(new Intent(Intent.ACTION_VIEW, i.getData()));
            } catch (ActivityNotFoundException e2) {
                pill("YouTube app not found");
                return;
            }
        }
        hideNow();
    }

    private void pill(String t) { host.pill(t); }

    private void showFolders(String srcId) {
        Index ix = host.index();
        items.clear();
        chanList = null;
        for (String f : ix.folderOrder(srcId)) {
            String id = "sf:" + srcId + "\t" + f;
            items.add(new CC(id, f, "📁", nf.format(ix.list(id, host.store()).size()) + "  ›"));
        }
        applyItems(memo.containsKey("src:" + srcId) ? memo.get("src:" + srcId) : 0);
    }

    private void enterDrill(String id) {
        host.index().refresh(host.store());
        memo.put(shownId, listSel);
        drill = id;
        chanList = host.index().list(id, host.store());
        items.clear();
        items.addAll(chanList);
        applyItems(memo.containsKey(id) ? memo.get(id) : 0);
    }

    private List<Channel> lastResults = new ArrayList<>();

    /** Debounced (150 ms) filter on a background thread; stale results are dropped. */
    private void runSearch() {
        final String q = searchEt.getText().toString();
        final Index ix = host.index();
        final int seq = ++searchSeq;
        final java.util.Set<String> favs = new HashSet<>(favSet);
        new Thread(new Runnable() {
            @Override public void run() {
                long t = SystemClock.elapsedRealtime();
                final List<Channel> r = ix.search(q, favs);
                Log.i(TAG, "search \"" + q.length() + " chars\" -> " + r.size() + " results in " + (SystemClock.elapsedRealtime() - t) + " ms");
                h.post(new Runnable() {
                    @Override public void run() {
                        if (seq != searchSeq || !"search".equals(shownId)) return;
                        lastQuery = q;
                        lastResults = r;
                        items.clear();
                        items.addAll(r);
                        chanList = r;
                        applyItems(0);
                    }
                });
            }
        }, "search").start();
    }

    private void applyItems(int pos) {
        boolean isSearch = "search".equals(shownId) && drill == null;
        boolean navHint = level == 1 && drill == null && (shownId.equals("countries") || shownId.equals("cats") || shownId.equals("srcs"));
        if (drill != null) {
            title.setText(drill.startsWith("cc:") ? Ui.country(drill.substring(3)) : host.index().title(drill));
            sub.setText((drill.startsWith("cc:") ? Ui.flag(drill.substring(3)) + "  " : "") + nf.format(items.size()) + " channels");
        } else {
            title.setText(host.index().title(shownId));
            sub.setText(shownId.equals("settings") || navHint ? "" : isSearch && items.isEmpty() ? "" : nf.format(items.size())
                    + (shownId.startsWith("src:") ? " folders" : items.size() == 1 ? " channel" : " channels"));
            if (shownId.startsWith("src:")) title.setText(host.index().srcName(shownId.substring(4)));
        }
        String e = "";
        if (items.isEmpty()) {
            if (navHint) e = "Press OK or ›\nto open";
            else if (shownId.equals("fav")) e = "★\n\nLong-press OK on any channel to add it here";
            else if (shownId.equals("recent")) e = "Channels you watch will show up here";
            else if (isSearch) e = searchEt.getText().length() == 0 ? "Press OK to open the keyboard\nor use the mic on your remote" : "No channels match";
            else e = host.index().all.isEmpty() ? "No channels yet \u2014 add a playlist in Settings \u2192 Sources" : "No channels";
        }
        empty.setText(e);
        empty.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        listSel = Math.max(0, Math.min(pos, items.size() - 1));
        listAd.notifyDataSetChanged();
        list.setSelectionFromTop(listSel, Ui.u(130));
        h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini();
        hideCard();
        if (list.hasFocus()) dwell();
    }

    private void selectList(int pos) { listSel = pos; list.setSelectionFromTop(pos, Ui.u(130)); }

    private void focusList() {
        if (railSel < rails.size()) showSection(rails.get(railSel));
        focusContent();
    }

    private void focusContent() {
        if ("settings".equals(shownId)) settings.focusContent();
        else if ("search".equals(shownId) && drill == null) searchEt.requestFocus();
        else list.requestFocus();
    }

    // ---- preview ----

    /** Mini preview allowed: setting = Auto, not switched off on this TV, and the TV reports two H.264 hardware decoder instances. */
    private boolean miniOn() {
        if (!host.store().miniAuto() || !host.store().miniBlocked().isEmpty()) return false;
        if (avc < 0) { avc = MiniPreview.avcInstances(); Log.i(TAG, "mini: H.264 hardware decoder instances = " + avc + ", tier " + Ui.tierName() + (avc >= 2 ? " -> preview allowed" : " -> logo only")); }
        return avc >= 2;
    }

    /** The mini window is on for this TV right now (Settings shows it). */
    public static String miniState(Context c, Store st) {
        if (!st.miniAuto()) return "Off";
        if (!st.miniBlocked().isEmpty()) return "Auto (off: " + st.miniBlocked() + ")";
        int n = MiniPreview.avcInstances();
        return n >= 2 ? "Auto (on)" : "Auto (off: this TV has one video decoder)";
    }

    private void blockMini(String why) {
        Log.i(TAG, "mini: off for this TV - " + why);
        host.store().setMiniBlocked(why.length() > 60 ? why.substring(0, 60) : why);
        host.pill("Mini preview turned off for this TV");
    }

    private void fitMini(int vw, int vh) {
        int cw = cMini.getWidth(), ch = cMini.getHeight();
        if (cw <= 0 || vw <= 0 || vh <= 0) return;
        int w = cw, hh = cw * vh / vw;
        if (hh > ch) { hh = ch; w = ch * vw / vh; }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cTex.getLayoutParams();
        if (lp.width != w || lp.height != hh) { lp.width = w; lp.height = hh; cTex.setLayoutParams(lp); }
    }

    private void stopMini() {
        h.removeCallbacks(miniWatchR);
        mini.stop();
        cTex.animate().cancel();
        cTex.setAlpha(0f);
    }

    private void dwell() {
        h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini();
        hideCard();
        cStatus.setVisibility(GONE);
        if (selected() == null) return;
        h.postDelayed(cardR, CARD_MS);
        h.postDelayed(liveR, LIVE_MS);
    }

    /** The playing channel has / has no subtitle tracks: a "CC" chip on the preview card (when it shows that channel). */
    public void setCC(boolean on) {
        cc = on;
        if (on && card.getVisibility() == VISIBLE) addCcChip();
    }

    private void addCcChip() {
        Channel c = selected();
        if (!cc || c == null || !c.url.equals(liveUrl)) return;
        TextView t = Ui.tv(getContext(), Ui.MICRO, Ui.TXT, true);
        t.setText("CC");
        t.setBackground(Ui.rect(0x5500D4FF, 12));
        t.setPadding(Ui.u(10), Ui.u(5), Ui.u(10), Ui.u(5));
        chips.addView(t);
    }

    /** Now / next on the preview card for the selected channel. */
    private void refreshNow() {
        Channel c = selected();
        long t = System.currentTimeMillis();
        CloudData.Slot n = c == null ? null : CloudData.now(c.id, t), x = c == null ? null : CloudData.next(c.id, t);
        cNow.setVisibility(n == null ? GONE : VISIBLE);
        cBar.setVisibility(n == null ? GONE : VISIBLE);
        cTimes.setVisibility(n == null ? GONE : VISIBLE);
        cNext.setVisibility(x == null ? GONE : VISIBLE);
        if (n != null) { cNow.setText(n.title); cTimes.setText("Now  ·  " + CloudData.hm(n.start) + "–" + CloudData.hm(n.stop)); cBar.set(n.progress(t)); }
        if (x != null) cNext.setText("Next  " + CloudData.hm(x.start) + "  ·  " + x.title);
    }

    private final Runnable nowR = new Runnable() {
        @Override public void run() {
            if (open) {
                listAd.notifyDataSetChanged();
                if (card.getVisibility() == VISIBLE) refreshNow();
            }
            h.postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 200); // on the minute: programme changes land within a second
        }
    };

    /** Fresh channel data arrived (UI thread). */
    public void onCloudData() { if (open) { listAd.notifyDataSetChanged(); if (card.getVisibility() == VISIBLE) refreshNow(); } }

    private void hideCard() { stopMini(); h.removeCallbacks(speedR); card.animate().cancel(); card.setAlpha(0f); card.setVisibility(INVISIBLE); }

    private void showCard() {
        Channel c = selected();
        if (c == null || !cardOk) return;
        LogoLoader.get(getContext()).load(c.logo, c.name, cLogo);
        cName.setText(c.name);
        chips.removeAllViews();
        int n = 0;
        for (String g : c.groups) {
            if (n == 3) break;
            TextView t = Ui.tv(getContext(), Ui.MICRO, Ui.TXT, true);
            t.setText(Index.groupLabel(g));
            t.setBackground(Ui.rect(0x557C5CFF, 12));
            t.setPadding(Ui.u(10), Ui.u(5), Ui.u(10), Ui.u(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.u(6);
            chips.addView(t, lp);
            n++;
        }
        StringBuilder s = new StringBuilder();
        if (!c.country.isEmpty()) s.append(Ui.flag(c.country)).append(' ').append(Ui.country(c.country));
        if (!c.quality.isEmpty()) s.append(s.length() > 0 ? "  ·  " : "").append(c.quality);
        cInfo.setText(s);
        addCcChip();
        refreshNow();
        card.setVisibility(VISIBLE);
        cSpeed.setText(host.netSpeed());
        h.removeCallbacks(speedR);
        h.postDelayed(speedR, 1000);
        if (Ui.anim()) {
            card.setTranslationY(Ui.u(16));
            card.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(Ui.DEC).start();
        } else { card.setTranslationY(0); card.setAlpha(1f); }
    }

    // ---- rows ----

    /** setText only when the text changed: a bind that changes nothing causes no relayout (rows have a fixed height). */
    private static void put(TextView t, CharSequence s) { if (!android.text.TextUtils.equals(t.getText(), s)) t.setText(s); }

    private static String num(int n) {
        while (NUMS.size() <= n) NUMS.add(String.valueOf(NUMS.size()));
        return NUMS.get(n);
    }

    /** The rail keeps a soft highlight on the open section while the list has focus (state_activated). */
    private void refreshRail() {
        for (int i = 0; i < rail.getChildCount(); i++) {
            if (!(rail.getChildAt(i) instanceof Row)) continue;
            Row r = (Row) rail.getChildAt(i);
            r.setActivated(r.pos == railSel);
        }
    }

    /**
     * List rows are highlighted by the ListView's own selected state (no callback, no extra frame). While key-repeat
     * scrolling ({@link #fast}) the 1.03 scale is applied instantly instead of animated.
     */
    private abstract class Row extends LinearLayout {
        int pos = -1;
        Row(Context c, int hDp) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(hDp)));
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_selected}, Ui.ring(Ui.SURF));
            sl.addState(new int[]{android.R.attr.state_activated}, Ui.rect(0x66141A2A, 14));
            sl.addState(new int[]{}, new ColorDrawable(0));
            setBackground(sl);
        }
        @Override public void setSelected(boolean s) {
            boolean was = isSelected();
            super.setSelected(s);
            if (s == was || pos < 0) return;
            float sc = s ? 1.03f : 1f;
            animate().cancel();
            if (!Ui.anim()) return; // Low tier: the gradient ring alone marks the focus, no scale
            if (fast || !open) { setScaleX(fast ? 1f : sc); setScaleY(fast ? 1f : sc); } // key-repeat: no scale at all (calmR redraws once settled)
            else animate().scaleX(sc).scaleY(sc).setDuration(160).setInterpolator(Ui.DEC).start();
        }
        /** Called from bind: a recycled view starts in its final state. */
        void settle() {
            if (!Ui.anim()) return;
            animate().cancel();
            float sc = isSelected() && !fast ? 1.03f : 1f;
            setScaleX(sc);
            setScaleY(sc);
        }
    }

    private final class RailRow extends Row {
        private final TextView label, count;
        RailRow(Context c) {
            super(c, 48);
            setPadding(Ui.u(10), 0, Ui.u(8), 0);
            label = Ui.tv(c, Ui.BODY, Ui.TXT, true);
            label.setDuplicateParentStateEnabled(true);
            label.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {android.R.attr.state_activated}, {}},
                    new int[]{Ui.TXT, Ui.CYAN, Ui.TXT}));
            count = Ui.tv(c, Ui.CAPTION, Ui.SEC, false);
            count.setPadding(Ui.u(4), 0, 0, 0);
            addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            addView(count);
        }
        void bind(Rail r, int p) {
            pos = p;
            put(label, r.label);
            put(count, r.count < 0 ? "" : nf.format(r.count) + (r.nav ? " ›" : ""));
            setActivated(p == railSel);
            settle();
        }
    }

    private final class CountryRow extends Row {
        private final TextView flag, name, count;
        CountryRow(Context c) {
            super(c, 56);
            setPadding(Ui.u(14), 0, Ui.u(16), 0);
            flag = Ui.tv(c, Ui.TITLE, Ui.TXT, false);
            name = Ui.tv(c, Ui.BODY, Ui.TXT, true);
            name.setSingleLine(false); name.setMaxLines(2); // "United States" vs "United Kingdom" must stay readable
            count = Ui.tv(c, Ui.CAPTION, Ui.SEC, false);
            count.setPadding(Ui.u(6), 0, 0, 0);
            flag.setPadding(0, 0, Ui.u(10), 0);
            addView(flag);
            addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            addView(count);
        }
        void bind(CC c, int p) {
            pos = p;
            put(flag, c.flag);
            put(name, c.name);
            put(count, c.countText);
            settle();
        }
    }

    private final class ChanRow extends Row {
        private final TextView num, name, info, live, star, chQ, chF, chV;
        private final ImageView logo;
        private final NowBar bar;
        private TextView chip(Context c, float sp, int bg) {
            TextView t = Ui.tv(c, sp, Ui.TXT, true);
            t.setPadding(Ui.u(4), Ui.u(2), Ui.u(4), Ui.u(2));
            if (bg != 0) t.setBackground(Ui.rect(bg, 8));
            return t;
        }
        ChanRow(Context c) {
            super(c, 64);
            setPadding(Ui.u(12), 0, Ui.u(12), 0);
            num = Ui.tv(c, Ui.CAPTION, Ui.SEC, true);
            num.setMinWidth(Ui.u(26));
            logo = new ImageView(c);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setBackground(Ui.rect(Ui.SURF, 8));
            logo.setPadding(Ui.u(3), Ui.u(3), Ui.u(3), Ui.u(3));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setPadding(Ui.u(12), 0, 0, 0);
            // line 1: name + right-aligned chips (quality, flag, N versions)
            LinearLayout l1 = new LinearLayout(c);
            l1.setGravity(Gravity.CENTER_VERTICAL);
            name = Ui.tv(c, Ui.BODY, Ui.TXT, true);
            chQ = chip(c, Ui.MICRO, 0x557C5CFF);
            chF = chip(c, Ui.MICRO, 0);
            chV = chip(c, Ui.MICRO, 0x5500D4FF);
            l1.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.leftMargin = Ui.u(5);
            l1.addView(chV, cp);
            l1.addView(chQ, cp);
            l1.addView(chF, cp);
            col.addView(l1);
            // line 2: now-title (or category) + live / favourite marks, then the progress bar
            LinearLayout l2 = new LinearLayout(c);
            l2.setGravity(Gravity.CENTER_VERTICAL);
            l2.setPadding(0, Ui.u(3), 0, 0);
            info = Ui.tv(c, Ui.CAPTION, Ui.SEC, false);
            live = Ui.tv(c, Ui.MICRO, Ui.CYAN, true);
            live.setText("LIVE ●");
            live.setPadding(Ui.u(6), 0, 0, 0);
            star = Ui.tv(c, Ui.CAPTION, Ui.STAR, false);
            star.setText("★");
            star.setPadding(Ui.u(6), 0, 0, 0);
            l2.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            l2.addView(live);
            l2.addView(star);
            col.addView(l2);
            bar = new NowBar(c);
            bar.setVisibility(GONE);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.u(2));
            bp.topMargin = Ui.u(4);
            col.addView(bar, bp);
            addView(num);
            addView(logo, new LinearLayout.LayoutParams(Ui.u(56), Ui.u(36)));
            addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        }
        void bind(Channel c, int p) {
            pos = p;
            put(num, num(p + 1));
            put(name, c.name);
            put(chQ, c.quality.isEmpty() ? "—" : c.quality);
            chQ.setAlpha(c.quality.isEmpty() ? 0.5f : 1f);
            String f = Ui.flag(c.country);
            put(chF, f.isEmpty() ? "🌐" : f);
            int nv = 1 + Dedup.alternates(c).size();
            chV.setVisibility(nv > 1 ? VISIBLE : GONE);
            if (nv > 1) put(chV, "×" + nv);
            if (c.info == null) c.info = c.groups.length > 0 ? Index.groupLabel(c.groups[0]) : "Other"; // built once per channel, not once per bind
            boolean dead = Health.get(getContext()).status(c.url) == Health.DEAD;
            CloudData.Slot now = dead ? null : CloudData.now(c.id, System.currentTimeMillis());
            put(info, dead ? "Unavailable" : now != null ? now.title : c.info);
            bar.setVisibility(now != null ? VISIBLE : GONE);
            if (now != null) bar.set(now.progress(System.currentTimeMillis()));
            float al = dead ? 0.45f : 1f;
            if (getAlpha() != al) setAlpha(al);
            h.removeCallbacks(probeR);
            h.postDelayed(probeR, 400);
            live.setVisibility(c.url.equals(liveUrl) ? VISIBLE : GONE);
            star.setVisibility(favSet.contains(c.url) ? VISIBLE : GONE);
            LogoLoader.get(getContext()).load(c.logo, c.name, logo);
            settle();
        }
    }

    /** Live preview for Settings > Screen edges: a frame on the title-safe rectangle. */
    private static final class SafeFrame extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final Runnable off = new Runnable() { @Override public void run() { hideNow(); } };
        SafeFrame(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Ui.u(3));
            p.setColor(Ui.CYAN);
            setVisibility(GONE);
        }
        void show() { removeCallbacks(off); setVisibility(VISIBLE); invalidate(); }
        void flash() { show(); postDelayed(off, 4000); }
        void hideNow() { removeCallbacks(off); setVisibility(GONE); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            c.drawRoundRect(Ui.marginX(), Ui.marginY(), getWidth() - Ui.marginX(), getHeight() - Ui.marginY(), Ui.u(14), Ui.u(14), p);
        }
    }
}
