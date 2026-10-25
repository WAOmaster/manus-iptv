package lk.manus.iptv.ui.wizard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.util.List;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Store;

/** Entry points for the first-run wizard. */
public final class Wizard {
    private static int seenRev = -1;

    private Wizard() {}

    /** Call first thing in PlayerActivity.onCreate (after prefs exist): starts the wizard on a brand-new install only. Returns true if it started. */
    public static boolean maybeStart(Activity a) {
        Store st = new Store(a.getSharedPreferences("iptv", Context.MODE_PRIVATE));
        if (!st.needsSetup()) return false;
        seenRev = st.setupRev();
        a.startActivity(new Intent(a, WizardActivity.class).putExtra("first", true));
        return true;
    }

    /** Settings > Run setup again. */
    public static void start(Context c) { c.startActivity(new Intent(c, WizardActivity.class)); }

    /**
     * Cheap; call from PlayerActivity.onResume when the index exists. If the wizard saved new choices since last time, For You
     * is recomputed off the UI thread and the ranked lists are dropped, so the next list that opens reflects them.
     */
    public static void sync(final Store st, final Index ix) {
        int rev = st.setupRev();
        if (seenRev < 0) { seenRev = rev; return; }
        if (rev == seenRev) return;
        seenRev = rev;
        new Thread(new Runnable() {
            @Override public void run() {
                List<Channel> fy = Index.forYou(ix.all, st);
                ix.forYou = fy;
                ix.rebuildNow();
            }
        }, "foryou").start();
    }
}
