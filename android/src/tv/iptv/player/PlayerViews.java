package tv.iptv.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** The strip shown when a channel is tuned: what it is, what is on, what is next. */
final class LiveBanner extends View {
    private final Pic pic = new Pic(this, false);
    private final RectF logo = new RectF();
    private final Paint shade = new Paint(), bar = new Paint(Paint.ANTI_ALIAS_FLAG), tile = Ui.fill(0x33FFFFFF);
    private final TextPaint numPaint = Ui.paint(14f, Ui.MED, Ui.TEXT2);
    private final TextPaint namePaint = Ui.paint(22f, Ui.BOLD, Ui.TEXT);
    private final TextPaint nowPaint = Ui.paint(15f, Ui.MED, Ui.TEXT);
    private final TextPaint smallPaint = Ui.paint(12.5f, Ui.REG, Ui.TEXT2);
    private final TextPaint hintPaint = Ui.paint(11.5f, Ui.REG, Ui.TEXT3);
    private final TextPaint clockPaint = Ui.paint(18f, Ui.MED, Ui.TEXT);
    private Item item;
    private String number = "", group = "";
    private Epg.Show now, next;

    LiveBanner(Context c) {
        super(c);
        clockPaint.setTextAlign(Paint.Align.RIGHT);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
    }

    void set(Item it, String number, String group) {
        item = it;
        this.number = number;
        this.group = group == null ? "" : group;
        pic.set(it.icon, App.dp(130), App.dp(74));
        Epg.Show[] shows = Epg.peek(it.id);
        now = Epg.now(shows);
        next = Epg.next(shows);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x00000000, 0xB3000000, 0xF2000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, shade);
        if (item == null) return;
        float left = App.dp(Ui.EDGE), right = w - App.dp(Ui.EDGE), bottom = h - App.dp(Ui.EDGE_V + 6);
        float boxW = App.dp(118), boxH = App.dp(66), top = bottom - boxH;
        logo.set(left, top, left + boxW, bottom);
        c.drawRoundRect(logo, App.dp(10), App.dp(10), tile);
        logo.inset(App.dp(14), App.dp(10));
        pic.fit(c, logo);

        float x = left + boxW + App.dp(20);
        float textW = right - x - App.dp(130);
        c.drawText(number + (group.isEmpty() ? "" : "   ·   " + group), x, top + App.dp(4), numPaint);
        c.drawText(Ui.fit(item.name, namePaint, textW), x, top + App.dp(30), namePaint);
        if (now != null) {
            String range = InfoPane.time(now.start) + " – " + InfoPane.time(now.end);
            float rw = smallPaint.measureText(range);
            c.drawText(range, x, top + App.dp(52), smallPaint);
            c.drawText(Ui.fit(now.title, nowPaint, textW - rw - App.dp(14)), x + rw + App.dp(14), top + App.dp(52), nowPaint);
            float th = App.dp(3), by = top + App.dp(62);
            bar.setColor(0x40FFFFFF);
            c.drawRoundRect(x, by, x + App.dp(260), by + th, th, th, bar);
            bar.setColor(Ui.TEXT);
            c.drawRoundRect(x, by, x + App.dp(260) * now.fraction(System.currentTimeMillis()), by + th, th, th, bar);
            if (next != null) {
                c.drawText(Ui.fit("Next  " + InfoPane.time(next.start) + "  " + next.title, smallPaint, textW - App.dp(280)),
                        x + App.dp(276), by + App.dp(5), smallPaint);
            }
        }
        c.drawText(InfoPane.time(System.currentTimeMillis()), right, top + App.dp(30), clockPaint);
        c.drawText("OK  Channels      Left  Last channel      Right  Options", right, bottom + App.dp(4), hintPaint);
    }
}

/** Timeline for movies and episodes. Left/right scrub, OK pauses. */
final class Scrub extends View {
    interface Host {
        void seekBy(int direction, int repeat);

        void toggle();
    }

    private final TextPaint timePaint = Ui.paint(12.5f, Ui.MED, Ui.TEXT);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long pos, dur;
    private float focus;
    private boolean paused;
    private ValueAnimator anim;
    Host host;

    Scrub(Context c) {
        super(c);
        setFocusable(true);
    }

    void set(long pos, long dur, boolean paused) {
        this.pos = pos;
        this.dur = dur;
        this.paused = paused;
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(focus, gain ? 1f : 0f);
        anim.setDuration(Ui.ms(160));
        anim.addUpdateListener(v -> {
            focus = (float) v.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (host == null) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                host.seekBy(-1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                host.seekBy(1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) host.toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && host != null) host.toggle();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f, w = getWidth();
        float icon = App.dp(13);
        Glyph.draw(c, paused ? Glyph.PLAY : Glyph.PAUSE, 0, cy - icon / 2f, icon, Ui.TEXT);
        String a = App.clock(pos), b = dur > 0 ? "-" + App.clock(Math.max(0, dur - pos)) : "";
        float ax = App.dp(28), aw = timePaint.measureText(a), bw = timePaint.measureText(b);
        timePaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(a, ax, cy + App.dp(4.5f), timePaint);
        c.drawText(b, w - bw, cy + App.dp(4.5f), timePaint);
        float x0 = ax + aw + App.dp(14), x1 = w - bw - App.dp(14);
        float th = App.dp(3) + App.dp(2) * focus;
        float f = dur > 0 ? Math.max(0f, Math.min(1f, pos / (float) dur)) : 0f;
        track.setColor(0x4DFFFFFF);
        c.drawRoundRect(x0, cy - th / 2f, x1, cy + th / 2f, th, th, track);
        track.setColor(Ui.FOCUS);
        float px = x0 + (x1 - x0) * f;
        c.drawRoundRect(x0, cy - th / 2f, px, cy + th / 2f, th, th, track);
        c.drawCircle(px, cy, App.dp(4) + App.dp(5) * focus, track);
    }
}
