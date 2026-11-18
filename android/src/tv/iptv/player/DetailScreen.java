package tv.iptv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Artwork behind a detail page: a wide still that melts into the background, or the poster as a card. */
final class Backdrop extends View {
    private final Pic wide = new Pic(this, true), poster = new Pic(this, true);
    private final RectF r = new RectF();
    private final Paint side = new Paint(), bottom = new Paint(), veil = Ui.fill(0x33000000 | (Ui.BG & 0xFFFFFF));
    private final Paint card = Ui.fill(Ui.FILL);
    private final boolean shortArt;

    Backdrop(Context c, boolean shortArt) {
        super(c);
        this.shortArt = shortArt;
    }

    void setPoster(String url) { poster.set(Img.sized(url, App.dp(190)), App.dp(190), App.dp(285)); }

    void setWide(String url) {
        int w = getResources().getDisplayMetrics().widthPixels * 2 / 3;
        wide.set(Img.sized(url, w), w, w * 9 / 16);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float left = w * 0.34f, artH = shortArt ? h * 0.66f : h;
        int clear = Ui.BG & 0xFFFFFF;
        side.setShader(new LinearGradient(left, 0, left + w * 0.34f, 0, Ui.BG, clear, Shader.TileMode.CLAMP));
        bottom.setShader(new LinearGradient(0, artH * 0.45f, 0, artH, clear, Ui.BG, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (wide.has()) {
            r.set(w * 0.34f, 0, w, shortArt ? h * 0.66f : h);
            wide.cover(c, r, 0);
            c.drawRect(r, veil);
            c.drawRect(r, side);
            c.drawRect(r, bottom);
        } else if (poster.has()) {
            float pw = App.dp(shortArt ? 150 : 200), ph = pw * 1.5f;
            float right = w - App.dp(Ui.EDGE + 20), top = shortArt ? App.dp(Ui.EDGE_V + 4) : (h - ph) / 2f;
            r.set(right - pw, top, right, top + ph);
            c.drawRoundRect(r, App.dp(12), App.dp(12), card);
            poster.cover(c, r, App.dp(12));
        }
    }
}

/** A movie or a series: what it is, one obvious Play button, and for series the seasons and episodes. */
final class DetailScreen extends Screen {
    private final Item item;
    private final boolean series;
    private Backdrop art;
    private TextView meta, plot, credits;
    private Btn play, restart, list;
    private RList seasons, episodes;
    private Details details;
    private Prog movieProg;
    private LongSparseArray<Prog> episodeProg = new LongSparseArray<>();
    private List<Episode> shown = new ArrayList<>();
    private boolean fav;
    private boolean loading;
    private int shownSeason = Integer.MIN_VALUE;
    /** Delayed swap from the seasons row; a no-op when that season is already showing, so the episode selection survives. */
    private final Runnable seasonSwap = new Runnable() {
        @Override public void run() {
            if (details == null || details.seasons.isEmpty()) return;
            int s = details.seasons.get(Math.min(seasons.selected(), details.seasons.size() - 1));
            if (s != shownSeason) showSeason();
        }
    };

    DetailScreen(MainActivity a, Item item) {
        super(a);
        this.item = item;
        series = item.kind == App.SERIES;
    }

    @Override
    protected View build() {
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Ui.BG);
        root.setClipChildren(false);
        art = new Backdrop(a, series);
        art.setPoster(item.icon);
        root.addView(art, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);

        TextView title = Ui.text(a, series ? 26f : 30f, Ui.TEXT, Ui.BOLD);
        title.setText(Names.title(item.name));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));

        meta = Ui.text(a, 13f, Ui.TEXT2, Ui.MED);
        meta.setMaxLines(1);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = App.dp(8);
        col.addView(meta, lp);

        plot = Ui.text(a, 14f, Ui.TEXT2, Ui.REG);
        plot.setMaxLines(series ? 3 : 6);
        plot.setEllipsize(TextUtils.TruncateAt.END);
        plot.setLineSpacing(App.dp(4), 1f);
        plot.setText("Loading…");
        lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = App.dp(series ? 10 : 16);
        col.addView(plot, lp);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        play = new Btn(a, "Play", Glyph.PLAY, () -> start(false));
        restart = new Btn(a, "Start Over", Glyph.RESTART, () -> start(true));
        restart.setVisibility(View.GONE);
        list = new Btn(a, "My List", Glyph.PLUS, this::toggleList);
        row.addView(play);
        row.addView(restart, gap());
        row.addView(list, gap());
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = App.dp(series ? 16 : 24);
        col.addView(row, lp);

