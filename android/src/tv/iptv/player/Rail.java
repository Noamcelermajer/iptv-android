package tv.iptv.player;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A titled horizontal row: posters, channel tiles, things to resume, or live-category shortcuts. */
final class Rail {
    static final int POSTERS = 0, CHANNELS = 1, RESUME = 2, CATS = 3;

    final MainActivity a;
    final int type;
    final String title;
    final LinearLayout box;
    final RList list;
    List<?> data = new ArrayList<>();

    Rail(final MainActivity a, String title, final int type, final VScroll scroll) {
        this.a = a;
        this.title = title;
        this.type = type;
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setVisibility(View.GONE);
        TextView header = Ui.text(a, 16f, Ui.TEXT, Ui.MED);
        header.setText(title);
        header.setPadding(App.dp(Ui.EDGE), App.dp(12), 0, 0);
        box.addView(header, new LinearLayout.LayoutParams(-1, -2));

        list = new RList(a);
        list.horizontal = true;
        list.gap = App.dp(type == CATS ? 10 : 14);
        list.padStart = App.dp(Ui.EDGE);
        list.padEnd = App.dp(Ui.EDGE);
        list.padCross = App.dp(12);
        list.bleed = App.dp(12);
        list.trail = App.dp(Ui.EDGE + 40);
        if (type == CATS) {
            list.cellW = App.dp(168);
            list.cellH = App.dp(44);
        } else if (type == CHANNELS) {
            list.cellW = App.dp(150);
            list.cellH = App.dp(84 + TileCell.LABEL);
        } else {
            list.cellW = App.dp(118);
            list.cellH = App.dp(177 + (type == RESUME ? PosterCell.LABEL2 : PosterCell.LABEL));
        }
        list.setAdapter(new RList.Adapter() {
            @Override public int count() { return data.size(); }

            @Override public View create(RList l) {
                if (type == CATS) return new ChipCell(a);
                return type == CHANNELS ? new TileCell(a) : new PosterCell(a, type == RESUME);
            }

            @Override public void bind(View v, int pos) {
                Object o = data.get(pos);
                if (type == CATS) {
                    Cat c = (Cat) o;
                    ((ChipCell) v).set(c.special() ? c.name : Groups.clean(App.LIVE, c.name));
                } else if (type == CHANNELS) {
                    Item it = (Item) o;
                    ((TileCell) v).set(it.name, it.icon);
                } else if (type == RESUME) {
                    Prog p = (Prog) o;
                    String sub = p.kind == App.EPISODE ? p.sub : App.duration(Math.max(60000, p.dur - p.pos)) + " left";
                    ((PosterCell) v).set(p.title, sub, p.icon, p.fraction());
                } else {
                    Item it = (Item) o;
                    ((PosterCell) v).set(it.name, null, it.icon, -1);
                }
            }

            @Override public void state(View v, float f, boolean sel) {
                if (type == CATS) ((ChipCell) v).state(f, false);
                else if (type == CHANNELS) ((TileCell) v).state(f);
                else ((PosterCell) v).state(f);
            }
        });
        list.listener = new RList.Listener() {
            @Override void onFocus(boolean focused) {
                if (focused) scroll.reveal(box, true);
            }

            @Override void onClick(int pos) { open(pos); }

            @Override boolean onLongClick(int pos) {
                Object o = data.get(pos);
                if (o instanceof Prog) a.progMenu((Prog) o);
                else if (o instanceof Cat) a.categoryMenu(App.LIVE, (Cat) o);
                else a.itemMenu((Item) o);
                return true;
            }
        };
        box.addView(list, new LinearLayout.LayoutParams(-1, list.cellH + 2 * list.padCross));
    }

    boolean shown() { return box.getVisibility() == View.VISIBLE; }

    /** Returns true when this rail held focus and is now empty, so the caller must move focus. */
    boolean set(List<?> items, boolean keepSelection) {
        boolean had = list.isFocused();
        data = items;
        box.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        list.reload(keepSelection);
        return had && items.isEmpty();
    }

    private void open(int pos) {
        Object o = data.get(pos);
        if (o instanceof Prog) {
            a.resume((Prog) o);
            return;
        }
        if (o instanceof Cat) {
            a.openLive(((Cat) o).id);
            return;
        }
        Item it = (Item) o;
        if (it.kind == App.LIVE) {
            ArrayList<Item> channels = new ArrayList<>();
            for (Object x : data) channels.add((Item) x);
            a.player.playLive(channels, pos, title);
        } else {
            a.push(new DetailScreen(a, it));
        }
    }
}
