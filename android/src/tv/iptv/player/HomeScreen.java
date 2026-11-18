package tv.iptv.player;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Home: live categories first, then what you were watching, then what is new and worth a look. */
final class HomeScreen extends Screen {
    private VScroll scroll;
    private TextView empty;
    private Rail live, resume, favorites, recent, movies, pickMovies, series, pickSeries, mine;
    private Rail[] rails;

    HomeScreen(MainActivity a) { super(a); }

    @Override
    protected View build() {
        FrameLayout root = new FrameLayout(a);
        scroll = new VScroll(a);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setPadding(0, 0, 0, App.dp(Ui.EDGE_V + 8));
        rails = new Rail[]{
                live = new Rail(a, "Live TV", Rail.CATS, scroll),
                resume = new Rail(a, "Continue Watching", Rail.RESUME, scroll),
                favorites = new Rail(a, "Favourite Channels", Rail.CHANNELS, scroll),
                recent = new Rail(a, "Recently Watched Channels", Rail.CHANNELS, scroll),
                movies = new Rail(a, "Latest Movies", Rail.POSTERS, scroll),
                pickMovies = new Rail(a, "Recommended Movies", Rail.POSTERS, scroll),
                series = new Rail(a, "Latest Series", Rail.POSTERS, scroll),
                pickSeries = new Rail(a, "Recommended Series", Rail.POSTERS, scroll),
                mine = new Rail(a, "My List", Rail.POSTERS, scroll)};
        for (Rail r : rails) column.addView(r.box, new LinearLayout.LayoutParams(-1, -2));
        scroll.setContent(column);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        empty = Ui.text(a, 15f, Ui.TEXT2, Ui.REG);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(App.dp(6), 1f);
        empty.setText("Nothing here yet.\nOpen Live TV, Movies or Series to start watching.");
        empty.setVisibility(View.GONE);
        root.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    @Override
    View entry() {
        for (Rail r : rails) if (r.shown()) return r.list;
        return null;
    }

    @Override
    void refresh() {
        dirty = false;
        App.bg(() -> {
            final List<Cat> cats = new ArrayList<>();
            if (!App.db.favIds(App.LIVE).isEmpty()) cats.add(new Cat(LiveScreen.FAV, "Favourites"));
            cats.addAll(App.db.topCats(App.LIVE, 24));
            final List<Prog> cont = App.db.continueWatching();
            final List<Item> fav = App.db.favs(App.LIVE);
            final List<Item> rec = App.db.recentChannels(20);
            final List<Item> list = new ArrayList<>(App.db.favs(App.VOD));
            list.addAll(App.db.favs(App.SERIES));
            final List<Item> mov = App.db.newest(App.VOD, 40);
            final List<Item> ser = App.db.newest(App.SERIES, 40);
            final List<Item> pm = App.db.recommended(App.VOD, 40);
            final List<Item> ps = App.db.recommended(App.SERIES, 40);
            App.main(() -> {
                boolean lost = live.set(cats, true);
                lost |= resume.set(cont, true);
                lost |= favorites.set(fav, true);
                lost |= recent.set(rec, true);
                lost |= movies.set(mov, true);
                lost |= pickMovies.set(pm, true);
                lost |= series.set(ser, true);
                lost |= pickSeries.set(ps, true);
                lost |= mine.set(list, true);
                View first = entry();
                empty.setVisibility(first == null ? View.VISIBLE : View.GONE);
                if (lost && visible) {
                    scroll.reset();
                    if (first != null) first.requestFocus(); else a.focusTabs();
                }
                a.contentReady();
            });
        });
    }
}
