package lk.manus.iptv.data;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Favourites (ordered) and recents (max 15, newest first) as newline-joined URL lists in SharedPreferences. */
public final class Store {
    private static final int MAX_RECENT = 15;
    private final SharedPreferences p;

    public Store(SharedPreferences p) { this.p = p; }

    private List<String> get(String k) {
        List<String> l = new ArrayList<>();
        String s = p.getString(k, "");
        if (!s.isEmpty()) for (String u : s.split("\n")) if (!u.isEmpty()) l.add(u);
        return l;
    }

    private void put(String k, List<String> l) {
        if (k.equals("favs")) favCache = null;
        p.edit().putString(k, String.join("\n", l)).apply();
    }

    private Set<String> favCache;

    public List<String> favs() { return get("favs"); }
    public List<String> recents() { return get("recents"); }
    /** Cached set (rebuilt after a change): safe to call per channel. */
    public synchronized Set<String> favSet() {
        if (favCache == null) favCache = new HashSet<>(favs());
        return favCache;
    }
    public boolean isFav(String url) { return favSet().contains(url); }

    // ---- on-device watch counts: "plays" = url TAB count TAB last-played seconds (base 36), newest 300 kept ----

    private static final int MAX_PLAYS = 300;
    private Map<String, long[]> plays;

    private synchronized Map<String, long[]> plays() {
        if (plays != null) return plays;
        plays = new HashMap<>();
        for (String line : get("plays")) {
            String[] f = line.split("\t");
            if (f.length < 3) continue;
            try { plays.put(f[0], new long[]{Long.parseLong(f[1]), Long.parseLong(f[2], 36) * 1000}); } catch (NumberFormatException ignored) { }
        }
        return plays;
    }

    public synchronized void recordPlay(String url) {
        Map<String, long[]> m = plays();
        long[] e = m.get(url);
        if (e == null) m.put(url, e = new long[2]);
        e[0]++;
        e[1] = System.currentTimeMillis();
        while (m.size() > MAX_PLAYS) { // drop the least recently watched
            String old = null;
            for (Map.Entry<String, long[]> x : m.entrySet()) if (old == null || x.getValue()[1] < m.get(old)[1]) old = x.getKey();
            m.remove(old);
        }
        List<String> l = new ArrayList<>(m.size());
        for (Map.Entry<String, long[]> x : m.entrySet()) l.add(x.getKey() + "\t" + x.getValue()[0] + "\t" + Long.toString(x.getValue()[1] / 1000, 36));
        p.edit().putString("plays", String.join("\n", l)).apply();
    }

    public synchronized int playCount(String url) { long[] e = plays().get(url); return e == null ? 0 : (int) e[0]; }
    public synchronized long lastPlayedMs(String url) { long[] e = plays().get(url); return e == null ? 0 : e[1]; }

    // ---- subtitle choice per channel url: "subs" = url TAB track key; absent = Off ----

    public String subChoice(String url) { return kv("subs", url); }
    public void setSubChoice(String url, String key) { setKv("subs", url, key); }
    /** Audio language per channel url ("" = the stream's default). */
    public String audioChoice(String url) { return kv("audio", url); }
    public void setAudioChoice(String url, String key) { setKv("audio", url, key); }

    /** Channel volume multiplier 0.3..1.0 per url, default 1.0 (clamped by Vol). */
    public float volume(String url) {
        String v = kv("vols", url);
        try { return v.isEmpty() ? 1f : lk.manus.iptv.player.Vol.clamp(Integer.parseInt(v) / 100f); } catch (NumberFormatException e) { return 1f; }
    }
    public void setVolume(String url, float v) {
        int pct = Math.round(lk.manus.iptv.player.Vol.clamp(v) * 100);
        setKv("vols", url, pct >= 100 ? "" : Integer.toString(pct));
    }

    private String kv(String k, String url) {
        for (String line : get(k)) { int t = line.indexOf('\t'); if (t > 0 && line.substring(0, t).equals(url)) return line.substring(t + 1); }
        return "";
    }

    private void setKv(String k, String url, String val) {
        List<String> l = new ArrayList<>();
        for (String line : get(k)) { int t = line.indexOf('\t'); if (t > 0 && !line.substring(0, t).equals(url)) l.add(line); }
        if (!val.isEmpty()) l.add(url + "\t" + val);
        while (l.size() > 60) l.remove(0);
        put(k, l);
    }

