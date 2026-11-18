package tv.iptv.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** One destination. Views are built lazily and kept, so switching tabs is instant. */
abstract class Screen {
    final MainActivity a;
    private View root;
    boolean dirty = true, visible;
    View savedFocus;

    Screen(MainActivity a) { this.a = a; }

    protected abstract View build();

    final View view() {
        if (root == null) root = build();
        return root;
    }

    final boolean built() { return root != null; }

    /** Reload data. Called when shown while dirty, and when data changes while visible. */
    void refresh() {}

    void onShow() {}

    void onHide() {}

    /** The view that takes focus when the user moves down from the tab bar. */
    View entry() { return view(); }

    /** Called after focus moved into this screen from the tab bar. */
    void entered() {}

    boolean onBack() { return false; }
}

/** The top navigation: a row of labels with one pill that slides to the current tab. */
final class TabBar extends View {
    interface Listener {
        void onTab(int index);

        void onEnter();
    }

    private final String[] labels;
    private final float[] left, width;
    private final TextPaint paint = Ui.paint(14f, Ui.MED, Ui.TEXT2);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tray = Ui.fill(0x12FFFFFF);
    private final RectF r = new RectF();
    private int current;
    private float pillX, pillW, focus;
    private ValueAnimator slide, glow;
    Listener listener;

    TabBar(Context c, String[] labels) {
        super(c);
        this.labels = labels;
        left = new float[labels.length];
        width = new float[labels.length];
        setFocusable(true);
    }

    int current() { return current; }

    void setCurrent(int i, boolean animate) {
        current = i;
        slideTo(animate);
    }

    private void slideTo(boolean animate) {
        if (slide != null) slide.cancel();
        final float fx = pillX, fw = pillW, tx = left[current], tw = width[current];
        if (!animate || fw == 0) {
            pillX = tx;
            pillW = tw;
            invalidate();
            return;
        }
        slide = ValueAnimator.ofFloat(0f, 1f);
        slide.setDuration(Ui.ms(260));
        slide.setInterpolator(Ui.EASE);
        slide.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            pillX = fx + (tx - fx) * t;
            pillW = fw + (tw - fw) * t;
            invalidate();
        });
        slide.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = App.dp(17), total = 0;
        for (int i = 0; i < labels.length; i++) {
            width[i] = paint.measureText(labels[i]) + 2 * pad;
            total += width[i];
        }
        float x = (w - total) / 2f;
        for (int i = 0; i < labels.length; i++) {
            left[i] = x;
            x += width[i];
        }
        slideTo(false);
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        if (glow != null) glow.cancel();
        glow = ValueAnimator.ofFloat(focus, gain ? 1f : 0f);
        glow.setDuration(Ui.ms(180));
        glow.addUpdateListener(a -> {
            focus = (float) a.getAnimatedValue();
            invalidate();
        });
        glow.start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int next = current + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (next >= 0 && next < labels.length) {
                    setCurrent(next, true);
                    if (listener != null) listener.onTab(next);
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0 && listener != null) listener.onEnter();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            for (int i = 0; i < labels.length; i++) {
                if (e.getX() >= left[i] && e.getX() < left[i] + width[i]) {
                    requestFocus();
                    setCurrent(i, true);
                    if (listener != null) listener.onTab(i);
                    break;
                }
            }
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = App.dp(34), top = (getHeight() - h) / 2f, inset = App.dp(4);
        r.set(left[0] - inset, top - inset, left[labels.length - 1] + width[labels.length - 1] + inset, top + h + inset);
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, tray);
        pill.setColor(Ui.mix(Ui.SELECTED, Ui.FOCUS, focus));
        r.set(pillX, top, pillX + pillW, top + h);
        c.drawRoundRect(r, h / 2f, h / 2f, pill);
        float base = top + h / 2f + App.dp(5);
        for (int i = 0; i < labels.length; i++) {
            // Text darkens in step with how much of the pill is under it, so the slide never shows dark-on-dark.
            float overlap = Math.max(0f, Math.min(left[i] + width[i], pillX + pillW) - Math.max(left[i], pillX)) / width[i];
            paint.setColor(Ui.mix(Ui.TEXT2, Ui.mix(Ui.TEXT, Ui.ON_FOCUS, focus), overlap));
            c.drawText(labels[i], left[i] + App.dp(17), base, paint);
        }
    }
}

/** Vertical scroller for a column of rails: the content glides so the focused rail sits near the top. */
final class VScroll extends ViewGroup {
    private View child;
    private int offset;

    VScroll(Context c) {
        super(c);
        setClipChildren(true);
    }

    void setContent(View v) {
        child = v;
        addView(v);
    }

    void reveal(View section, boolean animate) {
        if (child == null) return;
        int max = Math.max(0, child.getMeasuredHeight() - getHeight());
        int t = Math.max(0, Math.min(section.getTop() - App.dp(6), max));
        if (t == offset && animate) return;
        offset = t;
        child.animate().cancel();
        if (animate) child.animate().translationY(-t).setDuration(Ui.ms(340)).setInterpolator(Ui.EASE).start();
        else child.setTranslationY(-t);
    }

