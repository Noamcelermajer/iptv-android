package tv.iptv.player;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Text fields that follow the app's focus rule: the focused control is solid white. */
final class Field {
    private Field() {}

    static EditText make(Context c, String hint, int action) {
        final EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.sp(15f));
        e.setPadding(App.dp(20), 0, App.dp(20), 0);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // The "visible password" input type switches to monospace; set the typeface afterwards.
        e.setTypeface(Ui.REG);
        e.setImeOptions(action | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(App.dp(22));
        e.setBackground(bg);
        final Runnable style = () -> {
            boolean f = e.isFocused();
            bg.setColor(f ? Ui.FOCUS : Ui.FILL_HI);
            e.setTextColor(f ? Ui.ON_FOCUS : Ui.TEXT);
            e.setHintTextColor(f ? Ui.ON_FOCUS2 : Ui.TEXT3);
        };
        style.run();
        e.setOnFocusChangeListener((v, f) -> style.run());
        e.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (ev.getAction() == KeyEvent.ACTION_UP) keyboard(e, true);
                return true;
            }
            return false;
        });
        return e;
    }

    static void keyboard(EditText e, boolean show) {
        InputMethodManager imm = (InputMethodManager) e.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) imm.showSoftInput(e, 0); else imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }
}

/** Search across channels, movies and series; results update as you type. */
final class SearchScreen extends Screen {
    private EditText field;
    private VScroll scroll;
    private TextView hint;
    private Rail channels, movies, series;
    private int ticket;
    private final Runnable run = this::search;

    SearchScreen(MainActivity a) { super(a); }

    @Override
    protected View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);

        field = Field.make(a, "Search channels, movies and series", EditorInfo.IME_ACTION_SEARCH);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override public void afterTextChanged(Editable s) {
                App.ui.removeCallbacks(run);
                App.ui.postDelayed(run, 260);
            }
        });
        field.setOnEditorActionListener((v, id, ev) -> {
            Field.keyboard(field, false);
            App.ui.removeCallbacks(run);
            search();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(App.dp(520), App.dp(44));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = App.dp(6);
        root.addView(field, lp);

        FrameLayout body = new FrameLayout(a);
        scroll = new VScroll(a);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setPadding(0, 0, 0, App.dp(Ui.EDGE_V + 8));
        channels = new Rail(a, "Channels", Rail.CHANNELS, scroll);
        movies = new Rail(a, "Movies", Rail.POSTERS, scroll);
        series = new Rail(a, "Series", Rail.POSTERS, scroll);
        column.addView(channels.box, new LinearLayout.LayoutParams(-1, -2));
        column.addView(movies.box, new LinearLayout.LayoutParams(-1, -2));
        column.addView(series.box, new LinearLayout.LayoutParams(-1, -2));
        scroll.setContent(column);
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        hint = Ui.text(a, 14f, Ui.TEXT3, Ui.REG);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(App.dp(6), 1f);
        hint.setText("Press OK to type.\nYou can also dictate with the microphone key on the keyboard.");
        body.addView(hint, new FrameLayout.LayoutParams(-1, -1));
        lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = App.dp(6);
        root.addView(body, lp);
        return root;
    }

    @Override
    View entry() { return field; }

    /** Coming down from the tab bar means "I want to type", so open the keyboard straight away. */
    @Override
    void entered() {
        if (field.isFocused()) App.ui.postDelayed(() -> { if (visible && field.isFocused()) Field.keyboard(field, true); }, 120);
    }

    @Override
    void onHide() { if (field != null) Field.keyboard(field, false); }

    @Override
    void refresh() {
        // Favourites and progress do not change search results, so there is nothing to reload here.
        dirty = false;
    }

    private void search() {
        final String q = field.getText().toString().trim();
        final int mine = ++ticket;
        if (q.length() < 2) {
            channels.set(new java.util.ArrayList<Item>(), false);
            movies.set(new java.util.ArrayList<Item>(), false);
            series.set(new java.util.ArrayList<Item>(), false);
            scroll.reset();
            hint.setText("Press OK to type.\nYou can also dictate with the microphone key on the keyboard.");
            hint.setVisibility(View.VISIBLE);
            return;
        }
        App.bg(() -> {
            final List<Item> ch = App.db.search(App.LIVE, q, 40);
            final List<Item> mv = App.db.search(App.VOD, q, 40);
            final List<Item> se = App.db.search(App.SERIES, q, 40);
            App.main(() -> {
                if (mine != ticket) return;
                boolean lost = channels.set(ch, false);
                lost |= movies.set(mv, false);
                lost |= series.set(se, false);
                scroll.reset();
                boolean none = ch.isEmpty() && mv.isEmpty() && se.isEmpty();
                hint.setText("No results for “" + q + "”.");
                hint.setVisibility(none ? View.VISIBLE : View.GONE);
                if (lost) field.requestFocus();
            });
        });
    }
}
