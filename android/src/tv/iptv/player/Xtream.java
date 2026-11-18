package tv.iptv.player;

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class Episode {
    long id, durMs;
    int season, num;
    String title, plot, image, ext;

    String label() { return "S" + season + " E" + num; }
}

/** Extra metadata fetched when a movie or series is opened. */
final class Details {
    String plot = "", cast = "", director = "", genre = "", year = "", backdrop, poster, ext;
    float rating;
    long durMs;
    final ArrayList<Integer> seasons = new ArrayList<>();
    final ArrayList<Episode> episodes = new ArrayList<>();

    List<Episode> season(int s) {
        ArrayList<Episode> out = new ArrayList<>();
        for (Episode e : episodes) if (e.season == s) out.add(e);
        return out;
    }
}

/** Xtream Codes client. */
final class Xtream {
    static final String TAG = "IPTV";
    static final String UA = "IPTVSmartersPro";

    interface Progress { void on(String stage, int count); }

    /** Bumped on the main thread at sign-out; a sync started under an older value stops and commits nothing more. */
    static volatile int epoch;
    /** Makes each download's spool file unique: two activities in one process can sync under the same epoch. */
    private static final java.util.concurrent.atomic.AtomicInteger spool = new java.util.concurrent.atomic.AtomicInteger();

    private static void live(int g) throws IOException {
        if (g != epoch) throw new IOException("sync cancelled");
    }

    private Xtream() {}

    static String server() { return App.prefs.getString("server", ""); }

    private static String enc(String s) { return Uri.encode(s); }

