package tv.iptv.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Async image state for a custom-drawn view, with a short fade when a download lands. */
final class Pic implements Img.Target {
    private final View host;
    private final boolean opaque;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF dst = new RectF();
    private String url;
    private Bitmap bmp;
    private BitmapShader shader;
    private long shownAt;
    private int w, h;
    /** gaveUp: a download failed for this url; asked: a retry was queued after the hold expired. */
    private boolean gaveUp, asked;

    Pic(View host, boolean opaque) {
        this.host = host;
        this.opaque = opaque;
    }

    void set(String url, int w, int h) {
        if (url != null && url.equals(this.url) && bmp != null) return;
        this.url = url;
        this.w = w;
        this.h = h;
        gaveUp = asked = false;
        bmp = null;
        shader = null;
        shownAt = 0;
        if (!Img.bad(url)) {
            Bitmap hit = Img.cached(url, w);
            if (hit != null) bmp = hit; else Img.load(url, w, h, opaque, this);
        }
        host.invalidate();
    }

    @Override
    public String wantedImage() { return url; }

    @Override
    public void onImage(String u, Bitmap b) {
        bmp = b;
        shader = null;
        shownAt = SystemClock.uptimeMillis();
        host.invalidate();
    }

    boolean has() { return bmp != null; }

    /** True once we know no picture is coming, so the caller can draw a text fallback. */
    boolean missing() {
        if (bmp != null) return false;
        if (Img.bad(url)) {
            gaveUp = true;
            asked = false;
            return true;
        }
        // The retry hold expired: ask again, but keep the text fallback until a picture really arrives.
        if (gaveUp && !asked) {
            asked = true;
            Img.load(url, w, h, opaque, this);
        }
        return gaveUp;
    }

    private int alpha() {
        if (shownAt == 0) return 255;
        float t = (SystemClock.uptimeMillis() - shownAt) / 240f;
        if (t >= 1f) {
            shownAt = 0;
            return 255;
        }
        host.postInvalidateOnAnimation();
        return Math.round(255 * t);
    }

    /** Centre-cropped into a rounded rectangle. */
    void cover(Canvas c, RectF r, float radius) {
        if (bmp == null) return;
        if (shader == null) shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float s = Math.max(r.width() / bmp.getWidth(), r.height() / bmp.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate(r.left + (r.width() - bmp.getWidth() * s) / 2f, r.top + (r.height() - bmp.getHeight() * s) / 2f);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        paint.setAlpha(alpha());
        c.drawRoundRect(r, radius, radius, paint);
    }

    /** Whole image, letterboxed inside the rectangle (channel logos). */
    void fit(Canvas c, RectF r) {
        if (bmp == null) return;
        float s = Math.min(r.width() / bmp.getWidth(), r.height() / bmp.getHeight());
        float w = bmp.getWidth() * s, h = bmp.getHeight() * s;
        dst.set(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f);
        paint.setShader(null);
        paint.setAlpha(alpha());
        c.drawBitmap(bmp, null, dst, paint);
    }
}

/** The few glyphs the interface needs, drawn as strokes so they stay crisp at any size. */
final class Glyph {
    static final int NONE = 0, CHECK = 1, HEART = 2, CHEVRON = 3, PLAY = 4, PLUS = 5, RESTART = 6, PAUSE = 7;
    private static final Path path = new Path();
    private static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Glyph() {}

