package lk.manus.iptv.ui.wizard;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lk.manus.iptv.Config;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Curated;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Sources;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.ui.Keys;
import lk.manus.iptv.ui.Ui;

/** First-run / "Run setup again" wizard: 8 full-screen steps; choices are kept in memory and saved on "Start watching". */
public final class WizardActivity extends Activity {
    private static final int N = 8;
    private static final String[] LANGS = {"Sinhala", "Tamil", "English", "Hindi", "Malayalam", "Telugu", "Arabic", "Urdu", "Bengali", "French", "Spanish", "Korean"};
    private static final String[][] CATS = {{"News", "📰"}, {"Sports", "⚽"}, {"Movies", "🎬"}, {"TV Series", "📺"}, {"Entertainment", "🎭"},
        {"Kids", "🧸"}, {"Music", "🎵"}, {"Documentary", "🌍"}, {"Religious", "🙏"}, {"Business", "💼"}};
    private static final String[] FALLBACK = {"lk", "in", "uk", "us", "ca", "au", "pk", "bd", "ae", "fr", "de", "es", "it", "tr", "sa", "sg", "my", "np", "jp", "kr", "za", "nz", "ie", "qa"};
    private static final String[] QN = {"Auto", "Data saver", "Best"}, QV = {"auto", "saver", "best"};
    private static final String[] WN = {"Off", "Top-left", "Top-right", "Bottom-left", "Bottom-right"}, WV = {"off", "tl", "tr", "bl", "br"};

    private Store st;
    private Wiz w;
    private FrameLayout root;
    private int step;
    private boolean first;