    static String normServer(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        int q = s.indexOf("/player_api.php");
        if (q > 0) s = s.substring(0, q);
        q = s.indexOf("/get.php");
        if (q > 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String api(String server, String user, String pass, String action) {
        return server + "/player_api.php?username=" + enc(user) + "&password=" + enc(pass)
                + (action == null ? "" : "&action=" + action);
    }

    private static String api(String action) {
        return api(server(), App.prefs.getString("user", ""), App.prefs.getString("pass", ""), action);
    }

    private static String path(String type, long id, String ext) {
        return server() + "/" + type + "/" + enc(App.prefs.getString("user", "")) + "/"
                + enc(App.prefs.getString("pass", "")) + "/" + id + "." + ext;
    }

    static String liveUrl(long id, String fmt) { return path("live", id, fmt); }

    static String vodUrl(long id, String ext) { return path("movie", id, ext == null || ext.isEmpty() ? "mp4" : ext); }

    static String episodeUrl(long id, String ext) { return path("series", id, ext == null || ext.isEmpty() ? "mp4" : ext); }

    // ------------------------------------------------------------------ http

    static HttpURLConnection open(String url, int readTimeout) throws IOException {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Bad redirect");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    static InputStream body(HttpURLConnection c) throws IOException {
        InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
        return "gzip".equalsIgnoreCase(c.getContentEncoding()) ? new GZIPInputStream(in, 1 << 16) : in;
    }

    static String text(String url, int readTimeout) throws IOException {
        HttpURLConnection c = open(url, readTimeout);
        try (InputStream in = body(c)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    // --------------------------------------------------------------- account

    /** Verifies and stores the credentials. Returns null on success or a message to show. */
    static String signIn(String rawServer, String user, String pass) {
        String server = normServer(rawServer);
        user = user == null ? "" : user.trim();
        pass = pass == null ? "" : pass.trim();
        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) return "Enter the server address, username and password.";
        try {
            JSONObject root = new JSONObject(text(api(server, user, pass, null), 20000));
            JSONObject info = root.optJSONObject("user_info");
            if (info == null || info.optInt("auth", 0) != 1) return "The username or password is not correct.";
            String status = info.optString("status", "Active");
            if (!"Active".equalsIgnoreCase(status) && !"Trial".equalsIgnoreCase(status)) {
                return "This subscription is " + status.toLowerCase() + ".";
            }
            App.prefs.edit().putString("server", server).putString("user", user).putString("pass", pass).apply();
            storeAccount(info);
            return null;
        } catch (org.json.JSONException e) {
            return "That address did not answer like an IPTV server.";
        } catch (IOException e) {
            return "Could not reach the server. Check the address and your network.";
        }
    }

    private static void storeAccount(JSONObject info) {
        boolean ts = true;
        JSONArray formats = info.optJSONArray("allowed_output_formats");
        if (formats != null && formats.length() > 0) {
            ts = false;
            for (int i = 0; i < formats.length(); i++) if ("ts".equals(formats.optString(i))) ts = true;
        }
        App.prefs.edit()
                .putString("acct_status", info.optString("status", ""))
                .putLong("acct_exp", num(info.optString("exp_date", "0")))
                .putInt("acct_max", (int) num(info.optString("max_connections", "1")))
                .putBoolean("acct_trial", "1".equals(info.optString("is_trial", "0")))
                .putBoolean("acct_ts", ts)
                .apply();
    }

    static void refreshAccount() {
        try {
            JSONObject info = new JSONObject(text(api(null), 20000)).optJSONObject("user_info");
            if (info != null && info.optInt("auth", 0) == 1) storeAccount(info);
        } catch (Exception e) {
            Log.w(TAG, "account refresh failed: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ sync

    static void sync(int g, Progress p) throws Exception {
        live(g);
        p.on("Channels", 0);
        loadCats(App.LIVE, "get_live_categories", g);
        int live = loadItems(App.LIVE, "get_live_streams", p, "Channels", g);
        int vod = 0, series = 0;
        live(g);
        try {
            p.on("Movies", 0);
            loadCats(App.VOD, "get_vod_categories", g);
            vod = loadItems(App.VOD, "get_vod_streams", p, "Movies", g);
        } catch (Exception e) {
            Log.w(TAG, "movies sync failed: " + e);
        }
        live(g);
        try {
            p.on("Series", 0);
            loadCats(App.SERIES, "get_series_categories", g);
            series = loadItems(App.SERIES, "get_series", p, "Series", g);
        } catch (Exception e) {
            Log.w(TAG, "series sync failed: " + e);
        }
        live(g);
        Log.i(TAG, "sync done: " + live + " channels, " + vod + " movies, " + series + " series");
        // An error page, an auth error object or an empty list parses to nothing; that is a failed sync, not an empty library.
        if (live + vod + series == 0) throw new IOException("sync returned no items");
        App.prefs.edit().putLong("synced", System.currentTimeMillis()).apply();
    }

    private static void loadCats(int kind, String action, int g) throws Exception {
        String raw = text(api(action), 60000).trim();
        live(g);
        if (!raw.startsWith("[")) return;
        JSONArray arr = new JSONArray(raw);
        ArrayList<Cat> cats = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("category_id", "");
            if (id.isEmpty()) continue;
            cats.add(new Cat(id, o.optString("category_name", "").trim()));
        }
        if (!cats.isEmpty()) App.db.replaceCats(kind, cats);
    }

    private static int loadItems(int kind, String action, Progress p, String label, int g) throws IOException {
        // Download to disk first: the write transaction must not stay open across a slow network read,
        // or every other writer (progress, favourites, recents) waits for the whole download.
        // The sync's epoch is in the name so a cancelled sync that is still winding down cannot touch a newer one's file.
        File tmp = new File(App.ctx.getCacheDir(), "sync-" + kind + "-" + g + "-" + spool.incrementAndGet() + ".json.gz");
        HttpURLConnection c = open(api(action), 120000);
        boolean got = false;
        try (InputStream in = body(c); OutputStream out = new GZIPOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                live(g);
                out.write(buf, 0, n);
            }
            got = true;
        } finally {
            c.disconnect();
            if (!got) tmp.delete();
        }
        Db.Bulk bulk = null;
        boolean ok = false;
        try (JsonReader r = new JsonReader(new InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(new FileInputStream(tmp), 1 << 16), 1 << 16), StandardCharsets.UTF_8))) {
            live(g);
            bulk = App.db.new Bulk(kind);
            r.setLenient(true);
            JsonToken t = r.peek();
            if (t == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    live(g);
                    readItem(r, bulk, p, label);
                }
                r.endArray();
            } else if (t == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    live(g);
                    r.nextName();
                    readItem(r, bulk, p, label);
                }
                r.endObject();
            }
            ok = true;
        } finally {
            if (bulk != null) bulk.finish(ok && bulk.count > 0 && g == epoch);
            tmp.delete();
        }
        p.on(label, bulk.count);
        return bulk.count;
    }