    /** Draws inside a square of the given size whose top-left is (x, y). */
    static void draw(Canvas c, int glyph, float x, float y, float size, int color) {
        if (glyph == NONE) return;
        p.setColor(color);
        p.setStrokeWidth(Math.max(2f, size * 0.12f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.rewind();
        switch (glyph) {
            case CHECK:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(x + size * 0.14f, y + size * 0.54f);
                path.lineTo(x + size * 0.40f, y + size * 0.78f);
                path.lineTo(x + size * 0.86f, y + size * 0.24f);
                break;
            case CHEVRON:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(x + size * 0.36f, y + size * 0.18f);
                path.lineTo(x + size * 0.68f, y + size * 0.5f);
                path.lineTo(x + size * 0.36f, y + size * 0.82f);
                break;
            case PLUS:
                p.setStyle(Paint.Style.STROKE);
                path.moveTo(x + size * 0.5f, y + size * 0.14f);
                path.lineTo(x + size * 0.5f, y + size * 0.86f);
                path.moveTo(x + size * 0.14f, y + size * 0.5f);
                path.lineTo(x + size * 0.86f, y + size * 0.5f);
                break;
            case PLAY:
                p.setStyle(Paint.Style.FILL);
                path.moveTo(x + size * 0.22f, y + size * 0.1f);
                path.lineTo(x + size * 0.9f, y + size * 0.5f);
                path.lineTo(x + size * 0.22f, y + size * 0.9f);
                path.close();
                break;
            case PAUSE:
                p.setStyle(Paint.Style.FILL);
                path.addRect(x + size * 0.18f, y + size * 0.1f, x + size * 0.4f, y + size * 0.9f, Path.Direction.CW);
                path.addRect(x + size * 0.6f, y + size * 0.1f, x + size * 0.82f, y + size * 0.9f, Path.Direction.CW);
                break;
            case HEART:
                p.setStyle(Paint.Style.FILL);
                path.moveTo(x + size * 0.5f, y + size * 0.9f);
                path.cubicTo(x - size * 0.1f, y + size * 0.52f, x + size * 0.12f, y + size * 0.02f, x + size * 0.5f, y + size * 0.3f);
                path.cubicTo(x + size * 0.88f, y + size * 0.02f, x + size * 1.1f, y + size * 0.52f, x + size * 0.5f, y + size * 0.9f);
                path.close();
                break;
            case RESTART:
                p.setStyle(Paint.Style.STROKE);
                path.addArc(x + size * 0.14f, y + size * 0.14f, x + size * 0.86f, y + size * 0.86f, -60, 300);
                path.moveTo(x + size * 0.5f, y + size * 0.0f);
                path.lineTo(x + size * 0.72f, y + size * 0.17f);
                path.lineTo(x + size * 0.5f, y + size * 0.36f);
                break;
            default:
                return;
        }
        c.drawPath(path, p);
    }
}

/** Portrait artwork with a title beneath: movies, series, continue-watching. */
final class PosterCell extends View {
    static final int LABEL = 26, LABEL2 = 42;
    private final Pic pic = new Pic(this, true);
    private final RectF art = new RectF();
    private final TextPaint titlePaint = Ui.paint(12.5f, Ui.MED, Ui.TEXT2);
    private final TextPaint subPaint = Ui.paint(11.5f, Ui.REG, Ui.TEXT3);
    private final TextPaint holdPaint = Ui.paint(12.5f, Ui.MED, Ui.TEXT3);
    private final Paint fill = Ui.fill(Ui.FILL);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean twoLine;
    private final float radius = App.dp(9);
    private final TextPaint tagPaint = Ui.paint(9.5f, Ui.BOLD, Ui.TEXT);
    private String title = "", sub = "", shown = "", shownSub = "", tag = "";
    private float progress = -1, focus, tagW;
    private StaticLayout hold;

    PosterCell(Context c, boolean twoLine) {
        super(c);
        this.twoLine = twoLine;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(App.dp(2.5f));
    }

    /** name may carry the provider's language tag ("FR - Title"); it is shown as a small badge instead. */
    void set(String name, String sub, String url, float progress) {
        this.title = Names.title(name);
        this.tag = Names.tag(name);
        this.tagW = tag.isEmpty() ? 0 : tagPaint.measureText(tag);
        this.sub = sub == null ? "" : sub;
        this.progress = progress;
        hold = null;
        fitText();
        int w = Math.max(getWidth(), App.dp(120));
        pic.set(Img.sized(url, w), w, w * 3 / 2);
        invalidate();
    }

    void state(float f) {
        focus = f;
        float s = 1f + 0.085f * f;
        setScaleX(s);
        setScaleY(s);
        invalidate();
    }

    private void fitText() {
        shown = Ui.fit(title, titlePaint, getWidth());
        shownSub = Ui.fit(sub, subPaint, getWidth());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        art.set(0, 0, w, h - App.dp(twoLine ? LABEL2 : LABEL));
        setPivotX(w / 2f);
        setPivotY(art.height() / 2f);
        fitText();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!pic.has() || focus < 1f) c.drawRoundRect(art, radius, radius, fill);
        if (pic.has()) {
            pic.cover(c, art, radius);
        } else if (pic.missing() && !title.isEmpty()) {
            if (hold == null) {
                int w = Math.max(1, (int) art.width() - App.dp(20));
                hold = StaticLayout.Builder.obtain(title, 0, title.length(), holdPaint, w)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(4)
                        .setEllipsize(TextUtils.TruncateAt.END).build();
            }
            c.save();
            c.translate(App.dp(10), art.centerY() - hold.getHeight() / 2f);
            hold.draw(c);
            c.restore();
        }
        if (tagW > 0 && pic.has()) {
            float x = App.dp(6), y = App.dp(6), h = App.dp(15);
            bar.setColor(0xB3000000);
            c.drawRoundRect(x, y, x + tagW + App.dp(10), y + h, App.dp(4), App.dp(4), bar);
            c.drawText(tag, x + App.dp(5), y + App.dp(11), tagPaint);
        }
        if (progress >= 0) {
            float pad = App.dp(8), th = App.dp(3), y = art.bottom - pad - th;
            bar.setColor(0x66000000);
            c.drawRoundRect(pad, y, art.right - pad, y + th, th, th, bar);
            bar.setColor(0xFFFFFFFF);
            c.drawRoundRect(pad, y, pad + (art.width() - 2 * pad) * Math.max(0.03f, progress), y + th, th, th, bar);
        }
        if (focus > 0.01f) {
            ring.setColor(Ui.alpha(Ui.FOCUS, focus));
            c.drawRoundRect(art, radius, radius, ring);
        }
        titlePaint.setColor(Ui.mix(Ui.TEXT2, Ui.TEXT, focus));
        c.drawText(shown, 0, art.bottom + App.dp(17), titlePaint);
        if (twoLine && !shownSub.isEmpty()) c.drawText(shownSub, 0, art.bottom + App.dp(33), subPaint);
    }
}

/** 16:9 tile with a channel logo and its name beneath. */
final class TileCell extends View {
    static final int LABEL = 26;
    private final Pic pic = new Pic(this, false);
    private final RectF art = new RectF(), logo = new RectF();
    private final TextPaint titlePaint = Ui.paint(12.5f, Ui.MED, Ui.TEXT2);
    private final TextPaint holdPaint = Ui.paint(13f, Ui.MED, Ui.TEXT2);
    private final Paint fill = Ui.fill(Ui.FILL);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius = App.dp(9);
    private String title = "", shown = "", shownHold = "";
    private float focus;

