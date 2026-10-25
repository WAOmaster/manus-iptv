package lk.manus.iptv.data;

import java.util.Set;

/** The live signals {@link Ranker} sorts by: favourites, on-device watch counts, stream health, pinned countries. */
public final class Rank implements Ranker.Signals {
    private final Store st;
    private final Health hl;

    public Rank(Store st, Health hl) { this.st = st; this.hl = hl; }

    @Override public boolean isFavourite(String url) { return st.favSet().contains(url); }
    @Override public int playCount(String url) { return st.playCount(url); }
    @Override public long lastPlayedMs(String url) { return st.lastPlayedMs(url); }
    @Override public int health(String url) { return hl.status(url); }
    @Override public int popularity(String url) { return CloudData.popularity(url); }
    @Override public Set<String> pinnedCountries() { return st.fyCountries(); }
}
