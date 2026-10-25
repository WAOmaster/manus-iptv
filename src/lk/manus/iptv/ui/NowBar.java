package lk.manus.iptv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Thin programme progress bar: dim track with a cyan fill. */
public final class NowBar extends View {
    private final Paint track = new Paint(), fill = new Paint();
    private float p;

    public NowBar(Context c) {
        super(c);
        track.setColor(0x33FFFFFF);
        fill.setColor(Ui.CYAN);
    }

    public void set(float progress) { p = progress; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), track);
        c.drawRect(0, 0, getWidth() * p, getHeight(), fill);
    }
}