    TileCell(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(App.dp(2.5f));
        holdPaint.setTextAlign(Paint.Align.CENTER);
    }

    void set(String title, String url) {
        this.title = title == null ? "" : title;
        fitText();
        pic.set(url, App.dp(110), App.dp(60));
        invalidate();
    }

    void state(float f) {
        focus = f;
        float s = 1f + 0.085f * f;
        setScaleX(s);
        setScaleY(s);
        invalidate();
    }

    private void fitText() {
        shown = Ui.fit(title, titlePaint, getWidth());
        shownHold = Ui.fit(title, holdPaint, getWidth() - App.dp(20));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        art.set(0, 0, w, h - App.dp(LABEL));
        logo.set(art);
        logo.inset(w * 0.2f, art.height() * 0.2f);
        setPivotX(w / 2f);
        setPivotY(art.height() / 2f);
        fitText();
    }

    @Override
    protected void onDraw(Canvas c) {
        fill.setColor(Ui.mix(Ui.FILL, Ui.FILL_HI, focus));
        c.drawRoundRect(art, radius, radius, fill);
        if (pic.has()) pic.fit(c, logo);
        else if (pic.missing()) c.drawText(shownHold, art.centerX(), art.centerY() + App.dp(5), holdPaint);
        if (focus > 0.01f) {
            ring.setColor(Ui.alpha(Ui.FOCUS, focus));
            c.drawRoundRect(art, radius, radius, ring);
        }
        titlePaint.setColor(Ui.mix(Ui.TEXT2, Ui.TEXT, focus));
        c.drawText(shown, 0, art.bottom + App.dp(17), titlePaint);
    }
}

/** A list row: optional number and logo, title, optional second line, optional trailing text / mark / progress. */
final class RowCell extends View {
    private final Pic pic = new Pic(this, false);
    private final RectF box = new RectF(), logo = new RectF();
    private final TextPaint leadPaint = Ui.paint(12f, Ui.REG, Ui.TEXT3);
    private final TextPaint titlePaint = Ui.paint(14.5f, Ui.MED, Ui.TEXT);
    private final TextPaint subPaint = Ui.paint(12f, Ui.REG, Ui.TEXT2);
    private final TextPaint trailPaint = Ui.paint(12.5f, Ui.REG, Ui.TEXT2);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius = App.dp(10);
    private String lead, title = "", sub = "", trail = "", shown = "", shownSub = "";
    private final TextPaint dividerPaint = Ui.paint(10.5f, Ui.MED, Ui.TEXT3);
    private boolean hasLogo, selected, dim, divider;
    private int mark;
    private float progress = -1, focus;

