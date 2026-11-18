package tv.iptv.player;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.LongSparseArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** A channel, movie or series as listed by the provider. */
final class Item {
    int kind;
    long id;
    String name, icon, cat, ext, epg;
    int num;
    long added;
    float rating;
    boolean arch;

    /** Pseudo-channels such as "##### SPORTS FHD #####" that only divide a list. */
    boolean divider() { return kind == App.LIVE && Names.divider(name); }
}

final class Cat {
    String id, name;
    int count;
    boolean hidden, pinned, off;

    Cat() {}

    Cat(String id, String name) { this.id = id; this.name = name; }

    boolean special() { return id != null && id.startsWith("@"); }
}

/** A region (live) or language (movies and series) that whole sets of categories belong to. */
final class Group {
    String code, label;
    int items;
}

/** Resume position for a movie or an episode. */
final class Prog {
    int kind;
    long id, pos, dur, ts, sid;
    boolean done;
    String title, sub, icon, ext;
    /** Set on a finished episode: the episode that follows, so the series stays in Continue Watching. */
    Prog next;

    float fraction() { return dur > 0 ? Math.min(1f, pos / (float) dur) : 0f; }
}

/** The whole catalogue lives in SQLite so lists open instantly and memory stays flat. */
final class Db extends SQLiteOpenHelper {
    private static final String COLS = "id,name,icon,cat,num,added,rating,ext,epg,arch";
    /** Categories the user does not want to see: hidden one by one, or switched off as a region / language. */
    private static final String HIDDEN = "(SELECT id FROM catflag WHERE kind=? AND (hidden=1 OR goff=1))";

    Db(Context c) {
        super(c, "library.db", null, 3);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE cat(kind INTEGER, id TEXT, name TEXT, pos INTEGER, PRIMARY KEY(kind,id)) WITHOUT ROWID");
        d.execSQL("CREATE TABLE catflag(kind INTEGER, id TEXT, hidden INTEGER DEFAULT 0, pinned INTEGER DEFAULT 0, goff INTEGER DEFAULT 0, PRIMARY KEY(kind,id)) WITHOUT ROWID");
        d.execSQL("CREATE TABLE item(kind INTEGER, id INTEGER, name TEXT, norm TEXT, icon TEXT, cat TEXT, num INTEGER, "
                + "added INTEGER, rating REAL, ext TEXT, epg TEXT, arch INTEGER, PRIMARY KEY(kind,id)) WITHOUT ROWID");
        d.execSQL("CREATE INDEX item_cat ON item(kind,cat)");
        d.execSQL("CREATE INDEX item_added ON item(kind,added)");
        d.execSQL("CREATE INDEX item_num ON item(kind,num,name)");
        d.execSQL("CREATE TABLE fav(kind INTEGER, id INTEGER, ts INTEGER, PRIMARY KEY(kind,id)) WITHOUT ROWID");
        d.execSQL("CREATE TABLE recent(id INTEGER PRIMARY KEY, ts INTEGER)");
        d.execSQL("CREATE TABLE prog(kind INTEGER, id INTEGER, pos INTEGER, dur INTEGER, ts INTEGER, done INTEGER, sid INTEGER, "
                + "title TEXT, sub TEXT, icon TEXT, ext TEXT, PRIMARY KEY(kind,id)) WITHOUT ROWID");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int from, int to) {
        if (from < 2) d.execSQL("ALTER TABLE catflag ADD COLUMN goff INTEGER DEFAULT 0");
        if (from < 3) d.execSQL("CREATE INDEX IF NOT EXISTS item_num ON item(kind,num,name)");
    }

    // ------------------------------------------------------------------ sync

