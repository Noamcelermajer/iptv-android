package tv.iptv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** What is on a channel right now and next, drawn beside the channel list. */
final class InfoPane extends View {
    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", Locale.ROOT);
    private final Pic pic = new Pic(this, false);
    private final RectF logo = new RectF();
    private final TextPaint namePaint = Ui.paint(18f, Ui.BOLD, Ui.TEXT);
    private final TextPaint capPaint = Ui.paint(10.5f, Ui.MED, Ui.TEXT3);
    private final TextPaint titlePaint = Ui.paint(15f, Ui.MED, Ui.TEXT);
    private final TextPaint timePaint = Ui.paint(12f, Ui.REG, Ui.TEXT2);
    private final TextPaint bodyPaint = Ui.paint(12.5f, Ui.REG, Ui.TEXT2);
    private final TextPaint nextPaint = Ui.paint(13f, Ui.REG, Ui.TEXT);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Item item;
    private Epg.Show now;
    private final ArrayList<Epg.Show> later = new ArrayList<>();
    private StaticLayout nameLayout, titleLayout, bodyLayout;
    private boolean known;

    InfoPane(Context c) {
        super(c);
        capPaint.setLetterSpacing(0.08f);
    }

    static String time(long ms) { return HM.format(new Date(ms)); }

    void set(Item it) {
        item = it;
        nameLayout = titleLayout = bodyLayout = null;
        now = null;
        later.clear();
        known = false;
        if (it != null) {
            pic.set(it.icon, App.dp(120), App.dp(68));
            Epg.Show[] shows = Epg.peek(it.id);
            known = shows != null;
            now = Epg.now(shows);
            if (shows != null) {
                long t = System.currentTimeMillis();
                for (Epg.Show s : shows) if (s.start > t && later.size() < 3) later.add(s);
            }
        }
        invalidate();
    }

    private StaticLayout layout(String s, TextPaint p, int lines) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(1, getWidth()))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(App.dp(2), 1f).build();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { nameLayout = titleLayout = bodyLayout = null; }

    @Override
    protected void onDraw(Canvas c) {
        if (item == null) return;
        float y = App.dp(8);
        if (pic.has()) {
            logo.set(0, y, App.dp(104), y + App.dp(58));
            pic.fit(c, logo);
            y += App.dp(72);
        }
        if (nameLayout == null) nameLayout = layout(item.name, namePaint, 2);
        c.save();
        c.translate(0, y);
        nameLayout.draw(c);
        c.restore();
        y += nameLayout.getHeight() + App.dp(22);

        if (now == null) {
            c.drawText(known ? "No programme information" : "", 0, y, timePaint);
            return;
        }
        c.drawText("NOW", 0, y, capPaint);
        y += App.dp(8);
        if (titleLayout == null) titleLayout = layout(now.title, titlePaint, 2);
        c.save();
        c.translate(0, y);
        titleLayout.draw(c);
        c.restore();
        y += titleLayout.getHeight() + App.dp(18);
        c.drawText(time(now.start) + " – " + time(now.end), 0, y, timePaint);
        y += App.dp(10);
        float th = App.dp(3);
        bar.setColor(0x33FFFFFF);
        c.drawRoundRect(0, y, getWidth(), y + th, th, th, bar);
        bar.setColor(Ui.TEXT);
        c.drawRoundRect(0, y, getWidth() * now.fraction(System.currentTimeMillis()), y + th, th, th, bar);
        y += App.dp(14);
        int room = (int) ((getHeight() - y - App.dp(later.isEmpty() ? 8 : 34 + 22 * later.size())) / (bodyPaint.getTextSize() * 1.32f));
        if (now.desc != null && !now.desc.isEmpty() && room > 0) {
            if (bodyLayout == null) bodyLayout = layout(now.desc, bodyPaint, Math.min(7, room));
            c.save();
            c.translate(0, y);
            bodyLayout.draw(c);
            c.restore();
            y += bodyLayout.getHeight();
        }
        if (!later.isEmpty()) {
            y += App.dp(26);
            c.drawText("NEXT", 0, y, capPaint);
            for (Epg.Show s : later) {
                y += App.dp(22);
                c.drawText(time(s.start), 0, y, timePaint);
                c.drawText(Ui.fit(s.title, nextPaint, getWidth() - App.dp(46)), App.dp(46), y, nextPaint);
            }
        }
    }
}

/** Live TV: categories, channels, and what is on. Left and right move between the columns. */
final class LiveScreen extends Screen implements Epg.Listener {
    static final String FAV = "@fav", RECENT = "@recent", ALL = "@all";

