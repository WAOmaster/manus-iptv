package lk.manus.iptv.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.Locale;

/** Shared palette + tiny view helpers. */
public final class Ui {
    public static final int GLASS = 0xEB0B0F19, SURF = 0xFF141A2A, TXT = 0xFFF5F7FF, SEC = 0xFF9AA4BF,
            VIOLET = 0xFF7C5CFF, CYAN = 0xFF22D3EE, STAR = 0xFFFFC940, BAD = 0xFFFF7A7A;
    public static final DecelerateInterpolator DEC = new DecelerateInterpolator();
    // ---- type roles (baseline dp, UX-SPEC 5) ----
    public static final float DISPLAY = 34, TITLE = 22, BODY = 18, CAPTION = 14, MICRO = 12;
    public static final int LOW = 0, MID = 1, HIGH = 2;

    private static float density = 1.33f, s = 1f, fs = 1f;
    private static int wPx = 1280, hPx = 720, edge = 5, perf, detected = -1;

    private Ui() {}

    /** Measure the real window and read the user's display prefs (UX-SPEC 2, 3, 7). Call from every Activity.onCreate (a density / font change recreates it). */
    public static void init(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        density = m.density;
        wPx = m.widthPixels;
        hPx = m.heightPixels;
        if (android.os.Build.VERSION.SDK_INT >= 30 && c instanceof android.app.Activity) {
            android.graphics.Rect b = ((android.app.Activity) c).getWindowManager().getCurrentWindowMetrics().getBounds();
            if (b.width() > 0 && b.height() > 0) { wPx = b.width(); hPx = b.height(); }
        }
        s = Math.max(0.85f, Math.min(1.6f, Math.min(wPx / density / 960f, hPx / density / 540f)));
        fs = Math.max(1f, Math.min(1.3f, c.getResources().getConfiguration().fontScale));
        android.content.SharedPreferences p = c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        edge = Math.max(0, Math.min(10, p.getInt("edge_pct", 5)));
        String pm = p.getString("perf", "auto");
        perf = pm.equals("smooth") ? 1 : pm.equals("rich") ? 2 : 0;
        if (detected < 0) detected = detect(c);
    }

    /** Settings > Display > Screen edges (0-10 %). */
    public static void setEdge(int pct) { edge = Math.max(0, Math.min(10, pct)); }
    public static int edge() { return edge; }
    public static void setPerf(String mode) { perf = mode.equals("smooth") ? 1 : mode.equals("rich") ? 2 : 0; }

    /** px for a baseline dp size: scaled by the canvas factor s. */
    public static int u(float dp) { return Math.round(dp * s * density); }
    /** Text size in px for a type role: canvas scale x user font scale (max 1.3), never below 12 real dp. */
    public static float px(float role) { return Math.max(role * s * density * fs, MICRO * density); }
    public static void size(TextView t, float role) { t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(role)); }

    public static int screenW() { return wPx; }
    public static int screenH() { return hPx; }
    /** Title-safe margins: the user's edge % of the width / height. Video and scrims stay full-bleed. */
    public static int marginX() { return Math.round(wPx * edge / 100f); }
    public static int marginY() { return Math.round(hPx * edge / 100f); }
    public static int safeW() { return wPx - 2 * marginX(); }
    public static int safeH() { return hPx - 2 * marginY(); }
    /** Every modal: at most 50 % of the safe width. */
    public static int modalW() { return Math.min(u(430), safeW() / 2); }

    /** Device tier (UX-SPEC 7) after the Settings override: Auto / Smooth (= Low) / Rich (= High). */
    public static int tier() { return perf == 1 ? LOW : perf == 2 ? HIGH : detected; }
    public static boolean low() { return tier() == LOW; }
    /** Scale / fade animations (off on the Low tier). */
    public static boolean anim() { return tier() != LOW; }
    public static String tierName() { return tier() == LOW ? "Low" : tier() == HIGH ? "High" : "Mid"; }

    private static int detect(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        boolean is64 = android.os.Build.SUPPORTED_64_BIT_ABIS.length > 0;
        int cores = Runtime.getRuntime().availableProcessors();
        int t = mi.totalMem <= 1_650_000_000L || am.isLowRamDevice() || (!is64 && cores <= 4) ? LOW : mi.totalMem >= 2_700_000_000L && is64 ? HIGH : MID;
        android.util.Log.i("IPTV", "tier " + (t == LOW ? "Low" : t == HIGH ? "High" : "Mid") + ": ram=" + mi.totalMem / 1048576 + " MB 64bit=" + is64 + " cores=" + cores
                + " canvas=" + wPx + "x" + hPx + " " + Math.round(wPx / density) + "x" + Math.round(hPx / density) + "dp scale=" + s);
        return t;
    }

    public static GradientDrawable rect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(u(radiusDp));
        return g;
    }

    /** Violet-to-cyan gradient border, 2dp thick, around a filled surface. */
    public static Drawable ring(int fill) {
        GradientDrawable o = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{VIOLET, CYAN});
        o.setCornerRadius(u(14));
        LayerDrawable l = new LayerDrawable(new Drawable[]{o, rect(fill, 12)});
        l.setLayerInset(1, u(2), u(2), u(2), u(2));
        return l;
    }

    /** role = one of the type roles above (baseline dp). */
    public static TextView tv(Context c, float role, int color, boolean bold) {
        TextView t = new TextView(c);
        size(t, role);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static final java.util.HashMap<String, String> FLAGS = new java.util.HashMap<>(); // UI thread only

    /** "lk" -> flag emoji; "uk" -> GB; "" -> "". Cached: rows call this on every bind. */
    public static String flag(String code) {
        String f = FLAGS.get(code);
        if (f == null) { f = makeFlag(code); FLAGS.put(code, f); }
        return f;
    }

    private static String makeFlag(String code) {
        if (code == null || code.length() != 2) return "";
        String c = code.equalsIgnoreCase("uk") ? "GB" : code.toUpperCase(Locale.ROOT);
        char a = c.charAt(0), b = c.charAt(1);
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "";
        return new String(Character.toChars(0x1F1E6 + a - 'A')) + new String(Character.toChars(0x1F1E6 + b - 'A'));
    }

    private static final java.util.HashMap<String, String> NAMES = new java.util.HashMap<>(); // UI thread only
    static { NAMES.put("latam", "Latin America"); FLAGS.put("latam", "\uD83C\uDF0E"); } // pseudo country: Latin-American feeds with no single country (T35)

    public static String country(String code) {
        String n = NAMES.get(code);
        if (n == null) {
            n = code.equalsIgnoreCase("uk") ? "United Kingdom" : new Locale("", code).getDisplayCountry(Locale.ENGLISH);
            if (n.isEmpty()) n = code.toUpperCase(Locale.ROOT);
            NAMES.put(code, n);
        }
        return n;
    }
}
