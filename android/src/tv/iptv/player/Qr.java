package tv.iptv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.nio.charset.StandardCharsets;

/**
 * A small QR encoder: byte mode, error-correction level L, versions 1-5 (up to 106 bytes), which is
 * plenty for the pairing address. Those versions use a single Reed-Solomon block, so no interleaving.
 */
final class Qr {
    /** Data and error-correction codeword counts for versions 1..5 at level L. */
    private static final int[] DATA = {19, 34, 55, 80, 108}, ECC = {7, 10, 15, 20, 26};

    private Qr() {}

    /** Returns the module grid (true = dark), or null when the text is too long. */
    static boolean[][] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = 0;
        for (int v = 1; v <= 5; v++) {
            if (bytes.length + 2 <= DATA[v - 1]) {
                version = v;
                break;
            }
        }
        if (version == 0) return null;
        int dataLen = DATA[version - 1], eccLen = ECC[version - 1];

        // Mode (4 bits) + length (8 bits) + payload, then terminator and the standard pad bytes.
        byte[] data = new byte[dataLen];
        int bit = 0;
        bit = put(data, bit, 0x4, 4);
        bit = put(data, bit, bytes.length, 8);
        for (byte b : bytes) bit = put(data, bit, b & 0xFF, 8);
        bit = put(data, bit, 0, Math.min(4, dataLen * 8 - bit));
        bit = (bit + 7) / 8 * 8;
        for (int pad = 0xEC; bit < dataLen * 8; pad ^= 0xEC ^ 0x11) bit = put(data, bit, pad, 8);

        byte[] all = new byte[dataLen + eccLen];
        System.arraycopy(data, 0, all, 0, dataLen);
        System.arraycopy(remainder(data, divisor(eccLen)), 0, all, dataLen, eccLen);

        int size = 17 + 4 * version;
        boolean[][] m = new boolean[size][size], fixed = new boolean[size][size];
        for (int i = 0; i < size; i++) {
            set(m, fixed, 6, i, i % 2 == 0);
            set(m, fixed, i, 6, i % 2 == 0);
        }
        finder(m, fixed, 3, 3);
        finder(m, fixed, size - 4, 3);
        finder(m, fixed, 3, size - 4);
        if (version >= 2) {
            int c = size - 7;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) set(m, fixed, c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        format(m, fixed, 0);

        // Codewords snake upward and downward through column pairs, right to left, skipping fixed modules.
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (fixed[y][x]) continue;
                    boolean dark = i < all.length * 8 && ((all[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                    i++;
                    // Mask pattern 0.
                    m[y][x] = dark ^ ((x + y) % 2 == 0);
                }
            }
        }
        return m;
    }

    private static int put(byte[] out, int bit, int value, int count) {
        for (int i = count - 1; i >= 0; i--, bit++) {
            if (((value >>> i) & 1) != 0) out[bit >>> 3] |= (byte) (0x80 >>> (bit & 7));
        }
        return bit;
    }

    private static void set(boolean[][] m, boolean[][] fixed, int x, int y, boolean dark) {
        if (x < 0 || y < 0 || y >= m.length || x >= m.length) return;
        m[y][x] = dark;
        fixed[y][x] = true;
    }

    private static void finder(boolean[][] m, boolean[][] fixed, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(m, fixed, cx + dx, cy + dy, d != 2 && d != 4);
            }
        }
    }

    /** Level L with the given mask, BCH-protected, written in both required places. */
    private static void format(boolean[][] m, boolean[][] fixed, int mask) {
        int data = 1 << 3 | mask, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412, size = m.length;
        for (int i = 0; i <= 5; i++) set(m, fixed, 8, i, bit(bits, i));
        set(m, fixed, 8, 7, bit(bits, 6));
        set(m, fixed, 8, 8, bit(bits, 7));
        set(m, fixed, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(m, fixed, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(m, fixed, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(m, fixed, 8, size - 15 + i, bit(bits, i));
        set(m, fixed, 8, size - 8, true);
    }

    private static boolean bit(int value, int i) { return ((value >>> i) & 1) != 0; }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static byte[] divisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) mul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = mul(root, 2);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) mul(divisor[i] & 0xFF, factor);
        }
        return result;
    }
}

/** Draws a QR code as dark modules on a white rounded tile, with the quiet zone scanners need. */
final class QrView extends View {
    private final Paint paint = new Paint();
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean[][] grid;

    QrView(Context c) {
        super(c);
        tile.setColor(0xFFFFFFFF);
        paint.setColor(0xFF000000);
    }

    void setText(String text) {
        grid = text == null ? null : Qr.encode(text);
        setVisibility(grid == null ? GONE : VISIBLE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (grid == null) return;
        int n = grid.length, side = Math.min(getWidth(), getHeight());
        // Whole-pixel modules keep the edges sharp; four modules of margin on every side.
        int cell = side / (n + 8);
        if (cell < 1) return;
        int used = cell * (n + 8), ox = cell * 4, oy = cell * 4;
        c.drawRoundRect(0, 0, used, used, App.dp(10), App.dp(10), tile);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (grid[y][x]) c.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, paint);
            }
        }
    }
}
