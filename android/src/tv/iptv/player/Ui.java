package tv.iptv.player;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/** Visual language: one near-black canvas, white type, and a single solid-white focus state. */
final class Ui {
    static final int BG = 0xFF0C0C0F;
    static final int TEXT = 0xFFF5F5F7;
    static final int TEXT2 = 0xFFA6A6AD;
    static final int TEXT3 = 0xFF7C7C85;
    static final int FILL = 0xFF1B1B20;
    static final int FILL_HI = 0xFF26262D;
    static final int SELECTED = 0x24FFFFFF;
    static final int FOCUS = 0xFFF2F2F4;
    static final int ON_FOCUS = 0xFF101014;
    static final int ON_FOCUS2 = 0xFF50505A;

    /** Screen-edge margins that stay inside TV overscan. */
    static final int EDGE = 48, EDGE_V = 27;

    static final Typeface REG = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    static final TimeInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.25f, 1f);

    private Ui() {}

    static float sp(float v) { return v * App.density; }

    /** Converts a real-world duration into what an Animator needs under the system animation scale. */
    static long ms(int real) {
        float scale = android.os.Build.VERSION.SDK_INT >= 33 ? android.animation.ValueAnimator.getDurationScale() : 1f;
        return scale < 0.05f ? real : Math.round(real / scale);
    }

    static TextPaint paint(float sp, Typeface tf, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(sp(sp));
        p.setTypeface(tf);
        p.setColor(color);
        return p;
    }

    static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    static int mix(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    static int alpha(int color, float a) {
        return (Math.round((color >>> 24) * Math.max(0f, Math.min(1f, a))) << 24) | (color & 0xFFFFFF);
    }

    static String fit(String s, TextPaint p, float width) {
        if (s == null || s.isEmpty() || width <= 0) return "";
        return TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
    }

    static TextView text(Context c, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(sp));
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    static String count(int n) { return String.format(java.util.Locale.US, "%,d", n); }
}