        credits = Ui.text(a, 12f, Ui.TEXT3, Ui.REG);
        credits.setMaxLines(2);
        credits.setEllipsize(TextUtils.TruncateAt.END);
        credits.setLineSpacing(App.dp(3), 1f);
        lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = App.dp(20);
        if (!series) col.addView(credits, lp);

        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(App.dp(series ? 500 : 430), -2,
                series ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL | Gravity.START);
        fl.leftMargin = App.dp(Ui.EDGE);
        fl.topMargin = series ? App.dp(Ui.EDGE_V + 4) : 0;
        root.addView(col, fl);

        if (series) buildEpisodes(root);
        return root;
    }

    private static LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = App.dp(12);
        return lp;
    }

    private void buildEpisodes(FrameLayout root) {
        LinearLayout block = new LinearLayout(a);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);

        seasons = new RList(a);
        seasons.horizontal = true;
        seasons.cellW = App.dp(104);
        seasons.cellH = App.dp(34);
        seasons.gap = App.dp(8);
        seasons.padStart = App.dp(Ui.EDGE);
        seasons.padEnd = App.dp(Ui.EDGE);
        seasons.trail = App.dp(Ui.EDGE + 40);
        seasons.setAdapter(new RList.Adapter() {
            @Override public int count() { return details == null ? 0 : details.seasons.size(); }

            @Override public View create(RList l) { return new ChipCell(a); }

            @Override public void bind(View v, int pos) { ((ChipCell) v).set("Season " + details.seasons.get(pos)); }

            @Override public void state(View v, float f, boolean sel) { ((ChipCell) v).state(f, sel); }
        });
        seasons.listener = new RList.Listener() {
            @Override void onSelect(int pos) {
                App.ui.removeCallbacks(seasonSwap);
                App.ui.postDelayed(seasonSwap, 120);
            }

            @Override void onClick(int pos) { if (episodes.count() > 0) episodes.requestFocus(); }
        };
        block.addView(seasons, new LinearLayout.LayoutParams(-1, App.dp(34)));

        episodes = new RList(a);
        episodes.horizontal = true;
        episodes.cellW = App.dp(212);
        episodes.cellH = App.dp(119 + EpisodeCell.TEXT_H);
        episodes.gap = App.dp(16);
        episodes.padStart = App.dp(Ui.EDGE);
        episodes.padEnd = App.dp(Ui.EDGE);
        episodes.padCross = App.dp(12);
        episodes.bleed = App.dp(12);
        episodes.trail = App.dp(Ui.EDGE + 50);
        episodes.trap = View.FOCUS_DOWN;
        episodes.setAdapter(new RList.Adapter() {
            @Override public int count() { return shown.size(); }

            @Override public View create(RList l) { return new EpisodeCell(a); }

            @Override public void bind(View v, int pos) {
                Episode e = shown.get(pos);
                Prog p = episodeProg.get(e.id);
                ((EpisodeCell) v).set(e, p != null && !p.done && p.pos > 0 ? p.fraction() : -1, p != null && p.done);
            }

            @Override public void state(View v, float f, boolean sel) { ((EpisodeCell) v).state(f); }
        });
        episodes.listener = new RList.Listener() {
            @Override void onClick(int pos) {
                Episode e = shown.get(pos);
                Prog p = episodeProg.get(e.id);
                playEpisode(e, p != null && !p.done ? p.pos : 0);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, episodes.cellH + 2 * episodes.padCross);
        lp.topMargin = App.dp(6);
        block.addView(episodes, lp);

        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        fl.bottomMargin = App.dp(Ui.EDGE_V - 14);
        root.addView(block, fl);
    }

    @Override
    View entry() { return play; }

    @Override
    void onShow() {
        if (details != null || loading) return;
        loading = true;
        App.net(() -> {
            Details d = null;
            try {
                d = Xtream.details(item);
            } catch (Exception ignored) {
            }
            final Details result = d;
            final LongSparseArray<Prog> eps = series ? App.db.seriesProg(item.id) : null;
            App.main(() -> {
                loading = false;
                if (result == null) {
                    plot.setText("Details are not available right now.");
                    return;
                }
                details = result;
                if (eps != null) episodeProg = eps;
                apply();
            });
        });
    }

    @Override
    void refresh() {
        dirty = false;
        App.bg(() -> {
            final boolean f = App.db.isFav(item.kind, item.id);
            final Prog p = series ? null : App.db.prog(App.VOD, item.id);
            final LongSparseArray<Prog> eps = series ? App.db.seriesProg(item.id) : new LongSparseArray<Prog>();
            App.main(() -> {
                fav = f;
                movieProg = p;
                episodeProg = eps;
                list.set(fav ? "In My List" : "My List", fav ? Glyph.CHECK : Glyph.PLUS);
                updatePlay();
                if (episodes != null) episodes.rebind();
            });
        });
    }

    private void apply() {
        Details d = details;
        plot.setText(d.plot.isEmpty() ? "No description available." : d.plot);
        ArrayList<String> bits = new ArrayList<>();
        String tag = Names.tag(item.name);
        if (!tag.isEmpty() && !Groups.label(tag, false).equals(tag)) bits.add(Groups.label(tag, false));
        if (!d.year.isEmpty()) bits.add(d.year);
        if (!series && d.durMs > 0) bits.add(App.duration(d.durMs));
        if (series && !d.seasons.isEmpty()) bits.add(d.seasons.size() + (d.seasons.size() == 1 ? " season" : " seasons"));
        if (!d.genre.isEmpty()) bits.add(d.genre);
        float rating = d.rating > 0 ? d.rating : item.rating;
        if (rating > 0) bits.add(String.format(Locale.US, "%.1f / 10", rating));
        meta.setText(TextUtils.join("   ·   ", bits));
        StringBuilder sb = new StringBuilder();
        if (!d.director.isEmpty()) sb.append("Director   ").append(d.director);
        if (!d.cast.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("Cast   ").append(d.cast);
        credits.setText(sb);
        if (d.backdrop != null && !d.backdrop.isEmpty()) art.setWide(d.backdrop);
        if (series) {
            seasons.reload(false);
            seasons.setVisibility(d.seasons.size() > 1 ? View.VISIBLE : View.INVISIBLE);
            Prog last = episodeProg.get(-1);
            int index = 0;
            if (last != null) {
                for (Episode e : d.episodes) if (e.id == last.id) index = Math.max(0, d.seasons.indexOf(e.season));
            }
            seasons.select(index, false);
            showSeason();
            if (last != null) {
                for (int i = 0; i < shown.size(); i++) if (shown.get(i).id == last.id) episodes.select(i, false);
            }
        }
        updatePlay();
    }

    private void showSeason() {
        if (details == null || details.seasons.isEmpty()) return;
        int s = details.seasons.get(Math.min(seasons.selected(), details.seasons.size() - 1));
        shown = details.season(s);
        shownSeason = s;
        episodes.reload(false);
    }

    /** The episode Play should start: the one in progress, the one after the last finished, or the first. */
    private Episode upNext() {
        if (details == null || details.episodes.isEmpty()) return null;
        Prog last = episodeProg.get(-1);
        if (last == null) return details.episodes.get(0);
        for (int i = 0; i < details.episodes.size(); i++) {
            Episode e = details.episodes.get(i);
            if (e.id != last.id) continue;
            if (last.done && i + 1 < details.episodes.size()) return details.episodes.get(i + 1);
            return e;
        }
        return details.episodes.get(0);
    }

    private void updatePlay() {
        if (series) {
            Episode e = upNext();
            Prog p = e == null ? null : episodeProg.get(e.id);
            boolean resume = p != null && !p.done && p.pos > 0;
            play.set(e == null ? "Play" : (resume ? "Resume " : "Play ") + e.label(), Glyph.PLAY);
        } else {
            boolean resume = movieProg != null && !movieProg.done && movieProg.pos > 15000;
            play.set(resume ? "Resume at " + App.clock(movieProg.pos) : "Play", Glyph.PLAY);
            if (!resume && restart.isFocused()) play.requestFocus();
            restart.setVisibility(resume ? View.VISIBLE : View.GONE);
        }
    }

    private void start(boolean fromStart) {
        if (series) {
            Episode e = upNext();
            if (e == null) {
                if (details == null && !loading) {
                    plot.setText("Loading…");
                    onShow();
                }
                a.toast(details == null ? "Still loading episodes…" : "No episodes available");
                return;
            }
            Prog p = episodeProg.get(e.id);
            playEpisode(e, p != null && !p.done ? p.pos : 0);
            return;
        }
        boolean resume = !fromStart && movieProg != null && !movieProg.done && movieProg.pos > 15000;
        if (details != null && details.ext != null && !details.ext.isEmpty()) item.ext = details.ext;
        a.player.playMovie(item, resume ? movieProg.pos : 0);
    }

    private void playEpisode(Episode e, long pos) {
        a.player.playEpisodes(item, details.episodes, details.episodes.indexOf(e), pos);
    }

    private void toggleList() {
        final boolean on = !fav;
        App.bg(() -> {
            App.db.setFav(item.kind, item.id, on);
            App.main(() -> {
                a.toast(on ? "Added to My List" : "Removed from My List");
                a.dataChanged();
            });
        });
    }
}
