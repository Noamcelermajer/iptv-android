package tv.iptv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

/**
 * A recycling list / grid built for a D-pad. The list itself holds Android focus and tracks a selected
 * index; cells are plain views that are told how focused they are (0..1) every frame. Scrolling and
 * focus both ease toward their targets, so holding a direction glides instead of stepping.
 */
final class RList extends ViewGroup {
    interface Adapter {
        int count();

        View create(RList list);

        void bind(View v, int pos);

        /** focus eases 0..1 while this list has focus and the cell is selected. */
        void state(View v, float focus, boolean selected);
    }

    static class Listener {
        void onSelect(int pos) {}

        void onClick(int pos) {}

        boolean onLongClick(int pos) { return false; }

        void onFocus(boolean focused) {}
    }

    private static final class Cell {
        final View v;
        int pos;
        float f;
        boolean sel;

        Cell(View v) { this.v = v; }
    }

    private static final float TAU_SCROLL = 0.085f, TAU_FOCUS = 0.055f;

    boolean horizontal;
    int cols = 1;
    /** Cell size in px. cellW 0 on a vertical list means "divide the width between the columns". */
    int cellW, cellH, gap, gapCross, padStart, padEnd, padCross;
    /** Space kept between the selected cell and the viewport edges; -1 uses the padding. */
    int lead = -1, trail = -1;
    /** How far cells may draw outside the list on the cross axis (room for focus scaling). */
    int bleed;
    /** Directions (View.FOCUS_*) in which focus may not leave this list. */
    int trap;

    interface Skip { boolean skip(int pos); }

    /** Rows the selection steps over (list dividers). Single-column lists only. */
    Skip skip;

    private Adapter adapter;
    Listener listener = new Listener();
    private int count, sel;
    private float scroll, target;
    private final SparseArray<Cell> cells = new SparseArray<>();
    private final ArrayList<Cell> pool = new ArrayList<>();
    private boolean animating, filling, longFired;
    private long lastFrame;
    private GradientDrawable fadeStart, fadeEnd;
    private int fadeSize;
    private final Choreographer.FrameCallback frame = this::onFrame;

    RList(Context c) {
        super(c);
        setFocusable(true);
        setClipChildren(false);
        setClipToPadding(false);
        setChildrenDrawingOrderEnabled(true);
        setWillNotDraw(true);
    }

    /** Cells never overlap, so fading the list needs no offscreen buffer. */
    @Override
    public boolean hasOverlappingRendering() { return false; }

    void setAdapter(Adapter a) {
        adapter = a;
        reload(false);
    }

    /** Soft edges where content scrolls out; colour must match what is behind the list. */
    void setFade(int color, int size) {
        fadeSize = size;
        GradientDrawable.Orientation a = horizontal ? GradientDrawable.Orientation.LEFT_RIGHT : GradientDrawable.Orientation.TOP_BOTTOM;
        GradientDrawable.Orientation b = horizontal ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.BOTTOM_TOP;
        fadeStart = new GradientDrawable(a, new int[]{color, color & 0xFFFFFF});
        fadeEnd = new GradientDrawable(b, new int[]{color, color & 0xFFFFFF});
    }

    int count() { return count; }

    int selected() { return sel; }

    /** Call after the adapter's data changed. */
    void reload(boolean keepSelection) {
        count = adapter == null ? 0 : adapter.count();
        if (!keepSelection) sel = 0;
        sel = Math.max(0, Math.min(sel, count - 1));
        sel = landable(sel, 1);
        recycleAll();
        if (!keepSelection) {
            scroll = 0;
            target = 0;
        }
        ensureVisible(false);
        fill();
        invalidate();
    }

    /** Nearest selectable row from pos in the given direction, or the other way when there is none. */
    private int landable(int pos, int dir) {
        if (skip == null || count == 0) return pos;
        int p = pos;
        while (p >= 0 && p < count && skip.skip(p)) p += dir;
        if (p >= 0 && p < count) return p;
        p = pos;
        while (p >= 0 && p < count && skip.skip(p)) p -= dir;
        return p >= 0 && p < count ? p : pos;
    }

    void select(int pos, boolean animate) {
        if (count == 0) return;
        pos = Math.max(0, Math.min(pos, count - 1));
        boolean changed = pos != sel;
        sel = pos;
        ensureVisible(animate);
        kick();
        if (changed) listener.onSelect(sel);
    }

