package lk.manus.iptv.data;

/** Source and stream links can embed logins, so logs only ever carry the host. */
public final class Redact {
    private Redact() {}

    public static String host(String url) {
        if (url == null) return "?";
        try {
            String h = new java.net.URI(url.trim()).getHost();
            return h == null ? "?" : h;
        } catch (Exception e) {
            return "?";
        }
    }
}