    private float touchY;
    private int touchOffset;
    private boolean scrolling;

    /** Takes over a touch once it is clearly a vertical drag; rails keep horizontal drags and taps. */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchY = e.getY();
            touchOffset = offset;
            scrolling = false;
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (Math.abs(e.getY() - touchY) > ViewConfiguration.get(getContext()).getScaledTouchSlop() * 2) scrolling = true;
        }
        return scrolling;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (child == null) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int max = Math.max(0, child.getMeasuredHeight() - getHeight());
            offset = Math.max(0, Math.min(Math.round(touchOffset - (e.getY() - touchY)), max));
            child.animate().cancel();
            child.setTranslationY(-offset);
        }
        return true;
    }

    void reset() {
        offset = 0;
        if (child != null) {
            child.animate().cancel();
            child.setTranslationY(0);
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        if (child != null) {
            child.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (child != null) child.layout(0, 0, r - l, child.getMeasuredHeight());
    }
}

/** A panel of choices that slides in from the right over whatever is on screen. */
final class Sheet {
    static final class Option {
        final String label, trail;
        final int mark;
        final Runnable run;
        /** Keep the sheet open after choosing (multi-select lists). */
        boolean keep;

        Option(String label, String trail, int mark, Runnable run) {
            this.label = label;
            this.trail = trail;
            this.mark = mark;
            this.run = run;
        }

        Option(String label, Runnable run) { this(label, null, Glyph.NONE, run); }
    }

    private final MainActivity a;
    final FrameLayout layer;
    private final LinearLayout panel;
    private final TextView title, note;
    private final RList list;
    private List<Option> options = new ArrayList<>();
    private View restore;
    private boolean open;

    Sheet(MainActivity a) {
        this.a = a;
        layer = new FrameLayout(a);
        layer.setVisibility(View.GONE);
        View scrim = new View(a);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x66000000, 0xCC000000}));
        layer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setOnClickListener(v -> hide());

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF17171B);
        panel.setPadding(App.dp(18), App.dp(Ui.EDGE_V + 12), App.dp(Ui.EDGE - 14), App.dp(Ui.EDGE_V));
        title = Ui.text(a, 20f, Ui.TEXT, Ui.BOLD);
        title.setPadding(App.dp(14), 0, 0, 0);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        note = Ui.text(a, 12.5f, Ui.TEXT2, Ui.REG);
        note.setPadding(App.dp(14), App.dp(4), 0, 0);
        panel.addView(note, new LinearLayout.LayoutParams(-1, -2));

        list = new RList(a);
        list.cellH = App.dp(46);
        list.padStart = App.dp(12);
        list.padEnd = App.dp(12);
        list.lead = App.dp(60);
        list.trail = App.dp(60);
        list.trap = View.FOCUS_LEFT | View.FOCUS_RIGHT | View.FOCUS_UP | View.FOCUS_DOWN;
        list.setFade(0xFF17171B, App.dp(18));
        list.setAdapter(new RList.Adapter() {
            @Override public int count() { return options.size(); }

            @Override public View create(RList l) { return new RowCell(a); }

            @Override public void bind(View v, int pos) {
                Option o = options.get(pos);
                ((RowCell) v).set(null, false, null, o.label, null, o.trail, o.mark, -1);
            }

            @Override public void state(View v, float f, boolean sel) { ((RowCell) v).state(f, false); }
        });
        list.listener = new RList.Listener() {
            @Override void onClick(int pos) {
                Option o = options.get(pos);
                if (!o.keep) hide();
                if (o.run != null) o.run.run();
            }
        };
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        layer.addView(panel, new FrameLayout.LayoutParams(App.dp(360), -1, Gravity.END));
    }

    boolean visible() { return open; }

    /** Swaps the rows of an open sheet in place, keeping the selection. */
    void update(List<Option> opts) {
        options = opts;
        list.reload(true);
    }

    void show(String heading, String sub, List<Option> opts, int selected) {
        options = opts;
        title.setText(heading);
        note.setText(sub == null ? "" : sub);
        note.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        list.reload(false);
        list.select(Math.max(0, selected), false);
        if (!open) {
            restore = a.getCurrentFocus();
            open = true;
            layer.setVisibility(View.VISIBLE);
            layer.setAlpha(0f);
            layer.animate().alpha(1f).setDuration(Ui.ms(200)).withEndAction(null).start();
            panel.setTranslationX(App.dp(80));
            panel.animate().translationX(0).setDuration(Ui.ms(280)).setInterpolator(Ui.EASE).start();
        }
        list.requestFocus();
    }

    void hide() {
        if (!open) return;
        open = false;
        View back = restore;
        restore = null;
        if (back == null || !back.isAttachedToWindow() || !back.requestFocus()) a.focusContent();
        layer.animate().alpha(0f).setDuration(Ui.ms(160)).withEndAction(() -> {
            if (!open) layer.setVisibility(View.GONE);
        }).start();
        panel.animate().translationX(App.dp(60)).setDuration(Ui.ms(160)).start();
    }
}