    private static void readItem(JsonReader r, Db.Bulk bulk, Progress p, String label) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return;
        }
        Item it = new Item();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "stream_id":
                case "series_id":
                    it.id = num(str(r));
                    break;
                case "name":
                    it.name = str(r);
                    break;
                case "stream_icon":
                case "cover":
                    it.icon = str(r);
                    break;
                case "category_id": {
                    String v = str(r);
                    if (v != null && !v.isEmpty()) it.cat = v;
                    break;
                }
                case "category_ids":
                    if (r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray();
                        while (r.hasNext()) {
                            String v = str(r);
                            if (it.cat == null && v != null) it.cat = v;
                        }
                        r.endArray();
                    } else {
                        r.skipValue();
                    }
                    break;
                case "num":
                    it.num = (int) num(str(r));
                    break;
                case "added":
                case "last_modified": {
                    long v = num(str(r));
                    if (v > it.added) it.added = v;
                    break;
                }
                case "rating":
                    it.rating = flt(str(r));
                    break;
                case "container_extension":
                    it.ext = str(r);
                    break;
                case "epg_channel_id":
                    it.epg = str(r);
                    break;
                case "tv_archive":
                    it.arch = num(str(r)) > 0;
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        if (it.id <= 0 || it.name == null) return;
        it.name = it.name.trim();
        bulk.add(it);
        if (bulk.count % 1000 == 0) p.on(label, bulk.count);
    }

    private static String str(JsonReader r) throws IOException {
        switch (r.peek()) {
            case NULL:
                r.nextNull();
                return null;
            case STRING:
            case NUMBER:
                return r.nextString();
            case BOOLEAN:
                return r.nextBoolean() ? "1" : "0";
            default:
                r.skipValue();
                return null;
        }
    }

    static long num(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(s.trim());
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }

    static float flt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --------------------------------------------------------------- details

    private static String pick(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                if (a.length() > 0 && !a.optString(0, "").isEmpty()) return a.optString(0, "");
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equals(s)) return s;
        }
        return "";
    }

    private static long seconds(JSONObject info) {
        long secs = num(pick(info, "duration_secs"));
        if (secs > 0) return secs;
        String d = pick(info, "duration");
        String[] parts = d.split(":");
        if (parts.length == 3) return num(parts[0]) * 3600 + num(parts[1]) * 60 + num(parts[2]);
        long runtime = num(pick(info, "episode_run_time", "runtime"));
        return runtime * 60;
    }

    private static String year(String date) {
        return date.length() >= 4 && date.substring(0, 4).matches("\\d{4}") ? date.substring(0, 4) : "";
    }

    static Details details(Item it) throws Exception {
        Details d = new Details();
        if (it.kind == App.VOD) {
            JSONObject root = new JSONObject(text(api("get_vod_info&vod_id=" + it.id), 30000));
            JSONObject info = root.optJSONObject("info");
            JSONObject data = root.optJSONObject("movie_data");
            fill(d, info);
            d.durMs = seconds(info) * 1000;
            d.ext = pick(data, "container_extension");
            if (d.ext.isEmpty()) d.ext = it.ext;
            return d;
        }
        JSONObject root = new JSONObject(text(api("get_series_info&series_id=" + it.id), 30000));
        fill(d, root.optJSONObject("info"));
        Object eps = root.opt("episodes");
        TreeSet<Integer> seasons = new TreeSet<>();
        if (eps instanceof JSONObject) {
            JSONObject o = (JSONObject) eps;
            for (Iterator<String> k = o.keys(); k.hasNext(); ) {
                String key = k.next();
                addEpisodes(d, o.optJSONArray(key), (int) num(key));
            }
        } else if (eps instanceof JSONArray) {
            JSONArray a = (JSONArray) eps;
            for (int i = 0; i < a.length(); i++) addEpisodes(d, a.optJSONArray(i), i + 1);
        }
        d.episodes.sort((a, b) -> a.season != b.season ? a.season - b.season : a.num - b.num);
        for (Episode e : d.episodes) seasons.add(e.season);
        d.seasons.addAll(seasons);
        return d;
    }

    private static void fill(Details d, JSONObject info) {
        if (info == null) return;
        d.plot = pick(info, "plot", "description", "overview");
        d.cast = pick(info, "cast", "actors");
        d.director = pick(info, "director");
        d.genre = pick(info, "genre");
        d.year = year(pick(info, "releasedate", "releaseDate", "release_date", "year"));
        d.backdrop = pick(info, "backdrop_path", "backdrop");
        d.poster = pick(info, "movie_image", "cover_big", "cover");
        d.rating = flt(pick(info, "rating"));
    }

    private static void addEpisodes(Details d, JSONArray arr, int season) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Episode e = new Episode();
            e.id = num(pick(o, "id"));
            if (e.id <= 0) continue;
            e.season = (int) num(pick(o, "season"));
            if (e.season <= 0) e.season = season;
            e.num = (int) num(pick(o, "episode_num"));
            if (e.num <= 0) e.num = i + 1;
            e.ext = pick(o, "container_extension");
            JSONObject info = o.optJSONObject("info");
            e.plot = pick(info, "plot", "overview");
            e.image = pick(info, "movie_image", "cover_big");
            e.durMs = seconds(info) * 1000;
            String title = pick(o, "title");
            // Providers usually send "Show - S01E02 - Real Title"; keep only the real title.
            int cut = title.lastIndexOf(" - ");
            if (cut >= 0 && cut + 3 < title.length()) title = title.substring(cut + 3);
            if (title.isEmpty() || title.matches("(?i)^(s\\d+\\s*e\\d+|episode \\d+|\\d+)$")) title = "Episode " + e.num;
            e.title = title;
            d.episodes.add(e);
        }
    }
}