    RowCell(Context c) {
        super(c);
        leadPaint.setTextAlign(Paint.Align.RIGHT);
        trailPaint.setTextAlign(Paint.Align.RIGHT);
    }

    /** lead and logoUrl may be null to leave those columns out entirely. */
    void set(String lead, boolean hasLogo, String logoUrl, String title, String sub, String trail, int mark, float progress) {
        this.lead = lead;
        this.hasLogo = hasLogo;
        this.title = title == null ? "" : title;
        this.sub = sub == null ? "" : sub;
        this.trail = trail == null ? "" : trail;
        this.mark = mark;
        this.progress = progress;
        divider = false;
        if (hasLogo) pic.set(logoUrl, App.dp(60), App.dp(34));
        fitText();
        invalidate();
    }

    /** A non-selectable section label inside a list. */
    void setDivider(String text) {
        set(null, false, null, text == null ? "" : text.toUpperCase(), null, null, Glyph.NONE, -1);
        divider = true;
        dividerPaint.setLetterSpacing(0.1f);
        shown = Ui.fit(title, dividerPaint, getWidth() - App.dp(28));
    }

    void setDim(boolean dim) { this.dim = dim; }

    void state(float f, boolean selected) {
        focus = f;
        this.selected = selected;
        invalidate();
    }

    private float textLeft() {
        float x = App.dp(14);
        if (lead != null) x += App.dp(30);
        if (hasLogo) x += App.dp(lead != null ? 64 : 58);
        return x;
    }

    private float rightReserve() {
        float r = App.dp(14);
        if (mark != Glyph.NONE) r += App.dp(22);
        if (!trail.isEmpty()) r += trailPaint.measureText(trail) + App.dp(10);
        if (progress >= 0) r += App.dp(58);
        return r;
    }

    private void fitText() {
        float w = getWidth() - textLeft() - rightReserve();
        shown = Ui.fit(title, titlePaint, w);
        shownSub = Ui.fit(sub, subPaint, w);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        box.set(0, App.dp(2), w, h - App.dp(2));
        fitText();
        if (divider) shown = Ui.fit(title, dividerPaint, w - App.dp(28));
    }