    /** Returns true if now a favourite. A snapshot of the list is kept first and the change goes into the history (T33). */
    public boolean toggleFav(String url, String name) {
        List<String> l = favs();
        snap(l, url, name);
        boolean add = !l.remove(url);
        if (add) l.add(url);
        put("favs", l);
        FavGuard.log(add, name);
        return add;
    }

    /** Replace the whole list (Settings > Restore favourites). The list as it was is snapshotted first, so a restore can be undone too. */
    public void setFavs(List<String> urls) {
        snap(favs(), null, null);
        put("favs", new ArrayList<>(urls));
        FavGuard.log(true, "(list restored: " + urls.size() + " favourites)");
    }

    private void snap(List<String> l, String url, String name) {
        Map<String, String> n = FavGuard.names();
        if (url != null && name != null) { Map<String, String> one = new HashMap<>(); one.put(url, name); FavGuard.rememberNames(one); n.put(url, name); }
        FavGuard.snapshot(l, n);
    }

    public void pushRecent(String url) {
        List<String> l = recents();
        l.remove(url);
        l.add(0, url);
        while (l.size() > MAX_RECENT) l.remove(l.size() - 1);
        put("recents", l);
    }

    public String section() { return p.getString("sec", "foryou"); }
    public void setSection(String id) { p.edit().putString("sec", id).apply(); }

    // ---- settings ----

    public String quality() { return p.getString("quality", "auto"); } // auto | saver | best
    /** False until the user picks a quality: a Low-tier TV then defaults to a 720p cap (UX-SPEC 7). */
    public boolean qualityChosen() { return p.contains("quality"); }
    /** Settings > Display: safe-area margin in % (0-10) and performance mode (auto | smooth | rich). Read by Ui.init. */
    public int edge() { return p.getInt("edge_pct", 5); }
    public void setEdge(int pct) { p.edit().putInt("edge_pct", pct).apply(); }
    public String perf() { return p.getString("perf", "auto"); }
    public void setPerf(String v) { p.edit().putString("perf", v).apply(); }
    public void setQuality(String q) { p.edit().putString("quality", q).apply(); }
    public boolean mirror() { return p.getBoolean("mirror_mode", false); }
    public void setMirror(boolean on) { p.edit().putBoolean("mirror_mode", on).apply(); }

    /** Settings > Playback: show channels known to be dead (greyed at the end of each list). */
    public boolean showUnavail() { return p.getBoolean("show_unavail", false); }
    public void setShowUnavail(boolean on) { p.edit().putBoolean("show_unavail", on).apply(); }

    /** Settings > Playback: the video behind the guide follows the row you rest on (off = only OK switches channel). */
    /** Mini preview in the guide card (T38): Auto (on when the TV can run two decoders) or Off. Replaces "Live preview while browsing". */
    public boolean miniAuto() { return !"off".equals(p.getString("mini_mode", "auto")); }
    public void setMiniAuto(boolean on) { p.edit().putString("mini_mode", on ? "auto" : "off").remove("mini_blocked").apply(); } // choosing Auto again retries
    /** Why Auto switched itself off on this TV ("" = never); remembered. */
    public String miniBlocked() { return p.getString("mini_blocked", ""); }
    public void setMiniBlocked(String why) { p.edit().putString("mini_blocked", why).apply(); }

    /** Corner watermark (T18): off | tl | tr | bl | br, and when it shows: change (8 s after a channel change) | always. */
    public String wmPos() { return p.getString("wm_pos", "off"); }
    public void setWmPos(String v) { p.edit().putString("wm_pos", v).apply(); }
    public String wmMode() { return p.getString("wm_mode", "change"); }
    public void setWmMode(String v) { p.edit().putString("wm_mode", v).apply(); }


    /** T27: "Skip channels that don't start" - off (default) shows a failure card instead of jumping on. */
    public boolean autoSkip() { return p.getBoolean("auto_skip", false); }
    public void setAutoSkip(boolean on) { p.edit().putBoolean("auto_skip", on).apply(); }

