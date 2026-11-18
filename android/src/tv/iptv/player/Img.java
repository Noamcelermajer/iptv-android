package tv.iptv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Small image pipeline: memory cache, disk cache, newest-request-first downloads. */
final class Img {
    interface Target {
        /** The URL this target currently wants; lets queued jobs drop out when a cell was recycled. */
        String wantedImage();

        void onImage(String url, Bitmap bitmap);
    }

    private static final class Job {
        final String url, key;
        final int w, h;
        final boolean opaque;
        final WeakReference<Target> target;

        Job(String url, String key, int w, int h, boolean opaque, Target t) {
            this.url = url;
            this.key = key;
            this.w = w;
            this.h = h;
            this.opaque = opaque;
            this.target = new WeakReference<>(t);
        }
    }

    private static LruCache<String, Bitmap> memory;
    private static File dir;
    private static final ArrayDeque<Job> queue = new ArrayDeque<>();
    /** url -> elapsedRealtime until which it is not retried; transient failures expire quickly. */
    private static final ConcurrentHashMap<String, Long> failed = new ConcurrentHashMap<>();
    private static final long RETRY_SOON = 30000L, RETRY_LATE = 10 * 60000L;

    private Img() {}

    static void init(Context c) {
        int budget = (int) Math.min(48L << 20, Runtime.getRuntime().maxMemory() / 5);
        memory = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
        };
        dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(Img::work, "img-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        App.bg(Img::trimDisk);
    }

    /** TMDB serves any size; asking for one close to what is drawn saves bandwidth and decode time. */
    static String sized(String url, int width) {
        if (url == null) return null;
        int i = url.indexOf("/t/p/");
        if (i < 0 || !url.contains("tmdb.org")) return url;
        int end = url.indexOf('/', i + 5);
        if (end < 0) return url;
        String size = width <= 200 ? "w185" : width <= 360 ? "w342" : width <= 520 ? "w500" : width <= 800 ? "w780" : "w1280";
        return url.substring(0, i + 5) + size + url.substring(end);
    }

    private static String key(String url, int w) { return w + "|" + url; }

    static boolean bad(String url) {
        if (url == null || url.length() < 8 || !url.startsWith("http")) return true;
        Long until = failed.get(url);
        if (until == null) return false;
        if (android.os.SystemClock.elapsedRealtime() < until) return true;
        failed.remove(url);
        return false;
    }

    /** Synchronous memory hit, or null. */
    static Bitmap cached(String url, int w) {
        return url == null ? null : memory.get(key(url, w));
    }

    /** Delivers on the main thread, immediately when cached. */
    static void load(String url, int w, int h, boolean opaque, Target t) {
        if (bad(url)) return;
        String key = key(url, w);
        Bitmap hit = memory.get(key);
        if (hit != null) {
            t.onImage(url, hit);
            return;
        }
        synchronized (queue) {
            queue.addFirst(new Job(url, key, w, h, opaque, t));
            while (queue.size() > 80) queue.removeLast();
            queue.notify();
        }
    }

    private static void work() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            Job job;
            synchronized (queue) {
                while (queue.isEmpty()) {
                    try {
                        queue.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                job = queue.pollFirst();
            }
            Target t = job.target.get();
            if (t == null || !job.url.equals(t.wantedImage())) continue;
            Bitmap b = memory.get(job.key);
            if (b == null) {
                // Undecodable, oversized or HTTP 4xx stays out for a while; timeouts, no network and OOM retry soon.
                long hold = RETRY_LATE;
                try {
                    b = fetch(job);
                } catch (Throwable e) {
                    b = null;
                    String m = e.getMessage();
                    boolean permanent = e instanceof IllegalStateException || (m != null && m.startsWith("HTTP 4"));
                    if (!permanent) hold = RETRY_SOON;
                }
                if (b == null) {
                    failed.put(job.url, android.os.SystemClock.elapsedRealtime() + hold);
                    continue;
                }
                memory.put(job.key, b);
            }
            final Bitmap result = b;
            final Job done = job;
            App.main(() -> {
                Target target = done.target.get();
                if (target != null && done.url.equals(target.wantedImage())) target.onImage(done.url, result);
            });
        }
    }

    private static File file(String url) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return new File(dir, sb.toString());
        } catch (Exception e) {
            return new File(dir, String.valueOf(url.hashCode()));
        }
    }

    private static Bitmap fetch(Job job) throws Exception {
        File f = file(job.url);
        if (!f.exists() || f.length() == 0) {
            File tmp = new File(dir, f.getName() + "." + Thread.currentThread().getId() + ".tmp");
            HttpURLConnection c = Xtream.open(job.url, 15000);
            try (InputStream in = Xtream.body(c); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > (12 << 20)) throw new IllegalStateException("too large");
                }
            } catch (Exception e) {
                tmp.delete();
                throw e;
            } finally {
                c.disconnect();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } else {
            f.setLastModified(System.currentTimeMillis());
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            f.delete();
            return null;
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= job.w && o.outHeight / (sample * 2) >= job.h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = job.opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) {
            f.delete();
            return null;
        }
        // Still much larger than drawn: shrink once so the cache holds more and drawing stays cheap.
        float scale = Math.max(job.w / (float) b.getWidth(), job.h / (float) b.getHeight());
        if (scale < 0.9f) {
            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)),
                    Math.max(1, Math.round(b.getHeight() * scale)), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    private static void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        long limit = 160L << 20;
        if (total < limit) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total < limit * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }
}
