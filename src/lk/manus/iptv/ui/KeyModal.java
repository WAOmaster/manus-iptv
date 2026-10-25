package lk.manus.iptv.ui;

import android.content.Context;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Centred modal (60% dim, max 50% of the safe width) for the two remote pages: "Remote keys" (what each button does) and
 * "Remote test" (shows every key pressed). The activity routes every key here while it is up ({@link #key}).
 */
public final class KeyModal extends Modal {
    private final LinearLayout box, body;
    private final TextView title, hint, big, log;
    private final ScrollView scroll;
    private final List<String> lines = new ArrayList<>();
    private boolean test, backHeld;

    public KeyModal(Context c) {
        super(c);
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.rect(0xFF141A2A, 18));
        box.setPadding(Ui.u(28), Ui.u(22), Ui.u(28), Ui.u(20));
        title = Ui.tv(c, Ui.TITLE, Ui.TXT, true);
        box.addView(title);
        hint = Ui.tv(c, Ui.CAPTION, Ui.SEC, false);
        hint.setSingleLine(false);
        hint.setPadding(0, Ui.u(6), 0, Ui.u(10));
        box.addView(hint);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        big = Ui.tv(c, Ui.TITLE, Ui.CYAN, true);
        big.setSingleLine(false);
        log = Ui.tv(c, Ui.CAPTION, Ui.TXT, false);
        log.setSingleLine(false);
        log.setPadding(0, Ui.u(10), 0, 0);
        add(box, Ui.u(480));
    }

    public boolean isUp() { return getVisibility() == VISIBLE; }

    public void showHelp() {
        test = false;
        title.setText("Remote keys");
        hint.setText("Up / Down to scroll. Back closes.");
        body.removeAllViews();
        for (Object[] k : Keys.HELP) {
            TextView l = Ui.tv(getContext(), Ui.BODY, Ui.TXT, true);
            int dot = (Integer) k[2];
            if (dot != 0) {
                SpannableString sp = new SpannableString("● " + k[0]);
                sp.setSpan(new ForegroundColorSpan(dot), 0, 1, 0);
                l.setText(sp);
            } else l.setText((String) k[0]);
            TextView d = Ui.tv(getContext(), Ui.CAPTION, Ui.SEC, false);
            d.setSingleLine(false);
            d.setText((String) k[1]);
            d.setPadding(0, Ui.u(1), 0, Ui.u(10));
            body.addView(l);
            body.addView(d);
        }
        scroll.scrollTo(0, 0);
        setVisibility(VISIBLE);
    }

    public void showTest() {
        test = true;
        backHeld = false;
        lines.clear();
        title.setText("Remote test");
        hint.setText("Press any button. Back leaves; to test Back itself, hold it for a second: it is listed and you stay.");
        body.removeAllViews();
        big.setText("Waiting for a key…");
        log.setText("");
        body.addView(big);
        body.addView(log);
        setVisibility(VISIBLE);
    }

    private void close() { setVisibility(GONE); body.removeAllViews(); }

    /** Every key while the modal is up. Returns false only for volume keys, so the system keeps them. */
    public boolean key(KeyEvent e) {
        int k = e.getKeyCode(), a = e.getAction();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (test && a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) show(e, null);
            return false;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (a == KeyEvent.ACTION_DOWN && test && e.getRepeatCount() >= 1 && !backHeld) { backHeld = true; show(e, "BACK (held)"); }
            else if (a == KeyEvent.ACTION_UP) {
                boolean held = backHeld;
                backHeld = false;
                if (!held) close();
            }
            return true;
        }
        if (test) { if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) show(e, null); return true; }
        if (a == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) scroll.smoothScrollBy(0, Ui.u(120));
            else if (k == KeyEvent.KEYCODE_DPAD_UP) scroll.smoothScrollBy(0, -Ui.u(120));
        }
        return true;
    }

    private void show(KeyEvent e, String nameOverride) {
        int k = e.getKeyCode();
        String name = nameOverride != null ? nameOverride : KeyEvent.keyCodeToString(k).replace("KEYCODE_", "");
        String does = k == KeyEvent.KEYCODE_BACK ? "Back is working" : Keys.describe(k);
        android.util.Log.i("IPTV-key", "test " + KeyEvent.keyCodeToString(k) + " scan=" + e.getScanCode() + " -> " + (does == null ? "not used" : does));
        lines.add(0, name + "   code " + k + "  scan " + e.getScanCode() + "   " + (does == null ? "not used by the app" : does));
        while (lines.size() > 7) lines.remove(lines.size() - 1);
        big.setText(name + "  (code " + k + ", scan " + e.getScanCode() + ")");
        StringBuilder b = new StringBuilder(does == null ? "Not used by the app" : does);
        for (int i = 1; i < lines.size(); i++) b.append('\n').append(lines.get(i));
        log.setText(b);
    }
}
