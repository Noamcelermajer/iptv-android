package tv.iptv.player;

import android.net.Uri;
import android.util.Base64;
import android.util.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;

/** Now/next programme data, fetched lazily per channel and kept in memory. */
final class Epg {
    static final class Show {
        long start, end;
        String title, desc;

        float fraction(long now) {
            return end > start ? Math.max(0f, Math.min(1f, (now - start) / (float) (end - start))) : 0f;
        }
    }

    interface Listener { void onEpg(long streamId); }

    private static final class Entry {
        Show[] shows;
        long fetched;
    }

    private static final Show[] NONE = new Show[0];
    private static final LongSparseArray<Entry> cache = new LongSparseArray<>();
    private static final ArrayDeque<Long> queue = new ArrayDeque<>();
    private static final HashSet<Long> pending = new HashSet<>();
    private static final ArrayList<Listener> listeners = new ArrayList<>();
    private static int workers;
    /** Own threads, so slow guide requests never hold up database reads. */
    private static final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);

    private Epg() {}

    static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    static void removeListener(Listener l) { listeners.remove(l); }

    static void clear() {
        synchronized (cache) { cache.clear(); }
    }

    /** Cached listing or null when nothing usable is known yet. Never blocks. */
    static Show[] peek(long id) {
        Entry e;
        synchronized (cache) { e = cache.get(id); }
        if (e == null) return null;
        long now = System.currentTimeMillis();
        long age = now - e.fetched;
        if (e.shows.length == 0) return age < 20 * 60000 ? e.shows : null;
        boolean stale = e.shows[e.shows.length - 1].end < now;
        return stale && age > 60000 ? null : e.shows;
    }

    /** Returns what is cached and queues a fetch if that is missing or stale. */
    static Show[] want(long id) {
        Show[] s = peek(id);
        if (s != null) return s;
        synchronized (queue) {
            if (pending.contains(id)) return null;
            queue.remove(id);
            queue.addFirst(id);
            while (queue.size() > 24) queue.removeLast();
            if (workers < 3) {
                workers++;
                pool.execute(Epg::work);
            }
        }
        return null;
    }

    private static void work() {
        while (true) {
            final long id;
            synchronized (queue) {
                Long next = queue.pollFirst();
                if (next == null) {
                    workers--;
                    return;
                }
                id = next;
                pending.add(id);
            }
            Entry e = new Entry();
            Show[] got = fetch(id);
            e.shows = got != null ? got : NONE;
            // A failed request is kept as "empty" for only 30 s, so it is retried soon without hammering the server.
            e.fetched = System.currentTimeMillis() - (got != null ? 0 : 20 * 60000 - 30000);
            synchronized (cache) { cache.put(id, e); }
            synchronized (queue) { pending.remove(id); }
            App.main(() -> {
                for (Listener l : new ArrayList<>(listeners)) l.onEpg(id);
            });
        }
    }

    private static Show[] fetch(long id) {
        try {
            String url = Xtream.server() + "/player_api.php?username=" + Uri.encode(App.prefs.getString("user", ""))
                    + "&password=" + Uri.encode(App.prefs.getString("pass", ""))
                    + "&action=get_short_epg&stream_id=" + id + "&limit=6";
            JSONArray arr = new JSONObject(Xtream.text(url, 12000)).optJSONArray("epg_listings");
            if (arr == null) return NONE;
            ArrayList<Show> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Show s = new Show();
                s.start = Xtream.num(o.optString("start_timestamp", "0")) * 1000;
                s.end = Xtream.num(o.optString("stop_timestamp", "0")) * 1000;
                s.title = b64(o.optString("title", ""));
                s.desc = b64(o.optString("description", ""));
                if (s.end > s.start && !s.title.isEmpty()) out.add(s);
            }
            out.sort((a, b) -> Long.compare(a.start, b.start));
            return out.toArray(new Show[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private static String b64(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return s.trim();
        }
    }

    static Show now(Show[] shows) {
        if (shows == null) return null;
        long now = System.currentTimeMillis();
        for (Show s : shows) if (s.start <= now && now < s.end) return s;
        return null;
    }

    static Show next(Show[] shows) {
        if (shows == null) return null;
        long now = System.currentTimeMillis();
        for (Show s : shows) if (s.start > now) return s;
        return null;
    }
}
