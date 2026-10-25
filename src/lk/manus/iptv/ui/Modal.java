package lk.manus.iptv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/** The modal layer (UX-SPEC 4): dims what is behind (60%), centres its box in the safe area, at most 50% of the safe width. It swallows clicks; the owner routes keys. */
public class Modal extends FrameLayout {
    public Modal(Context c) {
        super(c);
        setBackgroundColor(0x99000000);
        setClickable(true);
        setVisibility(GONE);
    }

    /** Adds a box; height = 0 wraps the content, otherwise at most this many px (and 80% of the safe height). */
    public void add(View box, int maxH) {
        LayoutParams lp = new LayoutParams(Ui.modalW(), maxH <= 0 ? -2 : Math.min(maxH, Ui.safeH() * 8 / 10), Gravity.CENTER);
        addView(box, lp);
    }

    /** Screen edges changed: boxes follow the new safe width. */
    public void relayout() {
        for (int i = 0; i < getChildCount(); i++) {
            LayoutParams lp = (LayoutParams) getChildAt(i).getLayoutParams();
            lp.width = Ui.modalW();
            getChildAt(i).setLayoutParams(lp);
        }
    }

    /** Shows one box (the others hide) and the dim. */
    public void show(View box) {
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setVisibility(getChildAt(i) == box ? VISIBLE : GONE);
        setVisibility(VISIBLE);
    }

    /** Hides one box; the dim goes with the last one. */
    public void hide(View box) {
        box.setVisibility(GONE);
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() == VISIBLE) return;
        setVisibility(GONE);
    }
}
