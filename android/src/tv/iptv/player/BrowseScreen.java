package tv.iptv.player;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Movies or Series: categories on the left, a poster grid on the right. */
final class BrowseScreen extends Screen {
    static final String MINE = "@fav", NEW = "@new";

    private final int kind;
    private RList cats, grid;
    private TextView heading;
    private List<Cat> catData = new ArrayList<>();
    private List<Item> items = new ArrayList<>();
    private String currentCat;
    private final Runnable loader = this::loadItems;

    BrowseScreen(MainActivity a, int kind) {
        super(a);
        this.kind = kind;
    }

    @Override
    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setClipChildren(false);

        cats = new RList(a);
        cats.cellH = App.dp(42);
        cats.padStart = App.dp(6);
        cats.padEnd = App.dp(Ui.EDGE_V);
        cats.lead = App.dp(90);
        cats.trail = App.dp(110);
        cats.trap = View.FOCUS_LEFT | View.FOCUS_DOWN;
        cats.setFade(Ui.BG, App.dp(28));
        cats.setAdapter(new RList.Adapter() {
            @Override public int count() { return catData.size(); }

            @Override public View create(RList l) { return new RowCell(a); }

            @Override public void bind(View v, int pos) {
                Cat c = catData.get(pos);
                ((RowCell) v).set(null, false, null, c.special() ? c.name : Groups.clean(kind, c.name), null,
                        c.count > 0 ? Ui.count(c.count) : null, Glyph.NONE, -1);
            }

            @Override public void state(View v, float f, boolean sel) { ((RowCell) v).state(f, sel); }
        });
        cats.listener = new RList.Listener() {
            @Override void onSelect(int pos) { pickCategory(pos, 170); }

            @Override void onClick(int pos) { if (grid.count() > 0) grid.requestFocus(); }

            @Override boolean onLongClick(int pos) {
                a.categoryMenu(kind, catData.get(pos));
                return true;
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(App.dp(224), -1);
        lp.leftMargin = App.dp(Ui.EDGE - 14);
        root.addView(cats, lp);

        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        heading = Ui.text(a, 13f, Ui.TEXT3, Ui.MED);
        heading.setPadding(App.dp(10), App.dp(8), 0, App.dp(6));
        right.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        int width = a.getResources().getDisplayMetrics().widthPixels - App.dp(Ui.EDGE - 14 + 224 + 14 + Ui.EDGE - 10);
        grid = new RList(a);
        grid.cols = 5;
        grid.padCross = App.dp(10);
        grid.gapCross = App.dp(16);
        grid.gap = App.dp(12);
        grid.padStart = App.dp(10);
        grid.padEnd = App.dp(Ui.EDGE_V);
        grid.lead = App.dp(34);
        grid.trail = App.dp(40);
        grid.bleed = App.dp(10);
        int cell = (width - 2 * grid.padCross - 4 * grid.gapCross) / 5;
        grid.cellH = cell * 3 / 2 + App.dp(PosterCell.LABEL);
        grid.trap = View.FOCUS_RIGHT | View.FOCUS_DOWN;
        grid.setFade(Ui.BG, App.dp(24));
        grid.setAdapter(new RList.Adapter() {
            @Override public int count() { return items.size(); }

            @Override public View create(RList l) { return new PosterCell(a, false); }

            @Override public void bind(View v, int pos) {
                Item it = items.get(pos);
                ((PosterCell) v).set(it.name, null, it.icon, -1);
            }

            @Override public void state(View v, float f, boolean sel) { ((PosterCell) v).state(f); }
        });
        grid.listener = new RList.Listener() {
            @Override void onClick(int pos) { a.push(new DetailScreen(a, items.get(pos))); }

            @Override boolean onLongClick(int pos) {
                a.itemMenu(items.get(pos));
                return true;
            }
        };
        right.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = App.dp(14);
        lp.rightMargin = App.dp(Ui.EDGE - 10);
        root.addView(right, lp);
        return root;
    }

    @Override
    View entry() { return grid.count() > 0 ? grid : cats.count() > 0 ? cats : null; }

    @Override
    void refresh() {
        dirty = false;
        App.bg(() -> {
            final ArrayList<Cat> list = new ArrayList<>();
            int fav = App.db.favIds(kind).size();
            if (fav > 0) {
                Cat c = new Cat(MINE, "My List");
                c.count = fav;
                list.add(c);
            }
            list.add(new Cat(NEW, "Recently Added"));
            list.addAll(App.db.cats(kind, false));
            App.main(() -> {
                String want = currentCat != null ? currentCat : NEW;
                catData = list;
                int index = 0;
                for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(want)) index = i;
                int keep = grid.selected();
                boolean same = list.get(index).id.equals(currentCat);
                cats.reload(false);
                cats.select(index, false);
                currentCat = null;
                pickCategory(index, 0);
                if (same) pendingSelect = keep;
            });
        });
    }

    private int pendingSelect = -1;
    private String loadedCat;

    private void pickCategory(int pos, int delay) {
        if (pos < 0 || pos >= catData.size()) return;
        String id = catData.get(pos).id;
        if (id.equals(currentCat)) return;
        currentCat = id;
        App.ui.removeCallbacks(loader);
        if (delay > 0) {
            grid.animate().alpha(0.3f).setDuration(Ui.ms(120)).start();
            App.ui.postDelayed(loader, delay);
        } else {
            loadItems();
        }
    }

    private void loadItems() {
        final String id = currentCat;
        if (id == null) return;
        App.bg(() -> {
            final List<Item> list = MINE.equals(id) ? App.db.favs(kind)
                    : NEW.equals(id) ? App.db.newest(kind, 150) : App.db.items(kind, id);
            App.main(() -> {
                if (!id.equals(currentCat)) return;
                items = list;
                boolean same = id.equals(loadedCat);
                loadedCat = id;
                grid.reload(same);
                if (pendingSelect >= 0) grid.select(Math.min(pendingSelect, list.size() - 1), false);
                pendingSelect = -1;
                grid.animate().alpha(1f).setDuration(Ui.ms(180)).start();
                String name = "";
                for (Cat c : catData) if (c.id.equals(id)) name = c.special() ? c.name : Groups.clean(kind, c.name);
                heading.setText((name + "   " + Ui.count(list.size()) + (kind == App.VOD ? " movies" : " series")).toUpperCase());
                if (list.isEmpty() && grid.isFocused()) cats.requestFocus();
            });
        });
    }
}
