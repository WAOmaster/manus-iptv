package lk.manus.iptv.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Placeholder logo: rounded coloured square with the channel's initials. */
public final class LetterTile extends Drawable {
    private static final int[] COLOURS = {
            0xFF3B2F80, 0xFF0E6E6E, 0xFF8A2D5C, 0xFF1F5FAF, 0xFF7A4B12,
            0xFF2E6B2E, 0xFF6A1B9A, 0xFF00695C, 0xFFAD1457, 0xFF283593
    };

    // Shared paints + cached initials: a list binds dozens of these per scroll (UI thread only)
    private static final Paint BG = new Paint(Paint.ANTI_ALIAS_FLAG), TEXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final java.util.HashMap<String, String> INITIALS = new java.util.HashMap<>();
    private static final RectF RECT = new RectF();
    static {
        TEXT.setColor(0xFFFFFFFF);
        TEXT.setTypeface(Typeface.DEFAULT_BOLD);
        TEXT.setTextAlign(Paint.Align.CENTER);
    }

    private final int colour;
    private final String initials;
    private int alpha = 255, sizeFor;   // sizeFor = bounds height the text size was computed for
    private float textSize;

    public LetterTile(String name) {
        String n = name == null ? "" : name;
        colour = COLOURS[Math.floorMod(n.hashCode(), COLOURS.length)];
        String in = INITIALS.get(n);
        if (in == null) { if (INITIALS.size() > 3000) INITIALS.clear(); in = initials(n); INITIALS.put(n, in); }
        initials = in;
    }

    /** First letters of the first two words that contain a letter; "?" if none. */
    private static String initials(String name) {
        StringBuilder sb = new StringBuilder(2);
        for (String word : name.trim().split("\\s+")) {
            if (sb.length() >= 2) break;
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (Character.isLetter(c)) {
                    sb.append(Character.toUpperCase(c));
                    break;
                }
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        if (w <= 0 || h <= 0) return;
        float r = 0.18f * Math.min(w, h);
        RECT.set(b);
        BG.setColor(colour);
        BG.setAlpha(alpha);
        canvas.drawRoundRect(RECT, r, r, BG);

        if (sizeFor != b.width() * 7919 + b.height()) {
            sizeFor = b.width() * 7919 + b.height();
            textSize = 0.42f * h;
            TEXT.setTextSize(textSize);
            float tw = TEXT.measureText(initials);
            if (tw > 0.9f * w) textSize = textSize * 0.9f * w / tw; // shrink long initials to fit
        }
        TEXT.setTextSize(textSize);
        TEXT.setAlpha(alpha);
        Paint.FontMetrics fm = TEXT.getFontMetrics();
        float y = b.exactCenterY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(initials, b.exactCenterX(), y, TEXT);
    }

    @Override
    public void setAlpha(int a) { alpha = a; invalidateSelf(); }

    @Override
    public void setColorFilter(ColorFilter colorFilter) { }

    @Override
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
