package lk.manus.iptv.data;

/** One live TV channel from an M3U playlist. All fields are non-null; missing values are "" or an empty array. */
public final class Channel {
    public final String id;        // tvg-id, "" if none
    public final String name;      // display name without trailing quality / status tags
    public final String quality;   // e.g. "720p", "" if none
    public final String logo;      // "" if none
    public final String[] groups;  // group-title split on ';', never null
    public String country;         // lowercase code: tvg-country, else tvg-id suffix; the cloud feed's first broadcast country wins (Index.relabel); "" if none
    public final String url;
    public final String userAgent; // "" if none
    public final String referrer;  // "" if none
    public final String src;       // source id, "" = parsed but not yet tagged (default source = "default")
    public String info;            // UI-thread cache of the guide row subtitle
    public String dkey;            // cache of Dedup.key (the regexes are slow on a weak CPU)
    public String[] areas;         // every country list this channel appears in (cloud feed broadcast countries), null = just country
    public String lang = "";       // first language of the feed (iso 639-3, cloud data), "" = unknown
    public String bkey;            // cache of Dedup.brand (name without quality/region markers, no spaces)
    public String bgk, blk;        // caches of Dedup's brand|country and brand|language group keys (null = none)
    public boolean breg;           // the name carried a region marker for its own country ("Nick Jr. (India)", "TBN UK")
    public String ikey;            // cache of Dedup.idKey (base tvg-id without @feed, lowercase; "" = none)
    public final String[] folders; // the source's own group-title folders, as written; same as groups for iptv-org

    public Channel(String id, String name, String quality, String logo, String[] groups,
                   String country, String url, String userAgent, String referrer) {
        this(id, name, quality, logo, groups, country, url, userAgent, referrer, "", groups);
    }

    public Channel(String id, String name, String quality, String logo, String[] groups,
                   String country, String url, String userAgent, String referrer, String src, String[] folders) {
        this.src = src;
        this.folders = folders;
        this.id = id;
        this.name = name;
        this.quality = quality;
        this.logo = logo;
        this.groups = groups;
        this.country = country;
        this.url = url;
        this.userAgent = userAgent;
        this.referrer = referrer;
    }

    /** Copy tagged with a source id; folders stay as given. */
    public Channel withSrc(String s) {
        return new Channel(id, name, quality, logo, groups, country, url, userAgent, referrer, s, folders);
    }

    /** Index.relabel: country/areas/language from the cloud feed data; resets the caches that depend on them. */
    public void relabel(String[] a, String lg) {
        String l = lg == null ? "" : lg;
        boolean same = (a == null || a.length == 0 || (java.util.Arrays.equals(a, areas) && a[0].equals(country))) && l.equals(lang);
        if (same) return;
        if (a != null && a.length > 0) { areas = a; country = a[0]; dkey = null; }
        lang = l;
        bkey = bgk = blk = null;
    }
}
