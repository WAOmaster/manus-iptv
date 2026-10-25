package lk.manus.iptv.player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebVTT cue parser for HLS subtitle segments. Pure Java 11. Never throws. */
public final class WebVtt {
    private WebVtt() {}

    /** One subtitle cue. Times are player-clock microseconds (X-TIMESTAMP-MAP already applied). */
    public static final class Cue {
        public final long startUs, endUs;
        public final String text;
        Cue(long s, long e, String t) { startUs = s; endUs = e; text = t; }
    }

    private static final Pattern NEWLINE = Pattern.compile("\r\n|\r|\n");
    private static final String TS_RAW = "(?:\\d{1,9}:)?[0-5]\\d:[0-5]\\d[.,]\\d{3}";
    private static final Pattern TS_ONE = Pattern.compile("(?:(\\d{1,9}):)?([0-5]\\d):([0-5]\\d)[.,](\\d{3})");
    private static final Pattern CUE_LINE = Pattern.compile("(" + TS_RAW + ")\\s+-->\\s+(" + TS_RAW + ")(?:\\s.*)?");
    private static final Pattern MPEGTS = Pattern.compile("MPEGTS:(\\d{1,15})");
    private static final Pattern LOCAL = Pattern.compile("LOCAL:(" + TS_RAW + ")");

    /** Parse one WebVTT document or segment. Cue times include the X-TIMESTAMP-MAP offset. */
    public static List<Cue> parse(byte[] data) {
        List<Cue> out = new ArrayList<>();
        try {
            String[] lines = lines(data);
            long offset = offsetUs(lines);
            int n = lines.length, i = 0;
            while (i < n) {
                if (lines[i].isBlank()) { i++; continue; }
                int s = i;
                while (i < n && !lines[i].isBlank()) i++;
                cue(lines, s, i, offset, out);
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Value to add to cue times, from X-TIMESTAMP-MAP in the header; 0 if absent or malformed. */
    public static long timestampOffsetUs(byte[] data) {
        try {
            return offsetUs(lines(data));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String[] lines(byte[] data) {
        if (data == null) return new String[0];
        String s = new String(data, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return NEWLINE.split(s, -1);
    }

    /** The map sits in the header block, which ends at the first blank line. */
    private static long offsetUs(String[] lines) {
        for (String line : lines) {
            if (line.isBlank()) break;
            if (!line.startsWith("X-TIMESTAMP-MAP=")) continue;
            Matcher mp = MPEGTS.matcher(line), ml = LOCAL.matcher(line);
            if (!mp.find() || !ml.find()) return 0;
            long local = parseTs(ml.group(1));
            if (local < 0) return 0;
            return Long.parseLong(mp.group(1)) * 100 / 9 - local; // 90 kHz ticks -> microseconds
        }
        return 0;
    }

    /** Handles one blank-line-separated block [s, e): skips NOTE/STYLE/REGION, reads an optional identifier and the timing line. */
    private static void cue(String[] lines, int s, int e, long offset, List<Cue> out) {
        if (startsWithWord(lines[s], "WEBVTT")) s++;
        if (s >= e || startsWithWord(lines[s], "NOTE") || startsWithWord(lines[s], "STYLE") || startsWithWord(lines[s], "REGION")) return;
        int t = -1;
        for (int k = s; k < e && k <= s + 1; k++) {
            if (lines[k].contains("-->")) { t = k; break; }
        }
        if (t < 0) return;
        Matcher m = CUE_LINE.matcher(lines[t].strip());
        if (!m.matches()) return;
        long start = parseTs(m.group(1)), end = parseTs(m.group(2));
        StringBuilder sb = new StringBuilder();
        for (int k = t + 1; k < e; k++) {
            if (k > t + 1) sb.append('\n');
            sb.append(cleanLine(lines[k]));
        }
        out.add(new Cue(start + offset, end + offset, sb.toString()));
    }

    /** Milliseconds-precision timestamp to microseconds, or -1 when not a valid timestamp. */
    private static long parseTs(String s) {
        Matcher m = TS_ONE.matcher(s);
        if (!m.matches()) return -1;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        return ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000000L
            + Long.parseLong(m.group(4)) * 1000L;
    }

    private static boolean startsWithWord(String line, String word) {
        return line.startsWith(word) && (line.length() == word.length() || Character.isWhitespace(line.charAt(word.length())));
    }

    /** Strip tags first, then decode entities, so an encoded "&lt;b&gt;" survives as text. */
    private static String cleanLine(String raw) {
        String s = stripTags(raw).strip();
        if (s.indexOf('&') < 0) return s;
        return s.replace("&nbsp;", "\u00A0").replace("&lrm;", "\u200E").replace("&rlm;", "\u200F")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String stripTags(String s) {
        if (s.indexOf('<') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '<' && i + 1 < n && (Character.isLetterOrDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '/')) {
                int close = s.indexOf('>', i + 1);
                if (close < 0) { sb.append(s, i, n); break; } // no closing '>': treat the '<' as text
                i = close + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
