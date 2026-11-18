package tv.iptv.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide singletons and small helpers. */
final class App {
    static final int LIVE = 0, VOD = 1, SERIES = 2, EPISODE = 3;

    static Context ctx;
    static float density = 2f;
    static SharedPreferences prefs;
    static Db db;
    static final Handler ui = new Handler(Looper.getMainLooper());
    static final ExecutorService io = Executors.newFixedThreadPool(4);
    /** Network calls can block for a full timeout; they get their own threads so DB loads on io never wait behind them. */
    static final ExecutorService netIo = Executors.newFixedThreadPool(3);

    /** Screens register here to be told when the library or personal data changed. */
    interface DataListener { void onDataChanged(); }
    private static final ArrayList<DataListener> listeners = new ArrayList<>();

    private App() {}

    static void init(Context c) {
        if (ctx != null) return;
        ctx = c.getApplicationContext();
        density = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        db = new Db(ctx);
        Img.init(ctx);
    }

    static int dp(float v) { return Math.round(v * density); }

    static void bg(Runnable r) { io.execute(r); }

    /** For work that talks to the network (sync, details, sign-in, pairing). */
    static void net(Runnable r) { netIo.execute(r); }

    static void main(Runnable r) { ui.post(r); }

    static void listen(DataListener l) { if (!listeners.contains(l)) listeners.add(l); }

    static void notifyDataChanged() {
        for (DataListener l : new ArrayList<>(listeners)) l.onDataChanged();
    }

    static boolean signedIn() {
        return prefs.getString("server", "").length() > 0 && prefs.getString("user", "").length() > 0;
    }

    /** Lower-cased, accent-stripped form used for search matching. */
    static String norm(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        boolean ascii = true;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) > 127) { ascii = false; break; }
        }
        if (ascii) return lower;
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    static String duration(long ms) {
        long min = Math.max(1, Math.round(ms / 60000.0));
        if (min >= 60) return (min / 60) + " h " + (min % 60 == 0 ? "" : (min % 60) + " min");
        return min + " min";
    }
}
