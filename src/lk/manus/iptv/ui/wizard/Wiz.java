package lk.manus.iptv.ui.wizard;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import lk.manus.iptv.ui.Ui;

/** Wizard UI kit: UX-SPEC scaling (baseline 960x540dp, s = clamp(min(w/960, h/540), 0.85, 1.6), font scale up to 1.3) and the few widgets the steps share. */
final class Wiz {
    static final int FILL = 0xFF141A2A, ON = 0xFF2A2F66;
    int wPx, hPx;

    Wiz(Context c) { update(c); }

    /** Re-read the canvas (the scale, safe area and font rules live in {@link Ui}). */
    void update(Context c) {
        Ui.init(c);
        wPx = Ui.screenW();
        hPx = Ui.screenH();
    }

    int u(float dp) { return Ui.u(dp); }

    TextView tv(Context c, CharSequence t, float dp, int color, boolean bold) {
        TextView v = Ui.tv(c, dp, color, bold);
        v.setSingleLine(false);
        v.setEllipsize(null);
        v.setText(t);
        return v;
    }

    private GradientDrawable rect(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(u(14));
        return g;
    }

    Drawable ring(int fill) {
        GradientDrawable o = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.VIOLET, Ui.CYAN});
        o.setCornerRadius(u(14));
        LayerDrawable l = new LayerDrawable(new Drawable[]{o, rect(fill)});
        l.setLayerInset(1, u(2), u(2), u(2), u(2));
        return l;
    }

    /** A focusable tile: title (+ optional caption). setOn() shows a tick and a tinted fill, so state never relies on colour alone. */
    final class Tile extends LinearLayout {
        final TextView title, cap;
        private final String label;
        private boolean on;

        Tile(Context c, String label, String caption, boolean center) {
            super(c);
            this.label = label;
            setOrientation(VERTICAL);
            setFocusable(true);
            setClickable(true);
            setGravity(center ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
            setPadding(u(12), u(8), u(12), u(8));
            setMinimumHeight(u(50));
            title = tv(c, label, 18, Ui.TXT, true);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(title, new LayoutParams(center ? -2 : -1, -2));
            cap = tv(c, caption == null ? "" : caption, 14, Ui.SEC, false);
            cap.setSingleLine(true);
            cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (caption != null) addView(cap, new LayoutParams(center ? -2 : -1, -2));
            paint();
        }

        void setOn(boolean v) { on = v; paint(); }
        boolean isOn() { return on; }

        private void paint() {
            title.setText(on ? "✓ " + label : label);
            StateListDrawable d = new StateListDrawable();
            d.addState(new int[]{android.R.attr.state_focused}, ring(on ? ON : FILL));
            d.addState(new int[]{}, rect(on ? ON : FILL));
            setBackground(d);
        }
    }

    Tile button(Context c, String text, boolean primary) {
        Tile t = new Tile(c, text, null, true);
        t.setMinimumWidth(u(150));
        if (primary) { t.setOn(false); t.title.setTextColor(Ui.CYAN); }
        return t;
    }
}