    // draft choices
    private Set<String> langs, cats;
    private List<String> order;
    private String quality, wm;
    private boolean skip, subs, even;
    private Map<String, Integer> counts;
    private int grabbed = -1;
    private int haveSources;        // sources already saved (step 2 explains instead of offering)
    private String addMsg = "";     // result line of the free-list download
    private boolean addBusy, addOwn;
    private TextView keyOut, keyDesc, warn;
    private Wiz.Tile nextBtn;
    private LinearLayout orderBox;
    private GridLayout countryGrid;
    private final Map<String, Wiz.Tile> countryTiles = new HashMap<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        st = new Store(getSharedPreferences("iptv", MODE_PRIVATE));
        Ui.init(this);
        w = new Wiz(this);
        first = getIntent().getBooleanExtra("first", false);
        Set<String> l = st.langs();
        langs = l != null ? l : new LinkedHashSet<>(Arrays.asList("Sinhala", "Tamil", "English"));
        order = new ArrayList<>(first ? Curated.DEFAULT_COUNTRIES : st.fyCountries());
        cats = new LinkedHashSet<>();
        if (first) cats.addAll(Arrays.asList("News", "Movies", "TV Series", "Kids", "Sports"));
        else for (String c : st.fyCats()) cats.add(Index.merged(c));
        quality = st.quality();
        skip = st.autoSkip();
        subs = st.subsDefault();
        wm = st.wmPos();
        even = st.evenVolume();
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F19);
        setContentView(root);
        haveSources = new Sources(getSharedPreferences("iptv", MODE_PRIVATE), getFilesDir(), null).list().size();
        loadCounts();
        show(0);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        w.update(this);
        show(step);
    }

    /** Channel counts per country from the cached playlist (first delivery only); step 4 redraws when they arrive. */
    private void loadCounts() {
        new Sources(getSharedPreferences("iptv", MODE_PRIVATE), getFilesDir(), null).load(new Sources.Callback() {
            @Override public void onLoaded(Sources.Loaded ld) {
                if (counts != null || isFinishing() || ld.merged.isEmpty()) return;
                Map<String, Integer> m = new HashMap<>();
                for (Channel c : ld.merged) if (!c.country.isEmpty()) { Integer n = m.get(c.country); m.put(c.country, n == null ? 1 : n + 1); }
                counts = m;
                if (step == 3) show(3);
            }
            @Override public void onError() { }
        });
    }

    // ---- shell ----

    private void show(int n) {
        step = n;
        grabbed = -1;
        keyOut = keyDesc = warn = null;
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.marginX(), Ui.marginY(), Ui.marginX(), Ui.marginY()); // title-safe margins (Settings > Display > Screen edges)
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < N; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(w.u(6));
            g.setColor(i == n ? Ui.CYAN : i < n ? Ui.VIOLET : 0xFF2A3050);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w.u(i == n ? 28 : 10), w.u(10));
            lp.rightMargin = w.u(8);
            top.addView(d, lp);
        }
        top.addView(w.tv(this, "Step " + (n + 1) + " of " + N, 14, Ui.SEC, false));
        page.addView(top, new LinearLayout.LayoutParams(-1, -2));

        String[] t = {"Welcome", "Add channels", "Which languages do you watch?", "Which countries come first?", "What do you like to watch?", "How should it play?", "Your remote", "All set"};
        String[] s = {"", Config.PLAY ? "Manus IPTV plays the playlists you add. Add yours now or later." : "Manus IPTV plays channel lists you add. Add one now or later.", "Pick all that apply. Channels in these languages rank higher.",
            "Tick countries on the left. Your order on the right sets what you see first.",
            "These make up your For You list. You can change them any time.",
            "Everything here can be changed later in Settings.",
            "Here is what each key does. Try your own remote: press any key.",
            "Here is what we will set up."};
        nextBtn = w.button(this, n == 0 ? "Start" : n == N - 1 ? "Start watching" : n == 1 && haveSources == 0 ? "Skip" : "Next", true);
        nextBtn.setId(View.generateViewId());
        View focus;
        View body;
        switch (n) {
            case 0: body = welcome(); break;
            case 1: body = channels(); break;
            case 2: body = languages(); break;
            case 3: body = countries(); break;
            case 4: body = categories(); break;
            case 5: body = playback(); break;
            case 6: body = remote(); break;
            default: body = done(); break;
        }
        if (n > 0) {
            TextView title = w.tv(this, t[n], 34, Ui.TXT, true);
            title.setPadding(0, w.u(12), 0, 0);
            page.addView(title);
            TextView sub = w.tv(this, s[n], 18, Ui.SEC, false);
            sub.setPadding(0, w.u(4), 0, w.u(12));
            page.addView(sub);
        }
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, w.u(12), 0, 0);
        Wiz.Tile next, back = null;
        if (n == 0) {
            next = nextBtn;
            back = w.button(this, "Skip setup", false);
            back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { st.markSetupDone(false); finish(); } });
        } else {
            back = w.button(this, "Back", false);
            back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { show(step - 1); } });
            next = nextBtn;
        }
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { next(); } });
        if (n == 0) { foot.addView(next); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = w.u(16); foot.addView(back, lp); foot.setGravity(Gravity.CENTER); }
        else {
            foot.addView(back);
            warn = w.tv(this, "", 18, Ui.BAD, true);
            warn.setGravity(Gravity.CENTER);
            foot.addView(warn, new LinearLayout.LayoutParams(0, -2, 1));
            foot.addView(next);
        }
        page.addView(foot, new LinearLayout.LayoutParams(-1, -2));
        focus = next;
        final View f = focus;
        root.post(new Runnable() { @Override public void run() { f.requestFocus(); } });
    }

    private void next() {
        if (step == 3 && order.isEmpty()) { warn.setText("Pick at least one country"); return; }
        if (step == 4 && cats.isEmpty()) { warn.setText("Pick at least one kind of channel"); return; }
        if (step == N - 1) { apply(); finish(); return; }
        show(step + 1);
    }

    @Override public void onBackPressed() {
        if (step > 0) show(step - 1); else finish(); // Back never quits the app: the screen under the wizard stays
    }

    /** Skip = defaults untouched; Done = write every choice once and bump the change counter so For You refreshes. */
    private void apply() {
        st.setFyCountries(new LinkedHashSet<>(order));
        st.setFyCats(cats);
        st.setLangs(langs);
        st.setQuality(quality);
        st.setAutoSkip(skip);
        st.setSubsDefault(subs);
        st.setWmPos(wm);
        st.setEvenVolume(even);
        st.markSetupDone(true);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (step == 6 && keyOut != null && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int k = e.getKeyCode();
            keyOut.setText(keyName(k));
            String d = Keys.describe(k);
            keyDesc.setText(d != null ? d : "The app does nothing with this key");
        }
        return super.dispatchKeyEvent(e);
    }

    private static String keyName(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return "OK";
            case KeyEvent.KEYCODE_DPAD_UP: return "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "Right";
            case KeyEvent.KEYCODE_PROG_RED: return "Red";
            case KeyEvent.KEYCODE_PROG_GREEN: return "Green";
            case KeyEvent.KEYCODE_PROG_YELLOW: return "Yellow";
            case KeyEvent.KEYCODE_PROG_BLUE: return "Blue";
            default: String n = KeyEvent.keyCodeToString(k).replace("KEYCODE_", "").replace('_', ' ').toLowerCase(Locale.ROOT);
                return n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
        }
    }

    // ---- steps ----

    private View welcome() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        ImageView ic = new ImageView(this);
        int id = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (id != 0) ic.setImageDrawable(getDrawable(id));
        c.addView(ic, new LinearLayout.LayoutParams(w.u(110), w.u(110)));
        TextView t = w.tv(this, "Manus IPTV", 34, Ui.TXT, true);
        t.setPadding(0, w.u(16), 0, w.u(8));
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        TextView s = w.tv(this, "Let's pick your languages, countries and favourites in under a minute.", 22, Ui.SEC, false);
        s.setGravity(Gravity.CENTER);
        c.addView(s, new LinearLayout.LayoutParams(Math.round(w.wPx * 0.6f), -2));
        return c;
    }

    /** Step 2: where the channels come from. Nothing is added unless the user taps it. */
    private View channels() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        if (haveSources > 0) {
            c.addView(w.tv(this, "You already have " + haveSources + (haveSources == 1 ? " playlist" : " playlists") + ". Manage them any time in Settings \u2192 Sources.", 20, Ui.TXT, false));
            return c;
        }
        final TextView status = w.tv(this, addMsg, 18, Ui.CYAN, true);
        if (!Config.PLAY) {
            final Wiz.Tile free = w.new Tile(this, "Add the free public list (iptv-org, community-maintained)", null, false);
            free.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (!addBusy) addFreeList(status); }
            });
            c.addView(free, new LinearLayout.LayoutParams(-1, -2));
            TextView note = w.tv(this, "Streams are publicly available links collected by the iptv-org project; Manus IPTV doesn't host or provide content.", 14, Ui.SEC, false);
            note.setPadding(w.u(4), w.u(4), 0, w.u(14));
            c.addView(note);
        }
        final Wiz.Tile own = w.new Tile(this, "Add my own playlist link", null, false);
        own.setOn(addOwn);
        own.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addOwn = !addOwn; own.setOn(addOwn); }
        });
        c.addView(own, new LinearLayout.LayoutParams(-1, -2));
        TextView ownNote = w.tv(this, "After setup: Settings \u2192 Sources \u2192 Add source. Type the link, or send it from your phone with the QR code.", 14, Ui.SEC, false);
        ownNote.setPadding(w.u(4), w.u(4), 0, w.u(14));
        c.addView(ownNote);
        c.addView(status);
        return c;
    }

    /** Downloads and saves the free list (one-tap, only on request); a failure just says so and nothing is saved. */
    private void addFreeList(final TextView status) {
        addBusy = true;
        status.setTextColor(Ui.SEC);
        status.setText("Downloading the list\u2026");
        final Sources src = new Sources(getSharedPreferences("iptv", MODE_PRIVATE), getFilesDir(), null);
        new Thread(new Runnable() {
            @Override public void run() {
                final Sources.Probe p = src.probe(Sources.DEFAULT_URL);
                final boolean ok = p.error == null;
                if (ok) src.add(Sources.FREE_NAME, p);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        addBusy = false;
                        if (isFinishing()) return;
                        if (ok) { haveSources = 1; addMsg = ""; counts = null; loadCounts(); show(1); }
                        else { status.setTextColor(Ui.BAD); status.setText(p.error); }
                    }
                });
            }
        }, "wiz-add").start();
    }

    private GridLayout grid(int cols) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        return g;
    }

    private void cell(GridLayout g, View v) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL), GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f));
        lp.width = 0;
        lp.setMargins(w.u(4), w.u(4), w.u(4), w.u(4));
        g.addView(v, lp);
    }

    private View languages() {
        GridLayout g = grid(4);
        for (final String l : LANGS) {
            final Wiz.Tile t = w.new Tile(this, l, null, true);
            t.setOn(langs.contains(l));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (!langs.remove(l)) langs.add(l); t.setOn(langs.contains(l)); }
            });
            cell(g, t);
        }
        return g;
    }

    private View categories() {
        GridLayout g = grid(5);
        for (final String[] c : CATS) {
            final Wiz.Tile t = w.new Tile(this, c[0], null, true);
            TextView em = w.tv(this, c[1], 34, Ui.TXT, false);
            em.setGravity(Gravity.CENTER);
            t.addView(em, 0);
            t.setMinimumHeight(w.u(104));
            t.setOn(cats.contains(c[0]));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (!cats.remove(c[0])) cats.add(c[0]); t.setOn(cats.contains(c[0])); }
            });
            cell(g, t);
        }
        return g;
    }

    private List<String> topCountries() {
        List<String> src = new ArrayList<>();
        if (counts != null) {
            src.addAll(counts.keySet());
            Collections.sort(src, new Comparator<String>() {
                @Override public int compare(String a, String b) { return counts.get(b) - counts.get(a); }
            });
        } else src.addAll(Arrays.asList(FALLBACK));
        Set<String> t = new LinkedHashSet<>(order);
        for (String c : src) { if (t.size() >= 24) break; t.add(c); }
        List<String> out = new ArrayList<>(t);
        if (counts != null) Collections.sort(out, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                Integer x = counts.get(a), y = counts.get(b);
                return (y == null ? 0 : y) - (x == null ? 0 : x);
            }
        });
        if (out.remove("lk")) out.add(0, "lk"); // Sri Lanka first, like the guide
        return out;
    }

    private View countries() {
        LinearLayout row = new LinearLayout(this);
        countryTiles.clear();
        countryGrid = grid(3);
        for (final String cc : topCountries()) {
            final Wiz.Tile t = w.new Tile(this, Ui.flag(cc) + " " + Ui.country(cc), counts == null ? null : String.format(Locale.US, "%,d channels", counts.get(cc) == null ? 0 : counts.get(cc)), false);
            t.title.setSingleLine(false);
            t.title.setMaxLines(2); // long names ("United Kingdom") wrap instead of cutting
            t.setOn(order.contains(cc));
            countryTiles.put(cc, t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!order.remove(cc)) order.add(cc);
                    t.setOn(order.contains(cc));
                    grabbed = -1;
                    drawOrder(-1);
                }
            });
            cell(countryGrid, t);
        }
        row.addView(countryGrid, new LinearLayout.LayoutParams(0, -2, 7));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(w.u(16), 0, 0, 0);
        side.addView(w.tv(this, "Your order", 22, Ui.TXT, true));
        TextView hint = w.tv(this, "OK picks a country up, Up / Down moves it, OK drops it.", 14, Ui.SEC, false);
        hint.setPadding(0, w.u(4), 0, w.u(8));
        side.addView(hint);
        orderBox = new LinearLayout(this);
        orderBox.setOrientation(LinearLayout.VERTICAL);
        side.addView(orderBox);
        row.addView(side, new LinearLayout.LayoutParams(0, -2, 4));
        drawOrder(-1);
        return row;
    }

    /** Rebuild the ordered list on the right; focusIdx >= 0 puts focus back on that row (after a move). */
    private void drawOrder(final int focusIdx) {
        orderBox.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            final int idx = i;
            String cc = order.get(i);
            Wiz.Tile t = w.new Tile(this, (i + 1) + "  " + Ui.flag(cc) + " " + Ui.country(cc), grabbed == i ? "Moving: Up / Down, OK to drop" : null, false);
            t.setOn(grabbed == i);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { grabbed = grabbed == idx ? -1 : idx; drawOrder(idx); }
            });
            if (i == order.size() - 1) t.setNextFocusDownId(nextBtn.getId());
            t.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int k, KeyEvent e) {
                    if (grabbed != idx) return false;
                    if (k == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) { grabbed = -1; drawOrder(idx); } return true; }
                    boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
                    if (!up && !down) return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        int j = idx + (up ? -1 : 1);
                        if (j >= 0 && j < order.size()) { Collections.swap(order, idx, j); grabbed = j; drawOrder(j); }
                    }
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = w.u(4);
            orderBox.addView(t, lp);
        }
        if (order.isEmpty()) orderBox.addView(w.tv(this, "Nothing picked yet", 18, Ui.SEC, false));
        if (focusIdx >= 0 && focusIdx < order.size()) { final View v = orderBox.getChildAt(focusIdx); v.post(new Runnable() { @Override public void run() { v.requestFocus(); } }); }
    }

    private LinearLayout choice(LinearLayout col, String label, String cap, final String[] names, int sel, final java.util.function.IntConsumer on) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(w.tv(this, label, 18, Ui.TXT, true));
        l.addView(w.tv(this, cap, 14, Ui.SEC, false));
        r.addView(l, new LinearLayout.LayoutParams(w.u(300), -2));
        final List<Wiz.Tile> tiles = new ArrayList<>();
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.END);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Wiz.Tile t = w.new Tile(this, names[i], null, true);
            t.setOn(i == sel);
            tiles.add(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { for (int j = 0; j < tiles.size(); j++) tiles.get(j).setOn(j == idx); on.accept(idx); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = w.u(8);
            chips.addView(t, lp);
        }
        r.addView(chips, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = w.u(12);
        col.addView(r, lp);
        return r;
    }

    private View playback() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        final String[] off = {"Off", "On"};
        choice(c, "Picture quality", "Auto adapts to your connection", QN, Math.max(0, Arrays.asList(QV).indexOf(quality)), new java.util.function.IntConsumer() { @Override public void accept(int i) { quality = QV[i]; } });
        choice(c, "Skip channels that don't start", "Off: ask what to do after 15 s", off, skip ? 1 : 0, new java.util.function.IntConsumer() { @Override public void accept(int i) { skip = i == 1; } });
        choice(c, "Subtitles by default", "On when a channel has them", off, subs ? 1 : 0, new java.util.function.IntConsumer() { @Override public void accept(int i) { subs = i == 1; } });
        choice(c, "Corner logo", "Channel logo and title in a corner", WN, Math.max(0, Arrays.asList(WV).indexOf(wm)), new java.util.function.IntConsumer() { @Override public void accept(int i) { wm = WV[i]; } });
        choice(c, "Even out volume", "Quiet and loud channels sound alike", off, even ? 1 : 0, new java.util.function.IntConsumer() { @Override public void accept(int i) { even = i == 1; } });
        return c;
    }

    private View dot(int color, int sizeDp) {
        View d = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        d.setBackground(g);
        d.setLayoutParams(new LinearLayout.LayoutParams(w.u(sizeDp), w.u(sizeDp)));
        return d;
    }

    private TextView keyCap(String t, int color) {
        TextView v = w.tv(this, t, 14, color, true);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(w.u(14));
        g.setColor(0xFF2A3050);
        v.setBackground(g);
        v.setPadding(w.u(8), w.u(6), w.u(8), w.u(6));
        return v;
    }

    private View remote() {
        LinearLayout row = new LinearLayout(this);
        // the remote, drawn with views
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setPadding(w.u(14), w.u(14), w.u(14), w.u(14));
        GradientDrawable body = new GradientDrawable();
        body.setCornerRadius(w.u(36));
        body.setColor(Ui.SURF);
        r.setBackground(body);
        LinearLayout cols = new LinearLayout(this);
        int[] cc = {0xFFFF4D4D, 0xFF3DDC84, 0xFFFFC940, 0xFF4D9DFF};
        for (int c : cc) { View d = dot(c, 18); ((LinearLayout.LayoutParams) d.getLayoutParams()).setMargins(w.u(4), 0, w.u(4), 0); cols.addView(d); }
        r.addView(cols);
        GridLayout pad = new GridLayout(this);
        pad.setColumnCount(3);
        String[] p = {"", "▲", "", "◀", "OK", "▶", "", "▼", ""};
        for (String s : p) {
            TextView v = keyCap(s, Ui.TXT);
            v.setMinWidth(w.u(40));
            v.setMinHeight(w.u(32));
            if (s.isEmpty()) v.setBackground(null);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(w.u(2), w.u(2), w.u(2), w.u(2));
            pad.addView(v, lp);
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.topMargin = w.u(12);
        r.addView(pad, plp);
        for (String s : new String[]{"CH +  /  CH −", "0 – 9"}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = w.u(8);
            r.addView(keyCap(s, Ui.SEC), lp);
        }
        row.addView(r, new LinearLayout.LayoutParams(w.u(190), -2));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(w.u(20), 0, 0, 0);
        GridLayout leg = grid(2);
        String[] pick = {"Red", "Green", "Yellow", "Blue", "OK", "Up / Down", "Left", "Right", "CH +", "0 -", "Play", "Back"}; // by label: Keys.HELP rows move
        for (String pk : pick) {
            Object[] h = null;
            for (Object[] x : Keys.HELP) if (((String) x[0]).startsWith(pk)) { h = x; break; }
            if (h == null) continue;
            LinearLayout e = new LinearLayout(this);
            e.setPadding(0, w.u(3), w.u(12), w.u(3));
            TextView k = w.tv(this, ((String) h[0]).startsWith("Play") ? "Play / Pause" : (String) h[0], 14, ((Integer) h[2]) != 0 ? (Integer) h[2] : Ui.CYAN, true);
            e.addView(k, new LinearLayout.LayoutParams(w.u(112), -2));
            e.addView(w.tv(this, (String) h[1], 14, Ui.TXT, false), new LinearLayout.LayoutParams(0, -2, 1));
            cell(leg, e);
        }
        right.addView(leg);
        LinearLayout tryBox = new LinearLayout(this);
        tryBox.setOrientation(LinearLayout.VERTICAL);
        tryBox.setPadding(w.u(16), w.u(10), w.u(16), w.u(10));
        tryBox.setBackground(w.ring(0xFF0B0F19));
        keyOut = w.tv(this, "Press any key to try it", 22, Ui.CYAN, true);
        keyDesc = w.tv(this, "It is named here, even keys the app does not use.", 14, Ui.SEC, false);
        tryBox.addView(keyOut);
        tryBox.addView(keyDesc);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = w.u(12);
        right.addView(tryBox, tlp);
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View done() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        StringBuilder co = new StringBuilder();
        for (String x : order) co.append(co.length() == 0 ? "" : "   ›   ").append(Ui.flag(x)).append(' ').append(Ui.country(x));
        if (haveSources == 0) line(c, "Channels", addOwn ? "Add your playlist: Settings \u2192 Sources" : "None yet. Add a playlist in Settings \u2192 Sources");
        line(c, "Languages", langs.isEmpty() ? "Any" : String.join(", ", langs));
        line(c, "Countries first", co.length() == 0 ? "None" : co.toString());
        line(c, "You like", String.join(", ", cats));
        line(c, "Picture", QN[Math.max(0, Arrays.asList(QV).indexOf(quality))] + (skip ? ", skips dead channels" : "") + (subs ? ", subtitles on" : ""));
        line(c, "Extras", "Corner logo: " + WN[Math.max(0, Arrays.asList(WV).indexOf(wm))] + ", even volume: " + (even ? "on" : "off"));
        return c;
    }

    private void line(LinearLayout c, String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, w.u(6), 0, w.u(6));
        r.addView(w.tv(this, k, 18, Ui.SEC, false), new LinearLayout.LayoutParams(w.u(190), -2));
        r.addView(w.tv(this, v, 18, Ui.TXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(r);
    }
}
