package tv.iptv.player;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String[] TABS = {"Search", "Home", "Live TV", "Movies", "Series", "Settings"};
    private static final int SEARCH = 0, HOME = 1, LIVE = 2, SETTINGS = 5;
    private static final int TAB_H = 72;

    private FrameLayout content, stackLayer;
    private TabBar tabs;
    private TextView clock, status, toastView;
    private Screen[] screens;
    private int tab = -1;
    private final ArrayList<Screen> stack = new ArrayList<>();
    private boolean appVisible = true, leaving, syncing, focusWhenReady;
    private long backAt;
    PlayerController player;
    Sheet sheet;
    Gate gate;

    private final Runnable tabSwitch = () -> showTab(tabs.current(), true);
    private final Runnable toastHide = () -> toastView.animate().alpha(0f).setDuration(Ui.ms(260)).start();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date()));
            App.ui.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        App.init(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));
        immersive();

        FrameLayout root = new FrameLayout(this);
        content = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.topMargin = App.dp(TAB_H);
        root.addView(content, lp);

        tabs = new TabBar(this, TABS);
        tabs.listener = new TabBar.Listener() {
            @Override public void onTab(int index) {
                App.ui.removeCallbacks(tabSwitch);
                App.ui.postDelayed(tabSwitch, 130);
            }

            @Override public void onEnter() {
                App.ui.removeCallbacks(tabSwitch);
                showTab(tabs.current(), true);
                focusContent();
            }
        };
        root.addView(tabs, new FrameLayout.LayoutParams(-1, App.dp(TAB_H)));

        clock = Ui.text(this, 14f, Ui.TEXT2, Ui.MED);
        lp = new FrameLayout.LayoutParams(-2, App.dp(TAB_H), Gravity.END);
        lp.rightMargin = App.dp(Ui.EDGE);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(clock, lp);

        status = Ui.text(this, 12f, Ui.TEXT3, Ui.REG);
        lp = new FrameLayout.LayoutParams(-2, App.dp(TAB_H), Gravity.START);
        lp.leftMargin = App.dp(Ui.EDGE);
        status.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(status, lp);

        stackLayer = new FrameLayout(this);
        stackLayer.setVisibility(View.INVISIBLE);
        root.addView(stackLayer, new FrameLayout.LayoutParams(-1, -1));

        gate = new Gate(this);
        root.addView(gate.view, new FrameLayout.LayoutParams(-1, -1));

        player = new PlayerController(this);
        root.addView(player.view, new FrameLayout.LayoutParams(-1, -1));

        sheet = new Sheet(this);
        root.addView(sheet.layer, new FrameLayout.LayoutParams(-1, -1));

        toastView = Ui.text(this, 13.5f, Ui.TEXT, Ui.MED);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xF02A2A31);
        pill.setCornerRadius(App.dp(20));
        toastView.setBackground(pill);
        toastView.setPadding(App.dp(20), App.dp(11), App.dp(20), App.dp(11));
        toastView.setAlpha(0f);
        lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = App.dp(Ui.EDGE_V + 10);
        root.addView(toastView, lp);

        setContentView(root);
        buildScreens();
        tick.run();

        if (App.signedIn()) {
            enter();
        } else {
            setAppVisible(false);
            gate.showSignIn();
        }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onStop() {
        super.onStop();
        player.onStop();
    }

    @Override
    protected void onDestroy() {
        App.ui.removeCallbacks(tick);
        if (player != null) player.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------ start-up

    private void buildScreens() {
        content.removeAllViews();
        stackLayer.removeAllViews();
        stack.clear();
        tab = -1;
        screens = new Screen[]{new SearchScreen(this), new HomeScreen(this), new LiveScreen(this),
                new BrowseScreen(this, App.VOD), new BrowseScreen(this, App.SERIES), new SettingsScreen(this)};
        tabs.setCurrent(HOME, false);
    }

    private void enter() {
        App.bg(() -> {
            final int channels = App.db.count(App.LIVE);
            if (channels > 0) App.db.applyGroups();
            App.main(() -> {
                if (channels == 0) {
                    sync(true);
                    return;
                }
                showApp();
                long age = System.currentTimeMillis() - App.prefs.getLong("synced", 0);
                if (age > 12 * 3600000L) sync(false);
            });
        });
    }

    private void showApp() {
        gate.hide();
        setAppVisible(true);
        focusWhenReady = true;
        tabs.setCurrent(HOME, false);
        showTab(HOME, false);
        tabs.requestFocus();
    }

    /** Home calls this once its rails are filled so focus can start in the content, not on the tab bar. */
    void contentReady() {
        if (!focusWhenReady) return;
        focusWhenReady = false;
        if (tabs.isFocused() && tab == HOME && stack.isEmpty() && !player.active() && !gate.visible()) focusContent();
        // Ask once which languages matter; with 100,000+ titles in 30 languages this is what makes Home relevant.
        if (!App.prefs.getBoolean("asked_langs", false) && !player.active() && !gate.visible()) {
            App.prefs.edit().putBoolean("asked_langs", true).apply();
            App.ui.postDelayed(() -> { if (!player.active() && !sheet.visible()) groupSheet(false); }, 600);
        }
    }

    /** Multi-select of live regions or movie/series languages. An empty selection means everything. */
    void groupSheet(final boolean live) {
        App.bg(() -> {
            final List<Group> groups = App.db.groups(live);
            App.main(() -> showGroups(live, groups, true));
        });
    }

    private void showGroups(final boolean live, final List<Group> groups, boolean fresh) {
        final String key = live ? "region_on" : "lang_on";
        final java.util.Set<String> on = new java.util.HashSet<>(App.prefs.getStringSet(key, new java.util.HashSet<String>()));
        List<Sheet.Option> o = new ArrayList<>();
        Sheet.Option all = new Sheet.Option(live ? "All Regions" : "All Languages", null, on.isEmpty() ? Glyph.CHECK : Glyph.NONE,
                () -> setGroups(live, groups, new java.util.HashSet<String>()));
        all.keep = true;
        o.add(all);
        for (final Group g : groups) {
            Sheet.Option opt = new Sheet.Option(g.label, Ui.count(g.items), on.contains(g.code) ? Glyph.CHECK : Glyph.NONE, () -> {
                java.util.Set<String> next = new java.util.HashSet<>(on);
                if (!next.remove(g.code)) next.add(g.code);
                setGroups(live, groups, next);
            });
            opt.keep = true;
            o.add(opt);
        }
        if (fresh) {
            sheet.show(live ? "Live TV Regions" : "Languages",
                    live ? "Tick the regions to show in Live TV. Press Back when done."
                            : "Tick what Movies and Series should show. Press Back when done. Change it any time in Settings.", o, 0);
        } else {
            sheet.update(o);
        }
    }

    private void setGroups(final boolean live, final List<Group> groups, java.util.Set<String> next) {
        App.prefs.edit().putStringSet(live ? "region_on" : "lang_on", next).apply();
        showGroups(live, groups, false);
        App.bg(() -> {
            App.db.applyGroups();
            App.main(this::dataChanged);
        });
    }

    /** Home shortcut: jump to Live TV with a category already open. */
    void openLive(String categoryId) {
        ((LiveScreen) screens[LIVE]).openCategory(categoryId);
        App.ui.removeCallbacks(tabSwitch);
        tabs.setCurrent(LIVE, true);
        showTab(LIVE, true);
        tabs.requestFocus();
    }

    void onSignedIn() { sync(true); }

    boolean syncing() { return syncing; }

    String liveFormat() {
        return App.prefs.getString("live_fmt", App.prefs.getBoolean("acct_ts", true) ? "ts" : "m3u8");
    }

    /** Downloads the catalogue. Blocking shows a full-screen progress state; otherwise it runs quietly. */
    void sync(final boolean blocking) {
        if (syncing) return;
        syncing = true;
        final int g = Xtream.epoch;
        if (blocking) {
            setAppVisible(false);
            gate.showProgress("Loading your library", "Connecting…");
        } else {
            status.setText("Updating library…");
        }
        App.net(() -> {
            String error = null;
            try {
                Xtream.refreshAccount();
                Xtream.sync(g, (stage, n) -> App.main(() -> {
                    if (g != Xtream.epoch) return;
                    String text = stage + (n > 0 ? "   " + Ui.count(n) : "");
                    if (blocking) gate.setProgress(text);
                    else ((SettingsScreen) screens[SETTINGS]).syncStatus(text);
                }));
                App.db.applyGroups();
            } catch (Exception e) {
                Log.w(Xtream.TAG, "sync failed: " + e);
                error = "The server did not answer. Check your connection and try again.";
            }
            final String failed = error;
            App.main(() -> {
                if (g != Xtream.epoch) return;
                syncing = false;
                status.setText("");
                if (failed == null) {
                    Epg.clear();
                    dataChanged();
                    if (blocking) showApp();
                    else toast("Library updated");
                } else if (blocking) {
                    gate.showFailure("Could not load your library", failed);
                } else {
                    toast("Library update failed");
                    dataChanged();
                }
            });
        });
    }

    void signOut() {
        if (player.active()) player.close();
        // A sync still running belongs to the account being left; it must not write or report anything more.
        Xtream.epoch++;
        syncing = false;
        status.setText("");
        App.prefs.edit().remove("server").remove("user").remove("pass").remove("synced").remove("live_cat").apply();
        Epg.clear();
        App.bg(() -> App.db.wipe());
        buildScreens();
        setAppVisible(false);
        gate.showSignIn();
    }

    // ----------------------------------------------------------- navigation

    private Screen current() { return stack.isEmpty() ? (tab >= 0 ? screens[tab] : null) : stack.get(stack.size() - 1); }

    private void applyLayers() {
        boolean detail = !stack.isEmpty();
        int base = appVisible && !detail ? View.VISIBLE : View.INVISIBLE;
        content.setVisibility(base);
        tabs.setVisibility(base);
        clock.setVisibility(base);
        status.setVisibility(base);
        stackLayer.setVisibility(appVisible && (detail || leaving) ? View.VISIBLE : View.INVISIBLE);
        for (int i = 0; i < stack.size(); i++) {
            stack.get(i).view().setVisibility(i == stack.size() - 1 ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** The player hides everything beneath it so the GPU only composites video. */
    void setAppVisible(boolean visible) {
        appVisible = visible;
        applyLayers();
    }

    private void showTab(int i, boolean animate) {
        if (i == tab) return;
        Screen old = tab >= 0 ? screens[tab] : null;
        Screen s = screens[i];
        tab = i;
        if (old != null) {
            old.visible = false;
            old.onHide();
            old.view().animate().cancel();
            old.view().setVisibility(View.GONE);
        }
        View v = s.view();
        if (v.getParent() == null) content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setVisibility(View.VISIBLE);
        s.visible = true;
        if (s.dirty) s.refresh();
        s.onShow();
        if (animate) {
            v.setAlpha(0f);
            v.setTranslationY(App.dp(10));
            v.animate().alpha(1f).translationY(0).setDuration(Ui.ms(240)).setInterpolator(Ui.EASE).start();
        } else {
            v.setAlpha(1f);
            v.setTranslationY(0);
        }
    }

    void focusTabs() { tabs.requestFocus(); }

    void focusContent() {
        Screen s = current();
        View v = s == null ? null : s.entry();
        if (v == null || !v.requestFocus()) {
            if (stack.isEmpty()) tabs.requestFocus();
        } else {
            s.entered();
        }
    }

    void push(Screen s) {
        s.savedFocus = getCurrentFocus();
        stack.add(s);
        final View v = s.view();
        stackLayer.addView(v, new FrameLayout.LayoutParams(-1, -1));
        stackLayer.setVisibility(View.VISIBLE);
        s.visible = true;
        s.refresh();
        s.onShow();
        v.setAlpha(0f);
        v.setTranslationX(App.dp(28));
        v.animate().alpha(1f).translationX(0).setDuration(Ui.ms(260)).setInterpolator(Ui.EASE).withEndAction(this::applyLayers).start();
        View e = s.entry();
        if (e != null) e.requestFocus();
    }

    void pop() {
        if (stack.isEmpty()) return;
        final Screen s = stack.remove(stack.size() - 1);
        s.visible = false;
        s.onHide();
        leaving = true;
        applyLayers();
        Screen below = current();
        if (below != null && below.dirty) below.refresh();
        View f = s.savedFocus;
        if (f == null || !f.isAttachedToWindow() || !f.requestFocus()) focusContent();
        final View v = s.view();
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(0f).translationX(App.dp(28)).setDuration(Ui.ms(200)).withEndAction(() -> {
            stackLayer.removeView(v);
            leaving = false;
            applyLayers();
        }).start();
    }

    /** Marks every screen stale and reloads the ones on screen. */
    void dataChanged() {
        for (Screen s : screens) {
            s.dirty = true;
            if (s.built() && s.visible) s.refresh();
        }
        for (Screen s : stack) {
            s.dirty = true;
            if (s.visible) s.refresh();
        }
    }

    void onPlayerClosed(long liveChannelId) {
        if (liveChannelId >= 0 && tab == LIVE && stack.isEmpty() && screens[LIVE].built()) {
            ((LiveScreen) screens[LIVE]).focusChannel(liveChannelId);
        }
    }

    // ---------------------------------------------------------------- actions

    void toast(String text) {
        toastView.setText(text);
        toastView.animate().cancel();
        toastView.setTranslationY(App.dp(8));
        toastView.animate().alpha(1f).translationY(0).setDuration(Ui.ms(200)).setInterpolator(Ui.EASE).start();
        App.ui.removeCallbacks(toastHide);
        App.ui.postDelayed(toastHide, 2400);
    }

    void setFav(final Item it, final boolean on) {
        App.bg(() -> {
            App.db.setFav(it.kind, it.id, on);
            App.main(() -> {
                String list = it.kind == App.LIVE ? "Favourites" : "My List";
                toast(on ? "Added to " + list : "Removed from " + list);
                dataChanged();
            });
        });
    }

    void itemMenu(final Item it) {
        App.bg(() -> {
            final boolean fav = App.db.isFav(it.kind, it.id);
            App.main(() -> {
                String list = it.kind == App.LIVE ? "Favourites" : "My List";
                List<Sheet.Option> o = new ArrayList<>();
                o.add(new Sheet.Option(fav ? "Remove from " + list : "Add to " + list, () -> setFav(it, !fav)));
                o.add(new Sheet.Option("Cancel", null));
                sheet.show(it.name, null, o, 0);
            });
        });
    }

    void progMenu(final Prog p) {
        List<Sheet.Option> o = new ArrayList<>();
        o.add(new Sheet.Option("Remove from Continue Watching", () -> App.bg(() -> {
            App.db.removeProg(p);
            App.main(this::dataChanged);
        })));
        o.add(new Sheet.Option("Cancel", null));
        sheet.show(p.title, null, o, 0);
    }

    void categoryMenu(final int kind, final Cat c) {
        if (c.special()) return;
        List<Sheet.Option> o = new ArrayList<>();
        o.add(new Sheet.Option(c.pinned ? "Unpin from Top" : "Pin to Top", () -> App.bg(() -> {
            App.db.setCatFlags(kind, c.id, false, !c.pinned);
            App.main(this::dataChanged);
        })));
        o.add(new Sheet.Option("Hide Category", () -> App.bg(() -> {
            App.db.setCatFlags(kind, c.id, true, false);
            App.main(() -> {
                toast("Hidden. Bring it back in Settings.");
                dataChanged();
            });
        })));
        o.add(new Sheet.Option("Cancel", null));
        sheet.show(c.name, "Pinned categories stay at the top of the list.", o, 0);
    }

    /** One press from Continue Watching goes straight back into the film or episode. */
    void resume(final Prog p) {
        if (p.kind == App.VOD) {
            App.bg(() -> {
                Item found = App.db.item(App.VOD, p.id);
                if (found == null) {
                    found = new Item();
                    found.kind = App.VOD;
                    found.id = p.id;
                    found.name = p.title;
                    found.icon = p.icon;
                    found.ext = p.ext;
                }
                final Item it = found;
                App.main(() -> player.playMovie(it, p.pos));
            });
            return;
        }
        toast("Loading…");
        App.net(() -> {
            Item found = App.db.item(App.SERIES, p.sid);
            if (found == null) {
                found = new Item();
                found.kind = App.SERIES;
                found.id = p.sid;
                found.name = p.title;
                found.icon = p.icon;
            }
            final Item series = found;
            Details d = null;
            try {
                d = Xtream.details(series);
            } catch (Exception ignored) {
            }
            final Details details = d;
            App.main(() -> {
                if (details == null) {
                    toast("Could not load this series");
                    return;
                }
                for (int i = 0; i < details.episodes.size(); i++) {
                    if (details.episodes.get(i).id == p.id) {
                        player.playEpisodes(series, details.episodes, i, p.pos);
                        return;
                    }
                }
                push(new DetailScreen(this, series));
            });
        });
    }

    // ------------------------------------------------------------------- keys

    private void back() {
        if (sheet.visible()) {
            sheet.hide();
            return;
        }
        if (player.active()) {
            player.onBack();
            return;
        }
        if (gate.visible()) {
            finish();
            return;
        }
        if (!stack.isEmpty()) {
            if (!current().onBack()) pop();
            return;
        }
        if (!tabs.isFocused()) {
            Screen s = current();
            if (s == null || !s.onBack()) tabs.requestFocus();
            return;
        }
        if (tab != HOME) {
            tabs.setCurrent(HOME, true);
            App.ui.removeCallbacks(tabSwitch);
            showTab(HOME, true);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - backAt < 2500) {
            finish();
        } else {
            backAt = now;
            toast("Press Back again to exit");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) back();
            return true;
        }
        if (player.active() && !sheet.visible() && player.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }
}
