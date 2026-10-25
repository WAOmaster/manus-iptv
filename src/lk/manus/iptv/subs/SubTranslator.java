package lk.manus.iptv.subs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.common.sdkinternal.MlKitContext;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lk.manus.iptv.player.CueCache;

/**
 * T40: on-device (ML Kit) translation of subtitle cues to English. One Translator per source language, closed after 2 idle minutes.
 * ML Kit runs the model on its own worker thread and delivers results on the main thread, so every method here is UI-thread only and never blocks it.
 * Anything that goes wrong (no Play services, no network for the model) degrades to "no translation": callers keep showing the original.
 */
public final class SubTranslator {
    private static final String TAG = "SubTr";
    private static final long IDLE_MS = 120_000;

    public interface Cb { void done(String text); }   // null = could not translate / identify
    public interface Listener {
        void downloading(String lang);   // model missing: a download (~30 MB, once) has started
        void ready(String lang);
        void failed(String lang);
    }

    private final Context app;
    private final Listener ls;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final CueCache cache = new CueCache(200);
    private final Map<String, Translator> tr = new HashMap<>();
    private final Set<String> ready = new HashSet<>(), pending = new HashSet<>();
    private final Map<String, Long> used = new HashMap<>();
    private LanguageIdentifier lid;
    private long lidUsed;
    private boolean inited, bad;
    private final Runnable idle = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            for (String k : new ArrayList<>(tr.keySet())) if (now - used.get(k) >= IDLE_MS) closeLang(k);
            if (lid != null && now - lidUsed >= IDLE_MS) { lid.close(); lid = null; }
            if (!tr.isEmpty() || lid != null) h.postDelayed(this, 30_000);
        }
    };

    public SubTranslator(Context c, Listener l) { app = c.getApplicationContext(); ls = l; }

    /** False when ML Kit cannot start on this TV (logged once). */
    public boolean ok() {
        if (bad) return false;
        if (!inited) {
            try { MlKitContext.initializeIfNeeded(app); inited = true; }
            catch (Throwable t) { bad = true; Log.w(TAG, "ML Kit unavailable: " + t); return false; }
        }
        return true;
    }

    public static boolean supported(String lang) { return !lang.isEmpty() && TranslateLanguage.fromLanguageTag(lang) != null; }
    public boolean isReady(String lang) { return ready.contains(lang); }

    /** Makes lang -> English ready (downloads the model on first need). Safe to call repeatedly. */
    public void ensure(final String lang) {
        if (!ok() || ready.contains(lang) || !pending.add(lang)) return;
        try {
            final Translator t = translator(lang);
            RemoteModelManager.getInstance().isModelDownloaded(new TranslateRemoteModel.Builder(lang).build()).addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Boolean>() {
                @Override public void onSuccess(Boolean have) {
                    if (!have) ls.downloading(lang);
                    final long t0 = SystemClock.elapsedRealtime();
                    t.downloadModelIfNeeded(new DownloadConditions.Builder().build()).addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) { pending.remove(lang); ready.add(lang); Log.i(TAG, lang + "->en ready after " + (SystemClock.elapsedRealtime() - t0) + " ms"); ls.ready(lang); }
                    }).addOnFailureListener(fail(lang));
                }
            }).addOnFailureListener(fail(lang));
        } catch (Throwable t) { pending.remove(lang); fail(lang).onFailure(new Exception(t)); }
    }

    private com.google.android.gms.tasks.OnFailureListener fail(final String lang) {
        return new com.google.android.gms.tasks.OnFailureListener() {
            @Override public void onFailure(Exception e) { pending.remove(lang); Log.w(TAG, lang + "->en model failed: " + e); ls.failed(lang); }
        };
    }

    private Translator translator(String lang) {
        Translator t = tr.get(lang);
        if (t == null) {
            t = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(lang).setTargetLanguage(TranslateLanguage.ENGLISH).build());
            tr.put(lang, t);
        }
        touch(lang);
        return t;
    }

    private void touch(String lang) {
        used.put(lang, SystemClock.elapsedRealtime());
        if (!h.hasCallbacks(idle)) h.postDelayed(idle, 30_000);
    }

    private void closeLang(String lang) {
        Translator t = tr.remove(lang);
        used.remove(lang);
        ready.remove(lang);
        if (t != null) try { t.close(); } catch (Throwable ignored) { }
        Log.i(TAG, lang + "->en closed (idle)");
    }

    /** Translates one cue; cb.done runs on the UI thread (at once on a cache hit). Needs isReady(lang). */
    public void translate(final String lang, final String text, final Cb cb) {
        String hit = cache.get(lang, text);
        if (hit != null) { cb.done(hit); return; }
        if (!ready.contains(lang)) { cb.done(null); return; }
        final long t0 = SystemClock.elapsedRealtime();
        try {
            translator(lang).translate(text).addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<String>() {
                @Override public void onSuccess(String r) { cache.put(lang, text, r); Log.i(TAG, "cue " + lang + " " + text.length() + " chars -> " + (SystemClock.elapsedRealtime() - t0) + " ms"); cb.done(r); }
            }).addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                @Override public void onFailure(Exception e) { Log.w(TAG, "translate failed: " + e); cb.done(null); }
            });
        } catch (Throwable t) { Log.w(TAG, "translate error: " + t); cb.done(null); }
    }

    /** Language of a cue ("hi"); null when unsure ("und") or ML Kit failed. */
    public void detect(String text, final Cb cb) {
        if (!ok()) { cb.done(null); return; }
        try {
            if (lid == null) lid = LanguageIdentification.getClient();
            lidUsed = SystemClock.elapsedRealtime();
            if (!h.hasCallbacks(idle)) h.postDelayed(idle, 30_000);
            lid.identifyLanguage(text).addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<String>() {
                @Override public void onSuccess(String l) { Log.i(TAG, "detected " + l); cb.done("und".equals(l) ? null : lk.manus.iptv.player.SubPref.norm(l)); }
            }).addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                @Override public void onFailure(Exception e) { Log.w(TAG, "language-id failed: " + e); cb.done(null); }
            });
        } catch (Throwable t) { Log.w(TAG, "language-id error: " + t); cb.done(null); }
    }

    public void close() {
        h.removeCallbacks(idle);
        for (String k : new ArrayList<>(tr.keySet())) closeLang(k);
        if (lid != null) { lid.close(); lid = null; }
        pending.clear();
    }

    /** "Hindi" for "hi". */
    public static String langName(String tag) { return new Locale(tag).getDisplayLanguage(Locale.ENGLISH); }

    // ---- downloaded models (Settings) ----

    public interface ListCb { void done(List<String> langs); }

    public static void downloaded(Context c, final ListCb cb) {
        try {
            MlKitContext.initializeIfNeeded(c.getApplicationContext());
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Set<TranslateRemoteModel>>() {
                @Override public void onSuccess(Set<TranslateRemoteModel> s) {
                    List<String> o = new ArrayList<>();
                    for (TranslateRemoteModel m : s) if (!m.getLanguage().equals(TranslateLanguage.ENGLISH)) o.add(m.getLanguage());
                    java.util.Collections.sort(o);
                    cb.done(o);
                }
            }).addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                @Override public void onFailure(Exception e) { cb.done(null); }
            });
        } catch (Throwable t) { cb.done(null); }
    }

    public static void delete(String lang, final Runnable done) {
        try {
            Task<Void> t = RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(lang).build());
            t.addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<Void>() { @Override public void onComplete(Task<Void> x) { done.run(); } });
        } catch (Throwable t) { done.run(); }
    }
}
