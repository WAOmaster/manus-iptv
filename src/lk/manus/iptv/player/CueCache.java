package lk.manus.iptv.player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small LRU of translated cues, keyed by source language + original text (live captions repeat a lot). UI thread only. */
public final class CueCache {
    private final LinkedHashMap<String, String> m;

    public CueCache(final int max) {
        m = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > max; }
        };
    }

    public String get(String lang, String text) { return m.get(lang + '\u0001' + text); }
    public void put(String lang, String text, String out) { m.put(lang + '\u0001' + text, out); }
    public int size() { return m.size(); }
}