    @Override
    protected void onDraw(Canvas c) {
        int h = getHeight(), w = getWidth();
        if (divider) {
            c.drawText(shown, App.dp(14), h - App.dp(12), dividerPaint);
            return;
        }
        int base = selected ? Ui.SELECTED : 0;
        int color = Ui.mix(base, Ui.FOCUS, focus);
        if ((color >>> 24) != 0) {
            bg.setColor(color);
            c.drawRoundRect(box, radius, radius, bg);
        }
        float x = App.dp(14), cy = h / 2f;
        if (lead != null) {
            leadPaint.setColor(Ui.mix(Ui.TEXT3, Ui.ON_FOCUS2, focus));
            c.drawText(lead, x + App.dp(24), cy + App.dp(4.5f), leadPaint);
            x += App.dp(34);
        }
        if (hasLogo) {
            logo.set(x, cy - App.dp(15), x + App.dp(48), cy + App.dp(15));
            pic.fit(c, logo);
        }
        float tx = textLeft();
        int main = dim ? Ui.TEXT3 : Ui.TEXT;
        titlePaint.setColor(Ui.mix(main, Ui.ON_FOCUS, focus));
        if (shownSub.isEmpty()) {
            c.drawText(shown, tx, cy + App.dp(5.2f), titlePaint);
        } else {
            c.drawText(shown, tx, cy - App.dp(2.5f), titlePaint);
            subPaint.setColor(Ui.mix(Ui.TEXT2, Ui.ON_FOCUS2, focus));
            c.drawText(shownSub, tx, cy + App.dp(14.5f), subPaint);
        }
        float rx = w - App.dp(14);
        if (mark != Glyph.NONE) {
            float size = App.dp(14);
            Glyph.draw(c, mark, rx - size, cy - size / 2f, size, Ui.mix(Ui.TEXT2, Ui.ON_FOCUS, focus));
            rx -= App.dp(22);
        }
        if (!trail.isEmpty()) {
            trailPaint.setColor(Ui.mix(Ui.TEXT2, Ui.ON_FOCUS2, focus));
            c.drawText(trail, rx, cy + App.dp(4.5f), trailPaint);
            rx -= trailPaint.measureText(trail) + App.dp(10);
        }
        if (progress >= 0) {
            float bw = App.dp(48), th = App.dp(3);
            bar.setColor(Ui.mix(0x33FFFFFF, 0x33000000, focus));
            c.drawRoundRect(rx - bw, cy - th / 2f, rx, cy + th / 2f, th, th, bar);
            bar.setColor(Ui.mix(Ui.TEXT, Ui.ON_FOCUS, focus));
            c.drawRoundRect(rx - bw, cy - th / 2f, rx - bw + bw * progress, cy + th / 2f, th, th, bar);
        }
    }
}

/** Landscape still with episode number, title and a two-line synopsis. */
final class EpisodeCell extends View {
    static final int TEXT_H = 84;
    private final Pic pic = new Pic(this, true);
    private final RectF art = new RectF();
    private final TextPaint capPaint = Ui.paint(10.5f, Ui.MED, Ui.TEXT3);
    private final TextPaint titlePaint = Ui.paint(13.5f, Ui.MED, Ui.TEXT);
    private final TextPaint plotPaint = Ui.paint(11.5f, Ui.REG, Ui.TEXT2);
    private final TextPaint bigPaint = Ui.paint(30f, Ui.BOLD, 0xFF3A3A42);
    private final Paint fill = Ui.fill(Ui.FILL);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius = App.dp(9);
    private String caption = "", title = "", plot = "", number = "", shownTitle = "", shownCap = "";
    private StaticLayout plotLayout;
    private float progress = -1, focus;
    private boolean watched;

    EpisodeCell(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(App.dp(2.5f));
        capPaint.setLetterSpacing(0.08f);
        bigPaint.setTextAlign(Paint.Align.CENTER);
    }

    void set(Episode e, float progress, boolean watched) {
        number = String.valueOf(e.num);
        caption = "EPISODE " + e.num + (e.durMs > 0 ? "  ·  " + App.duration(e.durMs).toUpperCase() : "");
        title = e.title == null ? "" : e.title;
        plot = e.plot == null ? "" : e.plot;
        this.progress = progress;
        this.watched = watched;
        plotLayout = null;
        fitText();
        int w = Math.max(getWidth(), App.dp(200));
        pic.set(Img.sized(e.image, w), w, w * 9 / 16);
        invalidate();
    }

    void state(float f) {
        focus = f;
        float s = 1f + 0.06f * f;
        setScaleX(s);
        setScaleY(s);
        invalidate();
    }

