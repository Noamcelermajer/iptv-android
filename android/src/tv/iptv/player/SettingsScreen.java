package tv.iptv.player;

import android.net.Uri;
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
import java.util.Set;

/** The handful of things worth changing, plus what the subscription looks like. */
final class SettingsScreen extends Screen {
    private static final int REFRESH = 0, LANGS = 1, REGIONS = 2, ADULT = 3, HIDDEN = 4, FORMAT = 5, HISTORY = 6, SIGN_OUT = 7;
    private static final String[] LABELS = {"Refresh Library", "Movie & Series Languages", "Live TV Regions", "Adult Content",
            "Hidden Categories", "Live Stream Format", "Clear Watch History", "Sign Out"};
    private RList list;
    private TextView account;
    private final String[] trail = new String[LABELS.length];

    SettingsScreen(MainActivity a) { super(a); }

    @Override
    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.HORIZONTAL);

        list = new RList(a);
        list.cellH = App.dp(48);
        list.padStart = App.dp(6);
        list.padEnd = App.dp(Ui.EDGE_V);
        list.lead = App.dp(60);
        list.trail = App.dp(70);
        list.trap = View.FOCUS_LEFT | View.FOCUS_RIGHT | View.FOCUS_DOWN;
        list.setFade(Ui.BG, App.dp(24));
        list.setAdapter(new RList.Adapter() {
            @Override public int count() { return LABELS.length; }

            @Override public View create(RList l) { return new RowCell(a); }

            @Override public void bind(View v, int pos) {
                ((RowCell) v).set(null, false, null, LABELS[pos], null, trail[pos], Glyph.NONE, -1);
            }

            @Override public void state(View v, float f, boolean sel) { ((RowCell) v).state(f, false); }
        });
        list.listener = new RList.Listener() {
            @Override void onClick(int pos) { act(pos); }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(App.dp(460), -1);
        lp.leftMargin = App.dp(Ui.EDGE - 14);
        root.addView(list, lp);

        account = Ui.text(a, 13.5f, Ui.TEXT2, Ui.REG);
        account.setLineSpacing(App.dp(9), 1f);
        lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = App.dp(48);
        lp.rightMargin = App.dp(Ui.EDGE);
        lp.topMargin = App.dp(20);
        root.addView(account, lp);
        return root;
    }

    @Override
    View entry() { return list; }

    /** Shown in the first row while a library refresh is running. */
    void syncStatus(String text) {
        if (!built()) return;
        trail[REFRESH] = text;
        list.rebind(REFRESH);
    }

    private static String chosen(String key, boolean live) {
        Set<String> on = App.prefs.getStringSet(key, new HashSet<String>());
        if (on.isEmpty()) return "All";
        ArrayList<String> names = new ArrayList<>();
        for (String code : on) names.add(Groups.label(code, live));
        java.util.Collections.sort(names);
        return names.size() > 2 ? names.size() + " selected" : TextUtils.join(", ", names);
    }

    @Override
    void refresh() {
        dirty = false;
        App.bg(() -> {
            final int live = App.db.count(App.LIVE), vod = App.db.count(App.VOD), ser = App.db.count(App.SERIES);
            int h = 0;
            for (int k = 0; k < 3; k++) for (Cat c : App.db.cats(k, true)) if (c.hidden) h++;
            final int hidden = h;
            App.main(() -> {
                if (!a.syncing()) trail[REFRESH] = ago(App.prefs.getLong("synced", 0));
                trail[LANGS] = chosen("lang_on", false);
                trail[REGIONS] = chosen("region_on", true);
                trail[ADULT] = App.prefs.getBoolean("adult", false) ? "Shown" : "Hidden";
                trail[HIDDEN] = hidden == 0 ? "None" : String.valueOf(hidden);
                trail[FORMAT] = "ts".equals(a.liveFormat()) ? "MPEG-TS" : "HLS";
                list.rebind();
                account.setText(summary(live, vod, ser));
            });
        });
    }

    private static String ago(long when) {
        if (when <= 0) return "Never";
        long min = (System.currentTimeMillis() - when) / 60000;
        if (min < 2) return "Just now";
        if (min < 60) return min + " min ago";
        if (min < 48 * 60) return (min / 60) + " h ago";
        return (min / 1440) + " days ago";
    }

    private CharSequence summary(int live, int vod, int series) {
        StringBuilder sb = new StringBuilder();
        long exp = App.prefs.getLong("acct_exp", 0);
        String status = App.prefs.getString("acct_status", "");
        sb.append("SUBSCRIPTION\n");
        sb.append(status.isEmpty() ? "Active" : status);
        if (App.prefs.getBoolean("acct_trial", false)) sb.append(" (trial)");
        sb.append('\n');
        if (exp > 0) {
            long days = (exp * 1000 - System.currentTimeMillis()) / 86400000L;
            sb.append("Expires ").append(new SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(new Date(exp * 1000)));
            sb.append(days >= 0 ? "  ·  " + days + " days left" : "  ·  expired").append('\n');
        } else {
            sb.append("No expiry date\n");
        }
        int max = App.prefs.getInt("acct_max", 1);
        sb.append(max).append(max == 1 ? " screen at a time\n" : " screens at a time\n");
        String host = Uri.parse(Xtream.server()).getHost();
        sb.append("Server  ").append(host == null ? "" : host).append("\n\n");
        sb.append("LIBRARY\n");
        sb.append(Ui.count(live)).append(" channels\n");
        sb.append(Ui.count(vod)).append(" movies\n");
        sb.append(Ui.count(series)).append(" series\n\n");
        sb.append("IPTV 2.0");
        return sb;
    }

    private void act(int pos) {
        switch (pos) {
            case REFRESH:
                if (!a.syncing()) a.sync(false);
                break;
            case LANGS:
                a.groupSheet(false);
                break;
            case REGIONS:
                a.groupSheet(true);
                break;
            case ADULT: {
                final boolean show = !App.prefs.getBoolean("adult", false);
                App.prefs.edit().putBoolean("adult", show).apply();
                trail[ADULT] = show ? "Shown" : "Hidden";
                list.rebind(ADULT);
                App.bg(() -> {
                    App.db.applyGroups();
                    App.main(a::dataChanged);
                });
                break;
            }
            case HIDDEN:
                showHidden();
                break;
            case FORMAT: {
                String next = "ts".equals(a.liveFormat()) ? "m3u8" : "ts";
                App.prefs.edit().putString("live_fmt", next).apply();
                trail[FORMAT] = "ts".equals(next) ? "MPEG-TS" : "HLS";
                list.rebind(FORMAT);
                break;
            }
            case HISTORY: {
                List<Sheet.Option> o = new ArrayList<>();
                o.add(new Sheet.Option("Clear History", () -> App.bg(() -> {
                    App.db.clearHistory();
                    App.main(() -> {
                        a.toast("Watch history cleared");
                        a.dataChanged();
                    });
                })));
                o.add(new Sheet.Option("Cancel", null));
                a.sheet.show("Clear watch history?", "Removes resume points and recently watched channels.", o, 1);
                break;
            }
            default: {
                List<Sheet.Option> o = new ArrayList<>();
                o.add(new Sheet.Option("Sign Out", a::signOut));
                o.add(new Sheet.Option("Cancel", null));
                a.sheet.show("Sign out?", "Your favourites and watch history on this TV will be removed.", o, 1);
            }
        }
    }

    private void showHidden() {
        App.bg(() -> {
            final List<Sheet.Option> o = new ArrayList<>();
            final String[] kinds = {"Live TV", "Movies", "Series"};
            for (int k = 0; k < 3; k++) {
                final int kind = k;
                for (final Cat c : App.db.cats(k, true)) {
                    if (!c.hidden) continue;
                    o.add(new Sheet.Option(Groups.clean(kind, c.name), kinds[k], Glyph.NONE, () -> App.bg(() -> {
                        App.db.setCatFlags(kind, c.id, false, c.pinned);
                        App.main(() -> {
                            a.toast("“" + Groups.clean(kind, c.name) + "” is visible again");
                            a.dataChanged();
                        });
                    })));
                }
            }
            App.main(() -> {
                if (o.isEmpty()) {
                    a.toast("Nothing hidden. Hold OK on a category to hide it.");
                    return;
                }
                a.sheet.show("Hidden Categories", "Choose one to show it again.", o, 0);
            });
        });
    }
}
