package tv.iptv.player;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Full-screen playback. The platform decoder runs on its own thread so tuning and releasing a stream
 * never blocks the interface; everything on screen here is driven from the main thread.
 */
final class PlayerController implements SurfaceHolder.Callback, Epg.Listener, Scrub.Host {
    private static final int NONE = -1, LIVE = 0, MOVIE = 1, EPISODE = 2;
    private static final String[] ASPECTS = {"Fit", "Stretch", "Zoom"};

    private final MainActivity a;
    final FrameLayout view;
    private final SurfaceView surface;
    private final LiveBanner banner;
    private final LinearLayout bar;
    private final TextView vodTitle, vodSub, message, subtitles, zapTitle;
    private final Scrub scrub;
    private final Btn audioBtn, subsBtn, aspectBtn, nextBtn;
    private final FrameLayout zap;
    private final RList zapList;
    private final Spinner spinner;

    private final Handler ph;
    private volatile MediaPlayer mp;
    private volatile int gen;
    private boolean surfaceReady, prepared, paused, seeking, bannerShown, barShown, zapShown;
    private int mode = NONE;
    private boolean destroyed;
    private View restoreFocus;

    private List<Item> channels = new ArrayList<>();
    private int index, retries;
    private String group = "";
    private long previousId = -1;
    private boolean altFormat;

    private Item movie, show;
    private List<Episode> eps = new ArrayList<>();
    private int epIndex;

    private String url;
    private long pos, dur, seekTarget = -1, lastSave, seekStarted;
    private int videoW, videoH, aspect;