    private void fitText() {
        shownTitle = Ui.fit(title, titlePaint, getWidth());
        shownCap = Ui.fit(caption, capPaint, getWidth() - (watched ? App.dp(20) : 0));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        art.set(0, 0, w, h - App.dp(TEXT_H));
        setPivotX(w / 2f);
        setPivotY(art.height() / 2f);
        plotLayout = null;
        fitText();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRoundRect(art, radius, radius, fill);
        if (pic.has()) pic.cover(c, art, radius);
        else c.drawText(number, art.centerX(), art.centerY() + App.dp(11), bigPaint);
        if (progress >= 0) {
            float pad = App.dp(8), th = App.dp(3), y = art.bottom - pad - th;
            bar.setColor(0x66000000);
            c.drawRoundRect(pad, y, art.right - pad, y + th, th, th, bar);
            bar.setColor(0xFFFFFFFF);
            c.drawRoundRect(pad, y, pad + (art.width() - 2 * pad) * Math.max(0.03f, progress), y + th, th, th, bar);
        }
        if (focus > 0.01f) {
            ring.setColor(Ui.alpha(Ui.FOCUS, focus));
            c.drawRoundRect(art, radius, radius, ring);
        }
        float y = art.bottom + App.dp(17);
        c.drawText(shownCap, 0, y, capPaint);
        if (watched) Glyph.draw(c, Glyph.CHECK, getWidth() - App.dp(14), y - App.dp(10), App.dp(11), Ui.TEXT2);
        c.drawText(shownTitle, 0, y + App.dp(18), titlePaint);
        if (!plot.isEmpty()) {
            if (plotLayout == null) {
                plotLayout = StaticLayout.Builder.obtain(plot, 0, plot.length(), plotPaint, Math.max(1, getWidth()))
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(App.dp(1.5f), 1f).build();
            }
            c.save();
            c.translate(0, y + App.dp(25));
            plotLayout.draw(c);
            c.restore();
        }
    }
}

/** Pill with a centred label: season picker. */
final class ChipCell extends View {
    private final TextPaint paint = Ui.paint(13f, Ui.MED, Ui.TEXT);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String label = "", fitted;
    private int fittedW;
    private float focus;
    private boolean selected;

    ChipCell(Context c) {
        super(c);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    void set(String label) {
        this.label = label;
        fitted = null;
        invalidate();
    }

    void state(float f, boolean selected) {
        focus = f;
        this.selected = selected;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        box.set(0, 0, getWidth(), getHeight());
        bg.setColor(Ui.mix(selected ? Ui.SELECTED : 0x0FFFFFFF, Ui.FOCUS, focus));
        c.drawRoundRect(box, getHeight() / 2f, getHeight() / 2f, bg);
        paint.setColor(Ui.mix(selected ? Ui.TEXT : Ui.TEXT2, Ui.ON_FOCUS, focus));
        if (fitted == null || fittedW != getWidth()) {
            fittedW = getWidth();
            fitted = Ui.fit(label, paint, fittedW - App.dp(24));
        }
        c.drawText(fitted, getWidth() / 2f, getHeight() / 2f + App.dp(4.6f), paint);
    }
}

/** A standalone focusable pill button. */
final class Btn extends View {
    private final TextPaint paint = Ui.paint(14f, Ui.MED, Ui.TEXT);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String label;
    private int glyph;
    private float focus;
    private ValueAnimator anim;
    Runnable action;

    Btn(Context c, String label, int glyph, Runnable action) {
        super(c);
        this.label = label;
        this.glyph = glyph;
        this.action = action;
        setFocusable(true);
    }

    void set(String label, int glyph) {
        this.label = label;
        this.glyph = glyph;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = Math.round(paint.measureText(label)) + App.dp(40) + (glyph != Glyph.NONE ? App.dp(20) : 0);
        setMeasuredDimension(w, App.dp(40));
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(focus, gain ? 1f : 0f);
        anim.setDuration(Ui.ms(170));
        anim.setInterpolator(Ui.EASE);
        anim.addUpdateListener(a -> {
            focus = (float) a.getAnimatedValue();
            setScaleX(1f + 0.05f * focus);
            setScaleY(1f + 0.05f * focus);
            invalidate();
        });
        anim.start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.getRepeatCount() == 0) e.startTracking();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.isTracking() && !e.isCanceled() && action != null) action.run();
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && action != null) action.run();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        box.set(0, 0, getWidth(), getHeight());
        bg.setColor(Ui.mix(Ui.FILL_HI, Ui.FOCUS, focus));
        c.drawRoundRect(box, getHeight() / 2f, getHeight() / 2f, bg);
        int color = Ui.mix(Ui.TEXT, Ui.ON_FOCUS, focus);
        paint.setColor(color);
        float x = App.dp(20);
        if (glyph != Glyph.NONE) {
            float size = App.dp(12);
            Glyph.draw(c, glyph, x, getHeight() / 2f - size / 2f, size, color);
            x += App.dp(20);
        }
        c.drawText(label, x, getHeight() / 2f + App.dp(5), paint);
    }
}