    void replaceCats(int kind, List<Cat> cats) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("cat", "kind=?", new String[]{String.valueOf(kind)});
            SQLiteStatement st = d.compileStatement("INSERT OR REPLACE INTO cat VALUES(?,?,?,?)");
            int pos = 0;
            for (Cat c : cats) {
                st.bindLong(1, kind);
                st.bindString(2, c.id);
                st.bindString(3, c.name == null ? "" : c.name);
                st.bindLong(4, pos++);
                st.executeInsert();
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** Replaces every item of one kind inside a single transaction; readers keep the old rows until commit. */
    final class Bulk {
        private final SQLiteDatabase d = getWritableDatabase();
        private final SQLiteStatement st;
        private final int kind;
        int count;

        Bulk(int kind) {
            this.kind = kind;
            d.beginTransactionNonExclusive();
            d.delete("item", "kind=?", new String[]{String.valueOf(kind)});
            st = d.compileStatement("INSERT OR REPLACE INTO item VALUES(?,?,?,?,?,?,?,?,?,?,?,?)");
        }

        void add(Item it) {
            st.bindLong(1, kind);
            st.bindLong(2, it.id);
            st.bindString(3, it.name == null ? "" : it.name);
            st.bindString(4, App.norm(it.name));
            bind(5, it.icon);
            bind(6, it.cat);
            st.bindLong(7, it.num);
            st.bindLong(8, it.added);
            st.bindDouble(9, it.rating);
            bind(10, it.ext);
            bind(11, it.epg);
            st.bindLong(12, it.arch ? 1 : 0);
            st.executeInsert();
            count++;
        }

        private void bind(int i, String s) {
            if (s == null || s.isEmpty()) st.bindNull(i); else st.bindString(i, s);
        }

        void finish(boolean ok) {
            try {
                if (ok) d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        }
    }

    // ------------------------------------------------------------- catalogue

    List<Cat> cats(int kind, boolean includeHidden) {
        ArrayList<Cat> out = new ArrayList<>();
        String k = String.valueOf(kind);
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT c.id, c.name, (SELECT COUNT(*) FROM item i WHERE i.kind=c.kind AND i.cat=c.id), "
                        + "IFNULL(f.hidden,0), IFNULL(f.pinned,0), IFNULL(f.goff,0) FROM cat c "
                        + "LEFT JOIN catflag f ON f.kind=c.kind AND f.id=c.id WHERE c.kind=? "
                        + "ORDER BY IFNULL(f.pinned,0) DESC, c.pos", new String[]{k})) {
            while (c.moveToNext()) {
                Cat cat = new Cat(c.getString(0), c.getString(1));
                cat.count = c.getInt(2);
                cat.hidden = c.getInt(3) != 0;
                cat.pinned = c.getInt(4) != 0;
                cat.off = c.getInt(5) != 0;
                if (cat.count == 0) continue;
                if ((cat.hidden || cat.off) && !includeHidden) continue;
                out.add(cat);
            }
        }
        return out;
    }

    void setCatFlags(int kind, String id, boolean hidden, boolean pinned) {
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("INSERT OR IGNORE INTO catflag(kind,id) VALUES(?,?)", new Object[]{kind, id});
        d.execSQL("UPDATE catflag SET hidden=?, pinned=? WHERE kind=? AND id=?", new Object[]{hidden ? 1 : 0, pinned ? 1 : 0, kind, id});
    }

