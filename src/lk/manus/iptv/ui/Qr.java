package lk.manus.iptv.ui;

import android.graphics.Bitmap;
import android.graphics.Color;

import io.nayuki.qrcodegen.QrCode;

/** Renders text as a QR code bitmap (dark modules on white, 2-module quiet zone, whole-pixel scale). */
public final class Qr {
    private Qr() {}

    public static Bitmap bitmap(String text, int maxPx) {
        QrCode q = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int quiet = 2, n = q.size + quiet * 2, scale = Math.max(1, maxPx / n);
        Bitmap b = Bitmap.createBitmap(n * scale, n * scale, Bitmap.Config.ARGB_8888);
        int[] row = new int[n * scale];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean dark = q.getModule(x - quiet, y - quiet);
                for (int s = 0; s < scale; s++) row[x * scale + s] = dark ? 0xFF0B0F19 : Color.WHITE;
            }
            for (int s = 0; s < scale; s++) b.setPixels(row, 0, n * scale, 0, y * scale + s, n * scale, 1);
        }
        return b;
    }
}