    private RList cats, chans;
    private InfoPane info;
    private List<Cat> catData = new ArrayList<>();
    private List<Item> chanData = new ArrayList<>();
    private HashSet<Long> favs = new HashSet<>();
    private String currentCat;
    private long pendingFocus = -1;
    private boolean wantFocus;
    private String loadedCat;
    private final Runnable loader = this::loadChannels;
    private final Runnable epgSweep = this::sweepEpg;
    private final Runnable infoUpdate = this::updateInfo;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            chans.rebind();
            updateInfo();
            App.ui.postDelayed(this, 60000);
        }
    };

    LiveScreen(MainActivity a) { super(a); }

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
                ((RowCell) v).set(null, false, null, c.special() ? c.name : Groups.clean(App.LIVE, c.name), null,
                        c.count > 0 ? Ui.count(c.count) : null, Glyph.NONE, -1);
            }

            @Override public void state(View v, float f, boolean sel) { ((RowCell) v).state(f, sel); }
        });
        cats.listener = new RList.Listener() {
            @Override void onSelect(int pos) { pickCategory(pos, 150); }

            @Override void onClick(int pos) { if (chans.count() > 0) chans.requestFocus(); }

            @Override boolean onLongClick(int pos) {
                a.categoryMenu(App.LIVE, catData.get(pos));
                return true;
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(App.dp(224), -1);
        lp.leftMargin = App.dp(Ui.EDGE - 14);
        root.addView(cats, lp);

        chans = new RList(a);
        chans.cellH = App.dp(54);
        chans.padStart = App.dp(6);
        chans.padEnd = App.dp(Ui.EDGE_V);
        chans.lead = App.dp(110);
        chans.trail = App.dp(140);
        chans.trap = View.FOCUS_DOWN | View.FOCUS_RIGHT;
        chans.setFade(Ui.BG, App.dp(28));
        chans.skip = pos -> pos < chanData.size() && chanData.get(pos).divider();
        chans.setAdapter(new RList.Adapter() {
            @Override public int count() { return chanData.size(); }

            @Override public View create(RList l) { return new RowCell(a); }

            @Override public void bind(View v, int pos) {
                Item it = chanData.get(pos);
                if (it.divider()) {
                    ((RowCell) v).setDivider(Names.dividerText(it.name));
                    return;
                }
                Epg.Show now = Epg.now(Epg.peek(it.id));
                ((RowCell) v).set(String.valueOf(it.num > 0 ? it.num : pos + 1), true, it.icon, it.name,
                        now == null ? null : now.title, null, favs.contains(it.id) ? Glyph.HEART : Glyph.NONE,
                        now == null ? -1 : now.fraction(System.currentTimeMillis()));
            }

            @Override public void state(View v, float f, boolean sel) { ((RowCell) v).state(f, false); }
        });
        chans.listener = new RList.Listener() {
            @Override void onSelect(int pos) {
                App.ui.removeCallbacks(infoUpdate);
                App.ui.postDelayed(infoUpdate, 90);
                App.ui.removeCallbacks(epgSweep);
                App.ui.postDelayed(epgSweep, 260);
            }

            @Override void onClick(int pos) {
                Cat c = selectedCat();
                if (chanData.get(pos).divider()) return;
                a.player.playLive(new ArrayList<>(chanData), pos,
                        c == null ? "Live TV" : c.special() ? c.name : Groups.clean(App.LIVE, c.name));
            }

            @Override boolean onLongClick(int pos) {
                if (!chanData.get(pos).divider()) a.itemMenu(chanData.get(pos));
                return true;
            }
        };
        lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = App.dp(8);
        root.addView(chans, lp);

        LinearLayout side = new LinearLayout(a);
        side.setOrientation(LinearLayout.VERTICAL);
        info = new InfoPane(a);
        side.addView(info, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = Ui.text(a, 11.5f, Ui.TEXT3, Ui.REG);
        hint.setLineSpacing(App.dp(4), 1f);
        hint.setText("OK  Watch\nHold OK  Add to Favourites");
        side.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        lp = new LinearLayout.LayoutParams(App.dp(260), -1);
        lp.leftMargin = App.dp(26);
        lp.rightMargin = App.dp(Ui.EDGE);
        lp.bottomMargin = App.dp(Ui.EDGE_V);
        root.addView(side, lp);
        return root;
    }

    @Override
    View entry() { return chans.count() > 0 ? chans : cats.count() > 0 ? cats : null; }

    @Override
    void onShow() {
        Epg.addListener(this);
        App.ui.removeCallbacks(tick);
        App.ui.postDelayed(tick, 60000);
        App.ui.postDelayed(epgSweep, 300);
    }

    @Override
    void onHide() {
        Epg.removeListener(this);
        App.ui.removeCallbacks(tick);
    }

    private Cat selectedCat() {
        int i = cats.selected();
        return i >= 0 && i < catData.size() ? catData.get(i) : null;
    }

    @Override
    void refresh() {
        dirty = false;
        App.bg(() -> {
            final ArrayList<Cat> list = new ArrayList<>();
            Cat favourites = new Cat(FAV, "Favourites");
            favourites.count = App.db.favIds(App.LIVE).size();
            list.add(favourites);
            if (!App.db.recentChannels(1).isEmpty()) list.add(new Cat(RECENT, "Recently Watched"));
            Cat all = new Cat(ALL, "All Channels");
            list.add(all);
            for (Cat c : App.db.cats(App.LIVE, false)) {
                all.count += c.count;
                list.add(c);
            }
            App.main(() -> {
                String want = currentCat != null ? currentCat : App.prefs.getString("live_cat", "");
                catData = list;
                int index = -1;
                for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(want)) index = i;
                if (index < 0) {
                    index = 0;
                    for (int i = 0; i < list.size(); i++) {
                        if (!list.get(i).special()) {
                            index = i;
                            break;
                        }
                    }
                    if (list.get(0).count > 0) index = 0;
                }
                cats.reload(false);
                cats.select(index, false);
                currentCat = null;
                long keep = chans.count() > 0 && chans.selected() < chanData.size() ? chanData.get(chans.selected()).id : -1;
                if (pendingFocus < 0) pendingFocus = keep;
                pickCategory(index, 0);
            });
        });
    }

    private void pickCategory(int pos, int delay) {
        if (pos < 0 || pos >= catData.size()) return;
        String id = catData.get(pos).id;
        if (id.equals(currentCat)) return;
        currentCat = id;
        App.ui.removeCallbacks(loader);
        if (delay > 0) {
            chans.animate().alpha(0.35f).setDuration(Ui.ms(120)).start();
            App.ui.postDelayed(loader, delay);
        } else {
            loadChannels();
        }
    }

    private void loadChannels() {
        final String id = currentCat;
        if (id == null) return;
        App.prefs.edit().putString("live_cat", id).apply();
        App.bg(() -> {
            final List<Item> list = FAV.equals(id) ? App.db.favs(App.LIVE)
                    : RECENT.equals(id) ? App.db.recentChannels(40)
                    : ALL.equals(id) ? App.db.all(App.LIVE) : App.db.items(App.LIVE, id);
            final HashSet<Long> f = App.db.favIds(App.LIVE);
            App.main(() -> {
                if (!id.equals(currentCat)) return;
                chanData = list;
                favs = f;
                // Same category refreshed (favourite toggled, player closed): keep the scroll position.
                boolean same = id.equals(loadedCat);
                loadedCat = id;
                chans.reload(same);
                if (pendingFocus >= 0) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).id == pendingFocus) {
                            chans.select(i, false);
                            break;
                        }
                    }
                    pendingFocus = -1;
                }
                chans.animate().alpha(1f).setDuration(Ui.ms(180)).start();
                if (list.isEmpty() && chans.isFocused()) cats.requestFocus();
                if (wantFocus) {
                    wantFocus = false;
                    if (visible) (list.isEmpty() ? cats : chans).requestFocus();
                }
                updateInfo();
                App.ui.removeCallbacks(epgSweep);
                App.ui.postDelayed(epgSweep, 200);
            });
        });
    }

    /** Jumps to a category (from a Home shortcut) and puts focus in its channel list. */
    void openCategory(String id) {
        App.prefs.edit().putString("live_cat", id).apply();
        currentCat = null;
        pendingFocus = -1;
        wantFocus = true;
        if (built()) {
            chans.reload(false);
            refresh();
        }
    }

    /** Keeps the list on the channel the player ended on, so Back lands where the user expects. */
    void focusChannel(long id) {
        for (int i = 0; i < chanData.size(); i++) {
            if (chanData.get(i).id == id) {
                chans.select(i, false);
                return;
            }
        }
    }

    private void sweepEpg() {
        if (!visible) return;
        chans.each((v, pos) -> {
            if (pos < chanData.size()) Epg.want(chanData.get(pos).id);
        });
    }

    private void updateInfo() {
        int i = chans.selected();
        Item it = i >= 0 && i < chanData.size() ? chanData.get(i) : null;
        if (it != null && it.divider()) it = null;
        if (it != null) Epg.want(it.id);
        info.set(it);
    }

    @Override
    public void onEpg(long id) {
        if (!visible) return;
        chans.each((v, pos) -> {
            if (pos < chanData.size() && chanData.get(pos).id == id) chans.rebind(pos);
        });
        int i = chans.selected();
        if (i < chanData.size() && chanData.get(i).id == id) info.set(chanData.get(i));
    }
}