    private final Runnable tuner = () -> openCurrent();
    private final Runnable hideBanner = () -> setBanner(false);
    private final Runnable hideBar = () -> { if (!paused && seekTarget < 0) setBar(false); };
    private final Runnable hideZap = () -> setZap(false);
    private final Runnable commitSeek = this::commitSeek;
    private final Runnable markRecent = this::markRecent;
    private final Runnable spinnerOff = () -> spinnerShow(false);
    private final Runnable zapSweep = this::sweepEpg;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (mode == NONE) return;
            tick();
            App.ui.postDelayed(this, 500);
        }
    };

    PlayerController(final MainActivity a) {
        this.a = a;
        HandlerThread thread = new HandlerThread("player");
        thread.start();
        ph = new Handler(thread.getLooper());

        view = new FrameLayout(a);
        view.setBackgroundColor(Color.BLACK);
        view.setFocusable(true);
        view.setVisibility(View.GONE);
        // Touch: a tap on the picture brings up the channel list or the playback controls.
        view.setOnClickListener(v -> {
            if (mode == LIVE) setZap(!zapShown);
            else if (mode != NONE) setBar(!barShown);
        });

        surface = new SurfaceView(a);
        surface.getHolder().addCallback(this);
        view.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        subtitles = Ui.text(a, 19f, Ui.TEXT, Ui.MED);
        subtitles.setGravity(Gravity.CENTER);
        subtitles.setShadowLayer(App.dp(3), 0, App.dp(1), 0xFF000000);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(App.dp(760), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        fl.bottomMargin = App.dp(46);
        view.addView(subtitles, fl);

        banner = new LiveBanner(a);
        banner.setAlpha(0f);
        banner.setTranslationY(App.dp(16));
        view.addView(banner, new FrameLayout.LayoutParams(-1, App.dp(190), Gravity.BOTTOM));

        // Movie / episode controls.
        bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setClipChildren(false);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000, 0xF2000000}));
        bar.setPadding(App.dp(Ui.EDGE), App.dp(70), App.dp(Ui.EDGE), App.dp(Ui.EDGE_V));
        bar.setAlpha(0f);
        bar.setTranslationY(App.dp(16));
        bar.setVisibility(View.GONE);
        vodTitle = Ui.text(a, 22f, Ui.TEXT, Ui.BOLD);
        vodTitle.setMaxLines(1);
        bar.addView(vodTitle);
        vodSub = Ui.text(a, 13f, Ui.TEXT2, Ui.REG);
        vodSub.setMaxLines(1);
        vodSub.setPadding(0, App.dp(4), 0, 0);
        bar.addView(vodSub);
        scrub = new Scrub(a);
        scrub.host = this;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, App.dp(34));
        lp.topMargin = App.dp(8);
        bar.addView(scrub, lp);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        audioBtn = new Btn(a, "Audio", Glyph.NONE, () -> tracks(true));
        subsBtn = new Btn(a, "Subtitles", Glyph.NONE, () -> tracks(false));
        aspectBtn = new Btn(a, "Picture: Fit", Glyph.NONE, this::cycleAspect);
        nextBtn = new Btn(a, "Next Episode", Glyph.NONE, this::nextEpisode);
        row.addView(audioBtn);
        for (Btn b : new Btn[]{subsBtn, aspectBtn, nextBtn}) {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.leftMargin = App.dp(12);
            row.addView(b, bl);
        }
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = App.dp(10);
        bar.addView(row, lp);
        view.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Channel list over live video.
        zap = new FrameLayout(a);
        zap.setVisibility(View.GONE);
        zap.setAlpha(0f);
        View scrim = new View(a);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0xE6000000, 0x00000000}));
        zap.addView(scrim, new FrameLayout.LayoutParams(App.dp(620), -1));
        zapTitle = Ui.text(a, 12f, Ui.TEXT3, Ui.MED);
        zapTitle.setLetterSpacing(0.08f);
        fl = new FrameLayout.LayoutParams(App.dp(400), -2);
        fl.leftMargin = App.dp(Ui.EDGE);
        fl.topMargin = App.dp(Ui.EDGE_V + 4);
        zap.addView(zapTitle, fl);
        zapList = new RList(a);
        zapList.cellH = App.dp(54);
        zapList.padStart = App.dp(4);
        zapList.padEnd = App.dp(Ui.EDGE_V);
        zapList.lead = App.dp(120);
        zapList.trail = App.dp(150);
        zapList.trap = View.FOCUS_LEFT | View.FOCUS_RIGHT | View.FOCUS_UP | View.FOCUS_DOWN;
        zapList.setAdapter(new RList.Adapter() {
            @Override public int count() { return channels.size(); }

            @Override public View create(RList l) { return new RowCell(a); }

            @Override public void bind(View v, int p) {
                Item it = channels.get(p);
                Epg.Show now = Epg.now(Epg.peek(it.id));
                v.setTag(p);
                ((RowCell) v).set(number(p), true, it.icon, it.name, now == null ? null : now.title, null,
                        Glyph.NONE, now == null ? -1 : now.fraction(System.currentTimeMillis()));
            }

            @Override public void state(View v, float f, boolean sel) {
                Object tag = v.getTag();
                ((RowCell) v).state(f, tag instanceof Integer && (Integer) tag == index);
            }
        });
        zapList.listener = new RList.Listener() {
            @Override void onSelect(int p) {
                stay(hideZap, 12000);
                App.ui.removeCallbacks(zapSweep);
                App.ui.postDelayed(zapSweep, 260);
            }

            @Override void onClick(int p) {
                setZap(false);
                if (p != index) switchTo(p, 0);
            }
        };
        fl = new FrameLayout.LayoutParams(App.dp(430), -1);
        fl.leftMargin = App.dp(Ui.EDGE - 14);
        fl.topMargin = App.dp(Ui.EDGE_V + 26);
        zap.addView(zapList, fl);
        view.addView(zap, new FrameLayout.LayoutParams(-1, -1));

        spinner = new Spinner(a);
        spinner.setVisibility(View.GONE);
        view.addView(spinner, new FrameLayout.LayoutParams(App.dp(40), App.dp(40), Gravity.CENTER));

        message = Ui.text(a, 15f, Ui.TEXT, Ui.MED);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(App.dp(6), 1f);
        message.setVisibility(View.GONE);
        view.addView(message, new FrameLayout.LayoutParams(App.dp(520), -2, Gravity.CENTER));
    }

    boolean active() { return mode != NONE; }

    private String number(int p) {
        Item it = channels.get(p);
        return String.valueOf(it.num > 0 ? it.num : p + 1);
    }

    // ---------------------------------------------------------------- starts

    void playLive(List<Item> list, int at, String groupName) {
        // Dividers are for browsing only; zapping should never land on one.
        ArrayList<Item> playable = new ArrayList<>(list.size());
        long wanted = at >= 0 && at < list.size() ? list.get(at).id : -1;
        int start = 0;
        for (Item it : list) {
            if (it.divider()) continue;
            if (it.id == wanted) start = playable.size();
            playable.add(it);
        }
        if (destroyed || playable.isEmpty()) return;
        list = playable;
        at = start;
        begin(LIVE);
        channels = list;
        index = Math.max(0, Math.min(at, list.size() - 1));
        group = groupName == null ? "" : groupName;
        previousId = -1;
        zapTitle.setText(group.toUpperCase());
        zapList.reload(false);
        zapList.select(index, false);
        tune(0);
    }

    void playMovie(Item it, long resumeAt) {
        if (destroyed) return;
        begin(MOVIE);
        movie = it;
        vodTitle.setText(Names.title(it.name));
        vodSub.setVisibility(View.GONE);
        nextBtn.setVisibility(View.GONE);
        retries = 0;
        open(Xtream.vodUrl(it.id, it.ext), resumeAt);
        setBar(true);
    }

    void playEpisodes(Item series, List<Episode> list, int at, long resumeAt) {
        if (destroyed || list == null || at < 0 || at >= list.size()) return;
        begin(EPISODE);
        show = series;
        eps = list;
        startEpisode(at, resumeAt);
    }

    private void startEpisode(int at, long resumeAt) {
        // Track choices belong to the episode that is ending.
        a.sheet.hide();
        epIndex = at;
        Episode e = eps.get(at);
        vodTitle.setText(Names.title(show.name));
        vodSub.setText(e.label() + "   ·   " + e.title);
        vodSub.setVisibility(View.VISIBLE);
        // Hiding the focused button would drop focus outside the bar; hand it to the scrubber first.
        if (at + 1 >= eps.size() && nextBtn.hasFocus()) scrub.requestFocus();
        nextBtn.setVisibility(at + 1 < eps.size() ? View.VISIBLE : View.GONE);
        retries = 0;
        open(Xtream.episodeUrl(e.id, e.ext), resumeAt);
        setBar(true);
    }

    private void begin(int newMode) {
        boolean wasActive = mode != NONE;
        if (wasActive) saveProgress(false);
        mode = newMode;
        App.ui.removeCallbacks(tuner);
        App.ui.removeCallbacks(markRecent);
        setBanner(false);
        setZap(false);
        setBar(false);
        message.setVisibility(View.GONE);
        subtitles.setText("");
        videoW = videoH = 0;
        aspect = App.prefs.getInt("aspect", 0);
        aspectBtn.set("Picture: " + ASPECTS[aspect], Glyph.NONE);
        if (wasActive) return;
        restoreFocus = a.getCurrentFocus();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(Ui.ms(220)).withEndAction(() -> {
            if (mode != NONE) a.setAppVisible(false);
        }).start();
        view.requestFocus();
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Epg.addListener(this);
        App.ui.removeCallbacks(ticker);
        App.ui.postDelayed(ticker, 500);
    }

    /** Leaves playback and returns to whatever was underneath. */
    void close() {
        if (mode == NONE) return;
        final Prog progress = buildProgress(false);
        final boolean wasLive = mode == LIVE;
        final long liveId = wasLive && index < channels.size() ? channels.get(index).id : -1;
        mode = NONE;
        gen++;
        ph.post(this::releaseNow);
        for (Runnable r : new Runnable[]{tuner, hideBanner, hideBar, hideZap, commitSeek, markRecent, spinnerOff, ticker, zapSweep}) {
            App.ui.removeCallbacks(r);
        }
        Epg.removeListener(this);
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        a.setAppVisible(true);
        // A track sheet left open would point at the player being released.
        a.sheet.hide();
        View back = restoreFocus;
        restoreFocus = null;
        if (back != null && back.isAttachedToWindow() && back.isShown()) back.requestFocus(); else a.focusContent();
        view.animate().alpha(0f).setDuration(Ui.ms(200)).withEndAction(() -> {
            if (mode == NONE) view.setVisibility(View.GONE);
        }).start();
        a.onPlayerClosed(liveId);
        App.bg(() -> {
            if (progress != null) App.db.saveProg(progress);
            App.main(a::dataChanged);
        });
    }

    // ----------------------------------------------------------------- engine

    private void open(String target, long startAt) {
        url = target;
        pos = startAt;
        dur = 0;
        prepared = false;
        paused = false;
        seeking = false;
        seekTarget = -1;
        message.setVisibility(View.GONE);
        subtitles.setText("");
        spinnerShow(true);
        final int g = ++gen;
        if (!surfaceReady) return;
        final SurfaceHolder holder = surface.getHolder();
        final String u = target;
        final long start = startAt;
        ph.post(() -> {
            releaseNow();
            if (g != gen) return;
            MediaPlayer p = new MediaPlayer();
            try {
                p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
                HashMap<String, String> headers = new HashMap<>();
                headers.put("User-Agent", Xtream.UA);
                p.setDataSource(a, Uri.parse(u), headers);
                p.setDisplay(holder);
                p.setOnPreparedListener(x -> {
                    if (g != gen) return;
                    try {
                        if (start > 0) x.seekTo(start, MediaPlayer.SEEK_CLOSEST_SYNC);
                        x.start();
                    } catch (IllegalStateException ignored) {
                    }
                    App.main(() -> { if (g == gen) onPrepared(); });
                });
                p.setOnVideoSizeChangedListener((x, w, h) -> App.main(() -> {
                    if (g != gen) return;
                    videoW = w;
                    videoH = h;
                    layoutSurface();
                }));
                p.setOnInfoListener((x, what, extra) -> {
                    App.main(() -> {
                        if (g != gen) return;
                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinnerShow(true);
                        else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) spinnerShow(false);
                        else if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onRendering();
                    });
                    return true;
                });
                p.setOnSeekCompleteListener(x -> App.main(() -> {
                    if (g != gen) return;
                    seeking = false;
                    spinnerShow(false);
                }));
                p.setOnErrorListener((x, what, extra) -> {
                    Log.w(Xtream.TAG, "player error " + what + "/" + extra);
                    App.main(() -> { if (g == gen) onFailed(); });
                    return true;
                });
                p.setOnCompletionListener(x -> App.main(() -> { if (g == gen) onEnded(); }));
                p.setOnTimedTextListener((x, text) -> {
                    final String line = text == null || text.getText() == null ? "" : text.getText().trim();
                    App.main(() -> { if (g == gen) subtitles.setText(line); });
                });
                mp = p;
                p.prepareAsync();
            } catch (Exception e) {
                Log.w(Xtream.TAG, "player open failed: " + e.getClass().getSimpleName());
                try {
                    p.release();
                } catch (Exception ignored) {
                }
                mp = null;
                App.main(() -> { if (g == gen) onFailed(); });
            }
        });
    }

    /** Runs on the player thread. */
    private void releaseNow() {
        MediaPlayer old = mp;
        mp = null;
        if (old == null) return;
        try {
            old.reset();
        } catch (Exception ignored) {
        }
        try {
            old.release();
        } catch (Exception ignored) {
        }
    }

    private void onPrepared() {
        prepared = true;
        MediaPlayer p = mp;
        try {
            if (p != null && mode != LIVE) dur = Math.max(0, p.getDuration());
        } catch (IllegalStateException ignored) {
        }
        // Audio-only streams never report a first video frame.
        App.ui.removeCallbacks(spinnerOff);
        App.ui.postDelayed(spinnerOff, 2500);
        if (mode != LIVE) stay(hideBar, 4000);
    }

    private void onRendering() {
        spinnerShow(false);
        retries = 0;
        if (mode == LIVE) {
            App.ui.removeCallbacks(markRecent);
            App.ui.postDelayed(markRecent, 8000);
        }
    }

    private void onFailed() {
        prepared = false;
        if (mode == LIVE) {
            if (retries < 2) {
                retries++;
                App.ui.removeCallbacks(tuner);
                App.ui.postDelayed(tuner, 1200);
                return;
            }
            if (!altFormat) {
                altFormat = true;
                retries = 0;
                App.ui.removeCallbacks(tuner);
                App.ui.postDelayed(tuner, 300);
                return;
            }
            fail("This channel is not responding right now.\nPress Up or Down to try another one.");
        } else if (mode != NONE) {
            if (retries < 1) {
                retries++;
                open(url, pos);
                return;
            }
            fail("This video could not be played.\nPress Back to return.");
        }
    }

    private void fail(String text) {
        spinnerShow(false);
        gen++;
        ph.post(this::releaseNow);
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void onEnded() {
        if (mode == LIVE) {
            onFailed();
            return;
        }
        final Prog done = buildProgress(true);
        if (done != null) App.bg(() -> App.db.saveProg(done));
        lastSave = SystemClock.uptimeMillis();
        if (mode == EPISODE && epIndex + 1 < eps.size()) {
            a.toast("Next: " + eps.get(epIndex + 1).label());
            startEpisode(epIndex + 1, 0);
        } else {
            prepared = false;
            close();
        }
    }

    private void tick() {
        MediaPlayer p = mp;
        if (p != null && prepared) {
            try {
                if (!seeking || SystemClock.uptimeMillis() - seekStarted > 6000) {
                    seeking = false;
                    pos = p.getCurrentPosition();
                }
                if (dur <= 0 && mode != LIVE) dur = Math.max(0, p.getDuration());
            } catch (IllegalStateException ignored) {
            }
        }
        if (mode == LIVE) {
            if (bannerShown) banner.invalidate();
            return;
        }
        if (barShown) scrub.set(seekTarget >= 0 ? seekTarget : pos, dur, paused);
        if (prepared && SystemClock.uptimeMillis() - lastSave > 10000) saveProgress(false);
    }

    private Prog buildProgress(boolean finished) {
        if (mode != MOVIE && mode != EPISODE) return null;
        if (dur <= 0 || (!finished && pos < 15000)) return null;
        Prog p = new Prog();
        p.pos = pos;
        p.dur = dur;
        p.done = finished || pos > dur * 0.94f;
        if (mode == MOVIE) {
            p.kind = App.VOD;
            p.id = movie.id;
            p.title = movie.name;
            p.icon = movie.icon;
            p.ext = movie.ext;
        } else {
            Episode e = eps.get(epIndex);
            p.kind = App.EPISODE;
            p.id = e.id;
            p.sid = show.id;
            p.title = show.name;
            p.sub = e.label();
            p.icon = show.icon;
            p.ext = e.ext;
            if (p.done && epIndex + 1 < eps.size()) {
                Episode ne = eps.get(epIndex + 1);
                Prog n = new Prog();
                n.kind = App.EPISODE;
                n.id = ne.id;
                n.sid = show.id;
                n.title = show.name;
                n.sub = ne.label();
                n.icon = show.icon;
                n.ext = ne.ext;
                p.next = n;
            }
        }
        return p;
    }

    private void saveProgress(boolean finished) {
        lastSave = SystemClock.uptimeMillis();
        final Prog p = buildProgress(finished);
        if (p != null) App.bg(() -> App.db.saveProg(p));
    }

    /** Called when the activity is going to the background: remember where we are. */
    void onStop() {
        if (mode != NONE) saveProgress(false);
    }

    /** Called when the activity is destroyed: stop everything that outlives it. */
    void destroy() {
        destroyed = true;
        surfaceReady = false;
        mode = NONE;
        gen++;
        for (Runnable r : new Runnable[]{tuner, hideBanner, hideBar, hideZap, commitSeek, markRecent, spinnerOff, ticker, zapSweep}) {
            App.ui.removeCallbacks(r);
        }
        Epg.removeListener(this);
        // Unregister first: a late surfaceDestroyed would post to the dead looper and block on its latch.
        surface.getHolder().removeCallback(this);
        ph.post(this::releaseNow);
        ph.getLooper().quitSafely();
    }

    private void markRecent() {
        if (mode != LIVE || index >= channels.size()) return;
        final long id = channels.get(index).id;
        App.bg(() -> App.db.touchRecent(id));
    }

    private void spinnerShow(boolean on) {
        App.ui.removeCallbacks(spinnerOff);
        spinner.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (mode == LIVE) openCurrent();
        else if (mode != NONE && url != null) open(url, pos);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        prepared = false;
        gen++;
        final CountDownLatch latch = new CountDownLatch(1);
        ph.post(() -> {
            releaseNow();
            latch.countDown();
        });
        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private void layoutSurface() {
        int w = view.getWidth(), h = view.getHeight();
        if (w == 0 || h == 0) return;
        int outW = w, outH = h;
        if (videoW > 0 && videoH > 0 && aspect != 1) {
            float video = videoW / (float) videoH, screen = w / (float) h;
            boolean wider = video > screen;
            if (aspect == 0) {
                if (wider) outH = Math.round(w / video); else outW = Math.round(h * video);
            } else {
                if (wider) outW = Math.round(h * video); else outH = Math.round(w / video);
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != outW || lp.height != outH) {
            lp.width = outW;
            lp.height = outH;
            surface.setLayoutParams(lp);
        }
    }

    private void cycleAspect() {
        aspect = (aspect + 1) % ASPECTS.length;
        App.prefs.edit().putInt("aspect", aspect).apply();
        aspectBtn.set("Picture: " + ASPECTS[aspect], Glyph.NONE);
        layoutSurface();
        if (mode == LIVE) a.toast("Picture: " + ASPECTS[aspect]);
    }

    // ------------------------------------------------------------------- live

    private void tune(int delay) {
        retries = 0;
        altFormat = false;
        message.setVisibility(View.GONE);
        subtitles.setText("");
        App.ui.removeCallbacks(markRecent);
        showBanner();
        App.ui.removeCallbacks(tuner);
        if (delay > 0) {
            spinnerShow(true);
            App.ui.postDelayed(tuner, delay);
        } else {
            openCurrent();
        }
    }

    private void openCurrent() {
        if (mode != LIVE || index >= channels.size()) return;
        String fmt = a.liveFormat();
        if (altFormat) fmt = "ts".equals(fmt) ? "m3u8" : "ts";
        videoW = videoH = 0;
        open(Xtream.liveUrl(channels.get(index).id, fmt), 0);
    }

    private void switchTo(int to, int delay) {
        if (channels.isEmpty()) return;
        previousId = channels.get(index).id;
        index = (to % channels.size() + channels.size()) % channels.size();
        zapList.rebind();
        if (!zapShown) zapList.select(index, false);
        tune(delay);
    }

    private void recall() {
        if (previousId < 0) {
            a.toast("No previous channel yet");
            return;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id == previousId) {
                switchTo(i, 0);
                return;
            }
        }
    }

    private void showBanner() {
        if (mode != LIVE || index >= channels.size()) return;
        Item it = channels.get(index);
        Epg.want(it.id);
        banner.set(it, number(index), group);
        setBanner(true);
        stay(hideBanner, 5000);
    }

    private void setBanner(boolean on) {
        if (on == bannerShown) return;
        bannerShown = on;
        banner.animate().alpha(on ? 1f : 0f).translationY(on ? 0 : App.dp(16)).setDuration(on ? 220 : 320)
                .setInterpolator(Ui.EASE).start();
    }

    private void setZap(boolean on) {
        App.ui.removeCallbacks(hideZap);
        if (on == zapShown) return;
        zapShown = on;
        if (on) {
            setBanner(false);
            zapList.rebind();
            zapList.select(index, false);
            zap.setVisibility(View.VISIBLE);
            zap.setTranslationX(-App.dp(30));
            zap.animate().alpha(1f).translationX(0).setDuration(Ui.ms(240)).setInterpolator(Ui.EASE).withEndAction(null).start();
            zapList.requestFocus();
            stay(hideZap, 12000);
            App.ui.postDelayed(zapSweep, 200);
        } else {
            if (mode != NONE) view.requestFocus();
            zap.animate().alpha(0f).translationX(-App.dp(20)).setDuration(Ui.ms(180)).withEndAction(() -> {
                if (!zapShown) zap.setVisibility(View.GONE);
            }).start();
        }
    }

    private void sweepEpg() {
        if (!zapShown) return;
        zapList.each((v, p) -> {
            if (p < channels.size()) Epg.want(channels.get(p).id);
        });
    }

    @Override
    public void onEpg(long id) {
        if (mode != LIVE) return;
        if (index < channels.size() && channels.get(index).id == id && bannerShown) {
            banner.set(channels.get(index), number(index), group);
        }
        if (zapShown) {
            zapList.each((v, p) -> {
                if (p < channels.size() && channels.get(p).id == id) zapList.rebind(p);
            });
        }
    }

    private void liveOptions() {
        if (index >= channels.size()) return;
        final Item it = channels.get(index);
        App.bg(() -> {
            final boolean fav = App.db.isFav(App.LIVE, it.id);
            App.main(() -> {
                if (mode != LIVE) return;
                List<Sheet.Option> o = new ArrayList<>();
                o.add(new Sheet.Option(fav ? "Remove from Favourites" : "Add to Favourites", () -> a.setFav(it, !fav)));
                o.add(new Sheet.Option("Audio", null, Glyph.CHEVRON, () -> tracks(true)));
                o.add(new Sheet.Option("Subtitles", null, Glyph.CHEVRON, () -> tracks(false)));
                o.add(new Sheet.Option("Picture", ASPECTS[aspect], Glyph.NONE, this::cycleAspect));
                a.sheet.show(it.name, null, o, 0);
            });
        });
    }

    // ------------------------------------------------------------ movie / episode

    private void setBar(boolean on) {
        App.ui.removeCallbacks(hideBar);
        if (on == barShown) {
            if (on) stay(hideBar, 4000);
            return;
        }
        barShown = on;
        if (on) {
            scrub.set(seekTarget >= 0 ? seekTarget : pos, dur, paused);
            bar.setVisibility(View.VISIBLE);
            bar.animate().alpha(1f).translationY(0).setDuration(Ui.ms(220)).setInterpolator(Ui.EASE).withEndAction(null).start();
            scrub.requestFocus();
            stay(hideBar, 4000);
        } else {
            if (mode != NONE && !a.sheet.visible()) view.requestFocus();
            bar.animate().alpha(0f).translationY(App.dp(16)).setDuration(Ui.ms(260)).withEndAction(() -> {
                if (!barShown) bar.setVisibility(View.GONE);
            }).start();
        }
    }

    private void stay(Runnable hider, int ms) {
        App.ui.removeCallbacks(hider);
        App.ui.postDelayed(hider, ms);
    }

    @Override
    public void toggle() {
        MediaPlayer p = mp;
        if (p == null || !prepared) return;
        try {
            if (paused) p.start(); else p.pause();
            paused = !paused;
        } catch (IllegalStateException ignored) {
        }
        scrub.set(seekTarget >= 0 ? seekTarget : pos, dur, paused);
        if (paused) App.ui.removeCallbacks(hideBar); else stay(hideBar, 4000);
        if (paused) saveProgress(false);
    }

    @Override
    public void seekBy(int direction, int repeat) {
        if (!prepared || dur <= 0) return;
        long step = repeat < 8 ? 10000 : repeat < 30 ? 30000 : repeat < 70 ? 60000 : 180000;
        long base = seekTarget >= 0 ? seekTarget : pos;
        seekTarget = Math.max(0, Math.min(base + direction * step, dur - 3000));
        scrub.set(seekTarget, dur, paused);
        stay(commitSeek, 450);
        stay(hideBar, 4500);
    }

    private void commitSeek() {
        MediaPlayer p = mp;
        if (p != null && prepared && seekTarget >= 0) {
            try {
                seeking = true;
                seekStarted = SystemClock.uptimeMillis();
                p.seekTo(seekTarget, MediaPlayer.SEEK_CLOSEST);
                pos = seekTarget;
                spinnerShow(true);
            } catch (IllegalStateException e) {
                seeking = false;
            }
        }
        seekTarget = -1;
        stay(hideBar, 4000);
    }

    private void nextEpisode() {
        if (mode != EPISODE || epIndex + 1 >= eps.size()) return;
        saveProgress(false);
        startEpisode(epIndex + 1, 0);
    }

    private static String language(String code, int n) {
        if (code == null || code.isEmpty() || "und".equals(code)) return "Track " + n;
        String name = new Locale(code).getDisplayLanguage(Locale.ENGLISH);
        if (name == null || name.isEmpty() || name.equals(code)) return code.toUpperCase(Locale.ROOT);
        return name;
    }

    private void tracks(final boolean audio) {
        final MediaPlayer p = mp;
        if (p == null || !prepared) {
            a.toast("Not available yet");
            return;
        }
        List<Sheet.Option> o = new ArrayList<>();
        int chosen = 0;
        try {
            MediaPlayer.TrackInfo[] infos = p.getTrackInfo();
            int current = -1;
            int[] types = audio ? new int[]{MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO}
                    : new int[]{MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT, MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE};
            for (int t : types) {
                try {
                    int s = p.getSelectedTrack(t);
                    if (s >= 0) current = s;
                } catch (Exception ignored) {
                }
            }
            final int selected = current;
            if (!audio) {
                o.add(new Sheet.Option("Off", null, selected < 0 ? Glyph.CHECK : Glyph.NONE, () -> {
                    try {
                        if (selected >= 0) p.deselectTrack(selected);
                    } catch (Exception ignored) {
                    }
                    subtitles.setText("");
                }));
            }
            int n = 0;
            for (int i = 0; i < infos.length; i++) {
                int type = infos[i].getTrackType();
                boolean match = false;
                for (int t : types) if (t == type) match = true;
                if (!match) continue;
                n++;
                final int track = i;
                if (i == selected) chosen = o.size();
                o.add(new Sheet.Option(language(infos[i].getLanguage(), n), null, i == selected ? Glyph.CHECK : Glyph.NONE, () -> {
                    try {
                        p.selectTrack(track);
                    } catch (Exception e) {
                        a.toast("That track could not be selected");
                    }
                }));
            }
        } catch (Exception e) {
            Log.w(Xtream.TAG, "track list failed: " + e.getClass().getSimpleName());
        }
        if (o.size() <= (audio ? 0 : 1)) {
            a.toast(audio ? "No other audio tracks" : "No subtitles in this stream");
            return;
        }
        a.sheet.show(audio ? "Audio" : "Subtitles", null, o, chosen);
    }

    // ------------------------------------------------------------------- keys

    boolean onBack() {
        if (zapShown) {
            setZap(false);
            return true;
        }
        if (barShown && mode != LIVE && !paused) {
            setBar(false);
            return true;
        }
        close();
        return true;
    }

    /** First look at every key while playing. Returning false lets the focused control handle it. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && e.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first && mode != LIVE) {
                    setBar(true);
                    toggle();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (first) close();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down && mode != LIVE) {
                    setBar(true);
                    seekBy(code == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1, e.getRepeatCount());
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (mode == LIVE && !zapShown) {
                    if (down) switchTo(index + (code == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1), 350);
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false;
            default:
                break;
        }
        if (mode == LIVE) {
            if (zapShown) return false;
            if (!down) return true;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    switchTo(index - 1, 350);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    switchTo(index + 1, 350);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (first) setZap(true);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (first) recall();
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MENU:
                    if (first) liveOptions();
                    break;
                case KeyEvent.KEYCODE_INFO:
                    if (first) showBanner();
                    break;
                default:
                    return false;
            }
            return true;
        }
        if (barShown) {
            if (down) stay(hideBar, 4000);
            return false;
        }
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) {
                    setBar(true);
                    toggle();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                setBar(true);
                seekBy(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
                if (first) setBar(true);
                return true;
            default:
                return false;
        }
    }
}