    /** T25: also merge the same channel name across countries/languages (default off). */
    public boolean crossLang() { return p.getBoolean("cross_lang", false); }
    public void setCrossLang(boolean on) { p.edit().putBoolean("cross_lang", on).apply(); }

    // ---- remembered version per merged group: "vers" = key TAB url (keys from Dedup.choiceKeys), newest 200 kept ----

    private Map<String, String> verCache;

    /** key -> chosen stream url; cached, rebuilt after a change. */
    public synchronized Map<String, String> versionMap() {
        if (verCache == null) {
            verCache = new HashMap<>();
            for (String line : get("vers")) { int t = line.indexOf('\t'); if (t > 0) verCache.put(line.substring(0, t), line.substring(t + 1)); }
        }
        return verCache;
    }

    public synchronized void chooseVersion(String[] keys, String url) {
        List<String> l = new ArrayList<>();
        for (String line : get("vers")) { int t = line.indexOf('\t'); if (t > 0 && !java.util.Arrays.asList(keys).contains(line.substring(0, t))) l.add(line); }
        for (String k : keys) l.add(k + "\t" + url);
        while (l.size() > 200) l.remove(0);
        verCache = null;
        put("vers", l);
    }

    /** For You picker; null-free, defaults to the original curated behaviour. */
    public Set<String> fyCountries() { return set("fy_countries", Curated.DEFAULT_COUNTRIES); }
    public Set<String> fyCats() { return set("fy_cats", Curated.DEFAULT_CATS); }
    public void setFyCountries(Set<String> s) { p.edit().putString("fy_countries", String.join("\n", s)).apply(); }
    public void setFyCats(Set<String> s) { p.edit().putString("fy_cats", String.join("\n", s)).apply(); }
    public void resetFy() { p.edit().remove("fy_countries").remove("fy_cats").apply(); }

    /** Groups hidden from the sidebar's level-2 lists: "cc:jp", "cat:News". */
    public Set<String> hidden() { return set("hidden", new ArrayList<String>()); }
    public void hide(String id) { Set<String> s = hidden(); s.add(id); p.edit().putString("hidden", String.join("\n", s)).apply(); }
    public void unhide(String id) { Set<String> s = hidden(); s.remove(id); p.edit().putString("hidden", String.join("\n", s)).apply(); }

    // ---- T28 setup wizard ----

    /** First run = no prefs at all yet. An existing install (any pref present) never sees the wizard on its own. */
    public boolean needsSetup() { return !p.contains("setup_done") && p.getAll().isEmpty(); }
    public boolean setupDone() { return p.getBoolean("setup_done", false); }
    /** Bumped each time the wizard saves choices; Settings compares it after the wizard closes to refresh For You. */
    public int setupRev() { return p.getInt("setup_rev", 0); }
    public void markSetupDone(boolean changed) {
        SharedPreferences.Editor e = p.edit().putBoolean("setup_done", true);
        if (changed) e.putInt("setup_rev", setupRev() + 1);
        e.apply();
    }
    public boolean fyCustom() { return p.contains("fy_countries") || p.contains("fy_cats"); }
    /** Languages watched (ranking boost, applied later). Empty = not asked. */
    public Set<String> langs() { return p.contains("langs") ? set("langs", new ArrayList<String>()) : null; }
    public void setLangs(Set<String> s) { p.edit().putString("langs", String.join("\n", s)).apply(); }
    /** "Even out volume" (applied by a later task). */
    public boolean evenVolume() { return p.getBoolean("even_volume", true); }
    public void setEvenVolume(boolean on) { p.edit().putBoolean("even_volume", on).apply(); }
    /** Subtitles on by default when a channel has them (applied by a later task). */
    public boolean subsDefault() { return p.getBoolean("subs_default", false); }
    public void setSubsDefault(boolean on) { p.edit().putBoolean("subs_default", on).apply(); }

    /** T40: translate non-English subtitles to English on the TV (Auto) or show them as they are (Off). */
    public boolean subTranslate() { return p.getBoolean("sub_translate", true); }
    public void setSubTranslate(boolean on) { p.edit().putBoolean("sub_translate", on).apply(); }

    private Set<String> set(String k, List<String> dflt) {
        if (!p.contains(k)) return new LinkedHashSet<>(dflt);
        return new LinkedHashSet<>(get(k));
    }
}
