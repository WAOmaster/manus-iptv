package lk.manus.iptv.data;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T33: the safety net under the favourites. Before every change a snapshot (newest 10) of the whole list is kept, and every add / remove goes
 * into a small history file (newest 200). Both are plain text files in the app's private folder (never logcat); a missing folder = no-op (unit tests).
 * Snapshot line: time TAB url US name RS url US name ... ; history line: time TAB +|- TAB name.
 */
public final class FavGuard {
    public static final int MAX_SNAPS = 10, MAX_HIST = 200;
    private static final char US = '\u001f', RS = '\u001e';
    private static File dir;

    private FavGuard() {}

    public static final class Snap {
        public final long time;
        public final List<String> urls = new ArrayList<>(), names = new ArrayList<>();
        Snap(long t) { time = t; }
    }

    public static final class Event {
        public final long time;
        public final boolean added;
        public final String name;
        Event(long t, boolean a, String n) { time = t; added = a; name = n; }
    }

    public static void init(File filesDir) { dir = filesDir; }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\n\\r\u001e\u001f]", " "); }

    private static synchronized List<String> read(String f) {
        List<String> l = new ArrayList<>();
        if (dir == null) return l;
        try {
            File x = new File(dir, f);
            if (x.exists()) for (String line : Files.readAllLines(x.toPath(), StandardCharsets.UTF_8)) if (!line.isEmpty()) l.add(line);
        } catch (Exception ignored) { }
        return l;
    }

    private static synchronized void write(String f, List<String> l) {
        if (dir == null) return;
        try {
            File x = new File(dir, f + ".tmp");
            Files.write(x.toPath(), (String.join("\n", l) + "\n").getBytes(StandardCharsets.UTF_8));
            if (!x.renameTo(new File(dir, f))) Files.copy(x.toPath(), new File(dir, f).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { }
    }

    /** Newest first. */
    public static List<Snap> snaps() {
        List<Snap> out = new ArrayList<>();
        List<String> l = read("fav-snaps.txt");
        for (int i = l.size() - 1; i >= 0; i--) {
            String[] f = l.get(i).split("\t", 2);
            try {
                Snap s = new Snap(Long.parseLong(f[0]));
                if (f.length > 1 && !f[1].isEmpty()) for (String e : f[1].split(String.valueOf(RS))) {
                    int u = e.indexOf(US);
                    s.urls.add(u < 0 ? e : e.substring(0, u));
                    s.names.add(u < 0 ? "" : e.substring(u + 1));
                }
                out.add(s);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Keeps the list as it is now, before a change. Skipped when identical to the newest snapshot. */
    public static void snapshot(List<String> urls, Map<String, String> names) {
        if (dir == null) return;
        List<String> l = read("fav-snaps.txt");
        StringBuilder b = new StringBuilder().append(System.currentTimeMillis()).append('\t');
        for (int i = 0; i < urls.size(); i++) b.append(i == 0 ? "" : String.valueOf(RS)).append(clean(urls.get(i))).append(US).append(clean(names.get(urls.get(i))));
        String body = b.toString().substring(b.indexOf("\t"));
        if (!l.isEmpty() && l.get(l.size() - 1).substring(l.get(l.size() - 1).indexOf('\t')).equals(body)) return;
        l.add(b.toString());
        while (l.size() > MAX_SNAPS) l.remove(0);
        write("fav-snaps.txt", l);
    }

    /** Newest first. */
    public static List<Event> history() {
        List<Event> out = new ArrayList<>();
        List<String> l = read("fav-history.txt");
        for (int i = l.size() - 1; i >= 0; i--) {
            String[] f = l.get(i).split("\t", 3);
            if (f.length < 3) continue;
            try { out.add(new Event(Long.parseLong(f[0]), f[1].equals("+"), f[2])); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static void log(boolean added, String name) {
        if (dir == null) return;
        List<String> l = read("fav-history.txt");
        l.add(System.currentTimeMillis() + "\t" + (added ? "+" : "-") + "\t" + clean(name));
        while (l.size() > MAX_HIST) l.remove(0);
        write("fav-history.txt", l);
    }

    /** Remember the names of channels so snapshots can show them: url -> name, kept in the snapshot-independent names file. */
    public static Map<String, String> names() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : read("fav-names.txt")) { int t = line.indexOf('\t'); if (t > 0) m.put(line.substring(0, t), line.substring(t + 1)); }
        return m;
    }

    public static void rememberNames(Map<String, String> add) {
        if (dir == null || add.isEmpty()) return;
        Map<String, String> m = names();
        if (m.entrySet().containsAll(add.entrySet())) return;
        m.putAll(add);
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) l.add(clean(e.getKey()) + "\t" + clean(e.getValue()));
        while (l.size() > 400) l.remove(0);
        write("fav-names.txt", l);
    }
}
