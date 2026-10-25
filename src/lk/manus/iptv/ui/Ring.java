package lk.manus.iptv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Circular progress: a dim track with a cyan arc that fills clockwise. */
public final class Ring extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float p;

    public Ring(Context c) {
        super(c);
        float w = Ui.u(6);
        track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(w); track.setColor(0x33FFFFFF);
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(w); arc.setColor(Ui.CYAN); arc.setStrokeCap(Paint.Cap.ROUND);
    }

    public void set(float progress) { p = Math.max(0f, Math.min(1f, progress)); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = arc.getStrokeWidth() / 2;
        box.set(w, w, getWidth() - w, getHeight() - w);
        c.drawOval(box, track);
        c.drawArc(box, -90, 360 * p, false, arc);
    }
}