    View viewAt(int pos) {
        Cell c = cells.get(pos);
        return c == null ? null : c.v;
    }

    /** Re-binds the attached cells in place, e.g. when programme data arrived. */
    void rebind() {
        if (adapter == null) return;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.valueAt(i);
            adapter.bind(c.v, c.pos);
            adapter.state(c.v, c.f, c.pos == sel);
        }
    }

    interface Visitor { void visit(View v, int pos); }

    /** Visits the cells currently on screen. */
    void each(Visitor visitor) {
        for (int i = 0; i < cells.size(); i++) visitor.visit(cells.valueAt(i).v, cells.keyAt(i));
    }

    void rebind(int pos) {
        Cell c = cells.get(pos);
        if (c != null && adapter != null) {
            adapter.bind(c.v, c.pos);
            adapter.state(c.v, c.f, c.pos == sel);
        }
    }

    // -------------------------------------------------------------- geometry

    private int cw() {
        if (cellW > 0) return cellW;
        return Math.max(1, (getWidth() - 2 * padCross - (cols - 1) * gapCross) / cols);
    }

    private int pitch() { return (horizontal ? cw() : cellH) + gap; }

    private int lines() { return horizontal ? count : (count + cols - 1) / cols; }

    private int viewport() { return horizontal ? getWidth() : getHeight(); }

    private float maxScroll() {
        if (count == 0) return 0;
        return Math.max(0, padStart + lines() * pitch() - gap + padEnd - viewport());
    }

    private int left(int pos) { return horizontal ? padStart + pos * pitch() : padCross + (pos % cols) * (cw() + gapCross); }

    private int top(int pos) { return horizontal ? padCross : padStart + (pos / cols) * pitch(); }

    private void ensureVisible(boolean animate) {
        int vp = viewport();
        if (count == 0 || vp <= 0) {
            target = 0;
            if (!animate) scroll = 0;
            return;
        }
        int line = horizontal ? sel : sel / cols;
        float s = padStart + line * pitch();
        float e = s + pitch() - gap;
        int before = lead >= 0 ? lead : padStart, after = trail >= 0 ? trail : padEnd;
        float t = target;
        if (s - before < t) t = s - before;
        if (e + after > t + vp) t = e + after - vp;
        target = Math.max(0, Math.min(t, maxScroll()));
        if (!animate) {
            scroll = target;
            applyScroll();
        }
    }

    private void applyScroll() {
        int s = Math.round(scroll);
        if (horizontal) scrollTo(s, 0); else scrollTo(0, s);
        fill();
    }

    private void recycleAll() {
        filling = true;
        for (int i = cells.size() - 1; i >= 0; i--) {
            Cell c = cells.valueAt(i);
            removeViewInLayout(c.v);
            pool.add(c);
        }
        cells.clear();
        filling = false;
    }

    /** Attaches the cells that intersect the viewport (plus one line either side) and recycles the rest. */
    private void fill() {
        int vp = viewport();
        if (adapter == null || vp <= 0 || getWidth() <= 0) return;
        filling = true;
        int p = pitch(), per = horizontal ? 1 : cols;
        int firstLine = Math.max(0, (int) Math.floor((scroll - padStart - p) / p));
        int lastLine = Math.min(lines() - 1, (int) Math.floor((scroll + vp - padStart + p) / p));
        int first = firstLine * per, last = Math.min(count - 1, (lastLine + 1) * per - 1);
        for (int i = cells.size() - 1; i >= 0; i--) {
            Cell c = cells.valueAt(i);
            if (c.pos < first || c.pos > last) {
                removeViewInLayout(c.v);
                cells.removeAt(i);
                pool.add(c);
            }
        }
        boolean focused = isFocused();
        int w = cw();
        for (int pos = first; pos <= last; pos++) {
            if (cells.get(pos) != null) continue;
            Cell c = pool.isEmpty() ? new Cell(adapter.create(this)) : pool.remove(pool.size() - 1);
            c.pos = pos;
            c.sel = pos == sel;
            c.f = focused && c.sel ? 1f : 0f;
            adapter.bind(c.v, pos);
            adapter.state(c.v, c.f, c.sel);
            addViewInLayout(c.v, -1, generateDefaultLayoutParams(), true);
            c.v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));
            int l = left(pos), t = top(pos);
            c.v.layout(l, t, l + w, t + cellH);
            cells.put(pos, c);
        }
        filling = false;
    }

    @Override
    public void requestLayout() {
        // Cells have a fixed size, so nothing a cell does can change this list's layout.
        if (!filling) super.requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        if (horizontal && MeasureSpec.getMode(hSpec) != MeasureSpec.EXACTLY) h = cellH + 2 * padCross;
        if (!horizontal && MeasureSpec.getMode(wSpec) != MeasureSpec.EXACTLY && cellW > 0) {
            w = cols * cellW + (cols - 1) * gapCross + 2 * padCross;
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (changed) {
            recycleAll();
            ensureVisible(false);
        }
        fill();
    }

    // ------------------------------------------------------------- animation

    private void kick() {
        if (animating || !isAttachedToWindow()) return;
        animating = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void onFrame(long nanos) {
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (nanos - lastFrame) / 1e9f);
        lastFrame = nanos;
        boolean busy = false;
        float diff = target - scroll;
        // Never trail the selection by more than part of a screen, or a held key outruns what is drawn.
        float maxLag = viewport() * 0.55f;
        if (Math.abs(diff) > maxLag) {
            scroll = target - Math.signum(diff) * maxLag;
            diff = target - scroll;
        }
        if (Math.abs(diff) > 0.5f) {
            scroll += diff * (1f - (float) Math.exp(-dt / TAU_SCROLL));
            busy = true;
            applyScroll();
        } else if (scroll != target) {
            scroll = target;
            applyScroll();
        }
        boolean focused = isFocused();
        float k = 1f - (float) Math.exp(-dt / TAU_FOCUS);
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.valueAt(i);
            boolean isSel = c.pos == sel;
            float goal = focused && isSel ? 1f : 0f;
            if (c.f != goal) {
                float d = goal - c.f;
                if (Math.abs(d) < 0.012f) c.f = goal; else {
                    c.f += d * k;
                    busy = true;
                }
                c.sel = isSel;
                adapter.state(c.v, c.f, isSel);
            } else if (c.sel != isSel) {
                c.sel = isSel;
                adapter.state(c.v, c.f, isSel);
            }
        }
        if (fadeStart != null) invalidate();
        if (busy && isAttachedToWindow()) {
            Choreographer.getInstance().postFrameCallback(frame);
        } else {
            animating = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        kick();
    }

    /** An empty list has nothing to highlight, so D-pad focus search must not land on it. */
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (count == 0) return;
        super.addFocusables(views, direction, focusableMode);
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        kick();
        listener.onFocus(gain);
        if (gain && count > 0) listener.onSelect(sel);
    }

    // --------------------------------------------------------------- drawing

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        // The selected cell draws last so its enlarged focus state overlaps its neighbours.
        Cell c = cells.get(sel);
        int si = c == null ? -1 : indexOfChild(c.v);
        if (si < 0) return i;
        if (i == childCount - 1) return si;
        return i >= si ? i + 1 : i;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int sx = getScrollX(), sy = getScrollY(), w = getWidth(), h = getHeight();
        int save = canvas.save();
        if (horizontal) canvas.clipRect(sx, sy - bleed, sx + w, sy + h + bleed);
        else canvas.clipRect(sx - bleed, sy, sx + w + bleed, sy + h);
        super.dispatchDraw(canvas);
        if (fadeStart != null) {
            float a = Math.min(1f, scroll / fadeSize), b = Math.min(1f, (maxScroll() - scroll) / fadeSize);
            if (a > 0.01f) {
                if (horizontal) fadeStart.setBounds(sx, sy - bleed, sx + fadeSize, sy + h + bleed);
                else fadeStart.setBounds(sx - bleed, sy, sx + w + bleed, sy + fadeSize);
                fadeStart.setAlpha(Math.round(255 * a));
                fadeStart.draw(canvas);
            }
            if (b > 0.01f) {
                if (horizontal) fadeEnd.setBounds(sx + w - fadeSize, sy - bleed, sx + w, sy + h + bleed);
                else fadeEnd.setBounds(sx - bleed, sy + h - fadeSize, sx + w + bleed, sy + h);
                fadeEnd.setAlpha(Math.round(255 * b));
                fadeEnd.draw(canvas);
            }
        }
        canvas.restoreToCount(save);
    }

    // ------------------------------------------------------------------ keys

    // ----------------------------------------------------------------- touch

    private float downX, downY, downScroll;
    private boolean dragging, pressed;
    private final Runnable longPress = () -> {
        if (!pressed || dragging) return;
        pressed = false;
        int pos = cellAt(downX, downY);
        if (pos < 0) return;
        requestFocus();
        select(pos, true);
        listener.onLongClick(pos);
    };

    private int cellAt(float x, float y) {
        float cx = x + getScrollX(), cy = y + getScrollY();
        for (int i = 0; i < cells.size(); i++) {
            View v = cells.valueAt(i).v;
            if (cx >= v.getLeft() && cx < v.getRight() && cy >= v.getTop() && cy < v.getBottom()) {
                int pos = cells.keyAt(i);
                return skip != null && skip.skip(pos) ? -1 : pos;
            }
        }
        return -1;
    }

    /** Phones: drag to scroll, tap to open, press and hold for the same menu as holding OK. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (count == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downScroll = scroll;
                dragging = false;
                pressed = true;
                removeCallbacks(longPress);
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float delta = horizontal ? e.getX() - downX : e.getY() - downY;
                float cross = horizontal ? e.getY() - downY : e.getX() - downX;
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (!dragging && Math.abs(delta) > slop && Math.abs(delta) > Math.abs(cross)) {
                    dragging = true;
                    removeCallbacks(longPress);
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    scroll = target = Math.max(0, Math.min(downScroll - delta, maxScroll()));
                    applyScroll();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (pressed && !dragging) {
                    int pos = cellAt(e.getX(), e.getY());
                    if (pos >= 0) {
                        requestFocus();
                        select(pos, true);
                        listener.onClick(pos);
                    }
                }
                pressed = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                pressed = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Lines moved per key event; grows while the key is held so long lists stay reachable. */
    private static int stride(int repeat) {
        return repeat < 14 ? 1 : repeat < 45 ? 2 : repeat < 90 ? 4 : 8;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (isOk(code)) {
            if (count == 0) return true;
            if (e.getRepeatCount() == 0) {
                longFired = false;
                e.startTracking();
            }
            return true;
        }
        int dir;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = FOCUS_DOWN; break;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return page(-1);
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return page(1);
            default:
                return super.onKeyDown(code, e);
        }
        int next = moved(dir, stride(e.getRepeatCount()));
        if (next != sel && skip != null) {
            int step = next > sel ? 1 : -1, p = next;
            while (p >= 0 && p < count && skip.skip(p)) p += step;
            next = p >= 0 && p < count ? p : sel;
        }
        if (next != sel) {
            select(next, true);
            return true;
        }
        // At an edge: swallow repeats so a held key never flies out of the list by accident.
        return e.getRepeatCount() > 0 || (trap & dir) == dir;
    }

    private boolean page(int sign) {
        if (horizontal || count == 0) return false;
        int rows = Math.max(1, viewport() / pitch() - 1);
        select(landable(Math.max(0, Math.min(count - 1, sel + sign * rows * cols)), sign), true);
        return true;
    }

    private int moved(int dir, int stride) {
        if (count == 0) return sel;
        if (horizontal) {
            if (dir == FOCUS_LEFT) return Math.max(0, sel - stride);
            if (dir == FOCUS_RIGHT) return Math.min(count - 1, sel + stride);
            return sel;
        }
        int col = sel % cols;
        switch (dir) {
            case FOCUS_LEFT: return col > 0 ? sel - 1 : sel;
            case FOCUS_RIGHT: return col < cols - 1 && sel + 1 < count ? sel + 1 : sel;
            case FOCUS_UP: {
                int n = sel - cols * stride;
                while (n < 0) n += cols;
                return n > sel ? sel : n;
            }
            default: {
                int n = sel + cols * stride;
                if (n < count) return n;
                // Partial last row: land on the final item instead of refusing to move.
                int lastLine = (count - 1) / cols;
                return sel / cols < lastLine ? count - 1 : sel;
            }
        }
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (isOk(code) && count > 0) {
            longFired = true;
            listener.onLongClick(sel);
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isOk(code)) {
            if (count > 0 && e.isTracking() && !e.isCanceled() && !longFired) listener.onClick(sel);
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