    /** Re-derives which categories are switched off by the language, region and adult settings. */
    void applyGroups() {
        java.util.Set<String> langs = App.prefs.getStringSet("lang_on", new HashSet<String>());
        java.util.Set<String> regions = App.prefs.getStringSet("region_on", new HashSet<String>());
        boolean adult = App.prefs.getBoolean("adult", false);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try (Cursor c = d.rawQuery("SELECT kind,id,name FROM cat", null)) {
            while (c.moveToNext()) {
                int kind = c.getInt(0);
                String id = c.getString(1), name = c.getString(2);
                boolean off;
                if (Groups.adult(name)) {
                    off = !adult;
                } else {
                    java.util.Set<String> on = kind == App.LIVE ? regions : langs;
                    off = !on.isEmpty() && !on.contains(Groups.of(kind, name));
                }
                d.execSQL("INSERT OR IGNORE INTO catflag(kind,id) VALUES(?,?)", new Object[]{kind, id});
                d.execSQL("UPDATE catflag SET goff=? WHERE kind=? AND id=?", new Object[]{off ? 1 : 0, kind, id});
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** Regions for live TV, or languages across movies and series, in the provider's own order. */
    List<Group> groups(boolean live) {
        java.util.LinkedHashMap<String, Group> map = new java.util.LinkedHashMap<>();
        int[] kinds = live ? new int[]{App.LIVE} : new int[]{App.VOD, App.SERIES};
        for (int kind : kinds) {
            for (Cat c : cats(kind, true)) {
                if (Groups.adult(c.name)) continue;
                String code = Groups.of(kind, c.name);
                Group g = map.get(code);
                if (g == null) {
                    g = new Group();
                    g.code = code;
                    g.label = Groups.label(code, live);
                    map.put(code, g);
                }
                g.items += c.count;
            }
        }
        return new ArrayList<>(map.values());
    }

    private static Item read(Cursor c, int kind) {
        Item it = new Item();
        it.kind = kind;
        it.id = c.getLong(0);
        it.name = c.getString(1);
        it.icon = c.getString(2);
        it.cat = c.getString(3);
        it.num = c.getInt(4);
        it.added = c.getLong(5);
        it.rating = c.getFloat(6);
        it.ext = c.getString(7);
        it.epg = c.getString(8);
        it.arch = c.getInt(9) != 0;
        return it;
    }

    private List<Item> query(int kind, String sql, String... args) {
        ArrayList<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(read(c, kind));
        }
        return out;
    }

    private static String order(int kind) {
        return kind == App.LIVE ? " ORDER BY num, name" : " ORDER BY added DESC, name";
    }

    List<Item> items(int kind, String cat) {
        return query(kind, "SELECT " + COLS + " FROM item WHERE kind=? AND cat=?" + order(kind), String.valueOf(kind), cat);
    }

    /** Every item of a kind, minus hidden categories. Only used for channels. */
    List<Item> all(int kind) {
        String k = String.valueOf(kind);
        return query(kind, "SELECT " + COLS + " FROM item WHERE kind=? AND IFNULL(cat,'') NOT IN " + HIDDEN + order(kind), k, k);
    }

    List<Item> newest(int kind, int limit) {
        String k = String.valueOf(kind);
        return query(kind, "SELECT " + COLS + " FROM item WHERE kind=? AND IFNULL(cat,'') NOT IN " + HIDDEN
                + " ORDER BY added DESC LIMIT " + limit, k, k);
    }

    /** Well-rated recent titles from the categories the user keeps visible. */
    List<Item> recommended(int kind, int limit) {
        String k = String.valueOf(kind);
        return query(kind, "SELECT " + COLS + " FROM item WHERE kind=? AND rating>=7 AND rating<=10 AND icon IS NOT NULL "
                + "AND IFNULL(cat,'') NOT IN " + HIDDEN + " ORDER BY added DESC LIMIT " + limit + " OFFSET 40", k, k);
    }

    /** Visible live categories, pinned first: the shortcuts shown at the top of Home. */
    List<Cat> topCats(int kind, int limit) {
        List<Cat> all = cats(kind, false);
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    List<Item> favs(int kind) {
        return query(kind, "SELECT i.id,i.name,i.icon,i.cat,i.num,i.added,i.rating,i.ext,i.epg,i.arch FROM fav f "
                + "JOIN item i ON i.kind=f.kind AND i.id=f.id WHERE f.kind=? ORDER BY "
                + (kind == App.LIVE ? "i.num" : "f.ts DESC"), String.valueOf(kind));
    }

    List<Item> recentChannels(int limit) {
        return query(App.LIVE, "SELECT i.id,i.name,i.icon,i.cat,i.num,i.added,i.rating,i.ext,i.epg,i.arch FROM recent r "
                + "JOIN item i ON i.kind=0 AND i.id=r.id ORDER BY r.ts DESC LIMIT " + limit);
    }

    List<Item> search(int kind, String text, int limit) {
        String q = App.norm(text).trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        if (q.isEmpty()) return new ArrayList<>();
        return query(kind, "SELECT " + COLS + " FROM item WHERE kind=? AND norm LIKE ? ESCAPE '\\' AND IFNULL(cat,'') NOT IN " + HIDDEN + " "
                        + "ORDER BY CASE WHEN norm LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, "
                        + (kind == App.LIVE ? "num" : "added DESC") + " LIMIT " + limit,
                String.valueOf(kind), "%" + q.replace(' ', '%') + "%", String.valueOf(kind), q + "%");
    }

    Item item(int kind, long id) {
        List<Item> l = query(kind, "SELECT " + COLS + " FROM item WHERE kind=? AND id=?", String.valueOf(kind), String.valueOf(id));
        return l.isEmpty() ? null : l.get(0);
    }

    int count(int kind) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM item WHERE kind=?", new String[]{String.valueOf(kind)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // -------------------------------------------------------------- personal

    boolean isFav(int kind, long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM fav WHERE kind=? AND id=?",
                new String[]{String.valueOf(kind), String.valueOf(id)})) {
            return c.moveToFirst();
        }
    }

    HashSet<Long> favIds(int kind) {
        HashSet<Long> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM fav WHERE kind=?", new String[]{String.valueOf(kind)})) {
            while (c.moveToNext()) out.add(c.getLong(0));
        }
        return out;
    }

