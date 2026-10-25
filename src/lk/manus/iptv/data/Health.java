package lk.manus.iptv.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Per-stream liveness memory ("health" prefs, one compact line per probed url). Rules live in {@link Rule} so they test without Android. */
public final class Health {
    public static final int DEAD = -1, UNKNOWN = 0, LIKELY = 1 /* cloud says alive, never seen here */, ALIVE = 2;
    static final long DAY = 86400000L, HOUR = 3600000L;
    private static final String KEY = "d";
    private static final long KEEP_MS = 60 * DAY;

    /** Pure rules for one url; every method takes the clock. */
    public static final class Rule {
        long okAt, checkedAt;          // ms; 0 = never
        int fails, failDays, dayFails; // since the last success
        long lastFailDay = -1;

        public void record(boolean ok, long now) {
            checkedAt = now;
            if (ok) { okAt = now; fails = 0; failDays = 0; dayFails = 0; lastFailDay = -1; return; }
            fails++;
            long day = now / DAY;
            if (day != lastFailDay) { lastFailDay = day; failDays++; dayFails = 1; } else dayFails++;
        }

        public int status(long now) {
            if ((fails >= 2 && failDays >= 2) || dayFails >= 3) return DEAD;
            return okAt > 0 && now - okAt <= 7 * DAY ? ALIVE : UNKNOWN;
        }

        /** UNKNOWN waits an hour between tries so a flaky network cannot burn the three-strikes rule in minutes. */
        public boolean needsProbe(long now) {
            switch (status(now)) {
                case ALIVE: return now - checkedAt > 3 * DAY;
                case DEAD: return now - checkedAt > 7 * DAY;
                default: return checkedAt == 0 || now - checkedAt > HOUR;
            }
        }

        String encode(String url) {
            return url + '\t' + Long.toString(okAt / 1000, 36) + '\t' + Long.toString(checkedAt / 1000, 36) + '\t' + fails + '\t'
                    + failDays + '\t' + dayFails + '\t' + Long.toString(lastFailDay + 1, 36);
        }

        /** Returns the url, filling this; null if the line is malformed. */
        static String decode(String line, Rule r) {
            String[] f = line.split("\t");
            if (f.length < 7) return null;
            try {
                r.okAt = Long.parseLong(f[1], 36) * 1000;
                r.checkedAt = Long.parseLong(f[2], 36) * 1000;
                r.fails = Integer.parseInt(f[3]);
                r.failDays = Integer.parseInt(f[4]);
                r.dayFails = Integer.parseInt(f[5]);
                r.lastFailDay = Long.parseLong(f[6], 36) - 1;
                return f[0];
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static Health inst;
    private final SharedPreferences prefs;
    private final Map<String, Rule> map = new HashMap<>();
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "health-io");
        t.setDaemon(true);
        return t;
    });
    private boolean flushPending;

    public static synchronized Health get(Context c) {
        if (inst == null) inst = new Health(c.getApplicationContext().getSharedPreferences("health", Context.MODE_PRIVATE));
        return inst;
    }

    private Health(SharedPreferences p) {
        prefs = p;
        String s = p.getString(KEY, "");
        long now = System.currentTimeMillis();
        if (!s.isEmpty()) for (String line : s.split("\n")) {
            Rule r = new Rule();
            String url = Rule.decode(line, r);
            if (url != null && now - r.checkedAt < KEEP_MS) map.put(url, r);
        }
    }

    public synchronized void record(String url, boolean ok) {
        if (url == null || url.isEmpty()) return;
        Rule r = map.get(url);
        if (r == null) map.put(url, r = new Rule());
        r.record(ok, System.currentTimeMillis());
    }

    /** TV-observed status with the cloud hint folded in (see {@link #merge}). */
    public synchronized int status(String url) {
        Rule r = map.get(url);
        return merge(r == null ? UNKNOWN : r.status(System.currentTimeMillis()), CloudData.status(url));
    }

    /**
     * Cloud status is a hint only: TV-observed ALIVE/DEAD always wins. For an unseen stream 'd' = DEAD, 'a' = LIKELY;
     * 'g' / 't' are ignored (the runner is in the US, the TV in Sri Lanka).
     */
    public static int merge(int tv, char cloud) {
        if (tv != UNKNOWN) return tv;
        return cloud == 'd' ? DEAD : cloud == 'a' ? LIKELY : UNKNOWN;
    }

    /** T21: forgets every TV-side DEAD verdict (ALIVE ones stay); they are probed again soon. Returns how many were reset. */
    public synchronized int resetDead() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Rule r : map.values()) {
            if (r.status(now) != DEAD) continue;
            r.okAt = 0; r.checkedAt = 0; r.fails = 0; r.failDays = 0; r.dayFails = 0; r.lastFailDay = -1;
            n++;
        }
        if (n > 0) flushAsync();
        return n;
    }

    public synchronized boolean needsProbe(String url) {
        Rule r = map.get(url);
        return r == null || r.needsProbe(System.currentTimeMillis());
    }

    /** Debounced (3 s) write on a background thread. */
    public synchronized void flushAsync() {
        if (flushPending) return;
        flushPending = true;
        io.schedule(this::flush, 3, TimeUnit.SECONDS);
    }

    private void flush() {
        StringBuilder b = new StringBuilder();
        synchronized (this) {
            flushPending = false;
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<String, Rule>> it = map.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Rule> e = it.next();
                if (now - e.getValue().checkedAt >= KEEP_MS) { it.remove(); continue; }
                if (b.length() > 0) b.append('\n');
                b.append(e.getValue().encode(e.getKey()));
            }
        }
        prefs.edit().putString(KEY, b.toString()).apply();
    }
}