    void setFav(int kind, long id, boolean on) {
        if (on) {
            getWritableDatabase().execSQL("INSERT OR REPLACE INTO fav VALUES(?,?,?)", new Object[]{kind, id, System.currentTimeMillis()});
        } else {
            getWritableDatabase().execSQL("DELETE FROM fav WHERE kind=? AND id=?", new Object[]{kind, id});
        }
    }

    void touchRecent(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("INSERT OR REPLACE INTO recent VALUES(?,?)", new Object[]{id, System.currentTimeMillis()});
        d.execSQL("DELETE FROM recent WHERE id NOT IN (SELECT id FROM recent ORDER BY ts DESC LIMIT 40)");
    }

    private static Prog readProg(Cursor c) {
        Prog p = new Prog();
        p.kind = c.getInt(0);
        p.id = c.getLong(1);
        p.pos = c.getLong(2);
        p.dur = c.getLong(3);
        p.ts = c.getLong(4);
        p.done = c.getInt(5) != 0;
        p.sid = c.getLong(6);
        p.title = c.getString(7);
        p.sub = c.getString(8);
        p.icon = c.getString(9);
        p.ext = c.getString(10);
        return p;
    }

    void saveProg(Prog p) {
        long now = System.currentTimeMillis();
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("INSERT OR REPLACE INTO prog VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{p.kind, p.id, p.pos, p.dur, now, p.done ? 1 : 0, p.sid,
                        p.title == null ? "" : p.title, p.sub == null ? "" : p.sub, p.icon == null ? "" : p.icon,
                        p.ext == null ? "" : p.ext});
        Prog n = p.next;
        if (n == null) return;
        // Queue the following episode as the series' newest entry; keep its position if it was already part-watched.
        d.execSQL("INSERT OR IGNORE INTO prog VALUES(?,?,0,0,?,0,?,?,?,?,?)",
                new Object[]{n.kind, n.id, now + 1, n.sid,
                        n.title == null ? "" : n.title, n.sub == null ? "" : n.sub, n.icon == null ? "" : n.icon,
                        n.ext == null ? "" : n.ext});
        d.execSQL("UPDATE prog SET ts=?, pos=CASE WHEN done<>0 THEN 0 ELSE pos END, done=0 WHERE kind=? AND id=?",
                new Object[]{now + 1, n.kind, n.id});
    }

    Prog prog(int kind, long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM prog WHERE kind=? AND id=?",
                new String[]{String.valueOf(kind), String.valueOf(id)})) {
            return c.moveToFirst() ? readProg(c) : null;
        }
    }

    /** Unfinished movies and, per series, only the most recently watched episode. */
    List<Prog> continueWatching() {
        ArrayList<Prog> out = new ArrayList<>();
        HashSet<Long> series = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM prog ORDER BY ts DESC LIMIT 200", null)) {
            while (c.moveToNext() && out.size() < 30) {
                Prog p = readProg(c);
                if (p.kind == App.EPISODE && !series.add(p.sid)) continue;
                if (p.done) continue;
                out.add(p);
            }
        }
        return out;
    }

    /** Episode id → progress, plus the most recent entry under key -1. */
    LongSparseArray<Prog> seriesProg(long sid) {
        LongSparseArray<Prog> out = new LongSparseArray<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM prog WHERE kind=3 AND sid=? ORDER BY ts DESC",
                new String[]{String.valueOf(sid)})) {
            while (c.moveToNext()) {
                Prog p = readProg(c);
                if (out.size() == 0) out.put(-1, p);
                out.put(p.id, p);
            }
        }
        return out;
    }

    void removeProg(Prog p) {
        if (p.kind == App.EPISODE) {
            getWritableDatabase().execSQL("DELETE FROM prog WHERE kind=3 AND sid=?", new Object[]{p.sid});
        } else {
            getWritableDatabase().execSQL("DELETE FROM prog WHERE kind=? AND id=?", new Object[]{p.kind, p.id});
        }
    }

    void clearHistory() {
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("DELETE FROM prog");
        d.execSQL("DELETE FROM recent");
    }

    void wipe() {
        SQLiteDatabase d = getWritableDatabase();
        for (String t : new String[]{"cat", "catflag", "item", "fav", "recent", "prog"}) d.execSQL("DELETE FROM " + t);
    }
}
