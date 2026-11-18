package tv.iptv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Indeterminate progress: a quiet rotating arc. */
final class Spinner extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Spinner(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(App.dp(3));
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = App.dp(3), s = Math.min(getWidth(), getHeight());
        float angle = (SystemClock.uptimeMillis() % 900) / 900f * 360f;
        p.setColor(0x33FFFFFF);
        c.drawArc(pad, pad, s - pad, s - pad, 0, 360, false, p);
        p.setColor(Ui.TEXT);
        c.drawArc(pad, pad, s - pad, s - pad, angle, 90, false, p);
        if (getVisibility() == VISIBLE && isShown()) postInvalidateOnAnimation();
    }
}

/** Serves a one-page sign-in form to a phone on the same network, only while the sign-in screen is up. */
final class PairServer {
    interface Handler {
        /** Returns null when the credentials worked, otherwise the message to show on the phone. */
        String onCredentials(String server, String user, String pass);
    }

    static final int PORT = 8765;
    private ServerSocket socket;
    private volatile boolean running;

    static String address() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String host = addr.getHostAddress();
                    if (host == null || host.startsWith("169.254.")) continue;
                    if (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")) return host + ":" + PORT;
                    fallback = host;
                }
            }
            return fallback == null ? null : fallback + ":" + PORT;
        } catch (Exception e) {
            return null;
        }
    }

    void start(final Handler handler) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            try {
                socket = new ServerSocket(PORT);
                while (running) {
                    final Socket client = socket.accept();
                    App.net(() -> serve(client, handler));
                }
            } catch (IOException e) {
                if (running) Log.w(Xtream.TAG, "pairing server stopped: " + e.getMessage());
            }
        }, "pair-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    private void serve(Socket client, Handler handler) {
        try (Socket s = client) {
            s.setSoTimeout(8000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int prev3 = 0, prev2 = 0, prev1 = 0, cur;
            while ((cur = in.read()) != -1) {
                head.write(cur);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && cur == '\n') break;
                prev3 = prev2;
                prev2 = prev1;
                prev1 = cur;
                if (head.size() > 16384) return;
            }
            String[] lines = new String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
            String[] request = lines[0].split(" ");
            String method = request.length > 0 ? request[0] : "GET";
            String path = request.length > 1 ? request[1] : "/";
            int length = 0;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("content-length:")) length = (int) Xtream.num(line.substring(15).trim());
            }
            OutputStream out = s.getOutputStream();
            if ("POST".equals(method) && path.startsWith("/pair") && length > 0 && length < 8192) {
                byte[] body = new byte[length];
                int got = 0;
                while (got < length) {
                    int n = in.read(body, got, length - got);
                    if (n < 0) break;
                    got += n;
                }
                JSONObject o = new JSONObject(new String(body, 0, got, StandardCharsets.UTF_8));
                String error = handler.onCredentials(o.optString("server"), o.optString("username"), o.optString("password"));
                JSONObject reply = new JSONObject();
                reply.put("ok", error == null);
                if (error != null) reply.put("error", error);
                respond(out, "application/json", reply.toString());
            } else {
                respond(out, "text/html; charset=utf-8", PAGE);
            }
        } catch (Exception e) {
            Log.w(Xtream.TAG, "pairing request failed: " + e.getClass().getSimpleName());
        }
    }

    private static void respond(OutputStream out, String type, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static final String PAGE = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>IPTV</title><style>"
            + "body{margin:0;padding:28px 20px;background:#0c0c0f;color:#f5f5f7;font:16px -apple-system,Roboto,Segoe UI,sans-serif}"
            + "form{max-width:420px;margin:0 auto}h1{font-size:26px;margin:0 0 6px}p{color:#a6a6ad;margin:0 0 22px;line-height:1.45}"
            + "label{display:block;font-size:13px;color:#a6a6ad;margin:14px 0 6px}"
            + "input{width:100%;box-sizing:border-box;padding:13px 16px;border-radius:12px;border:1px solid #2c2c33;background:#1b1b20;color:#f5f5f7;font-size:16px}"
            + "button{width:100%;margin-top:24px;padding:14px;border:0;border-radius:999px;background:#f2f2f4;color:#101014;font-size:16px;font-weight:600}"
            + "#m{margin-top:16px;color:#a6a6ad;min-height:22px}</style></head><body><form onsubmit='return go(event)'>"
            + "<h1>Sign in to IPTV</h1><p>Enter the details from your subscription. They are sent straight to your TV.</p>"
            + "<label for='s'>Server address</label><input id='s' placeholder='http://example.com:8080' autocapitalize='off' autocorrect='off' spellcheck='false' required>"
            + "<label for='u'>Username</label><input id='u' autocapitalize='off' autocorrect='off' spellcheck='false' required>"
            + "<label for='p'>Password</label><input id='p' autocapitalize='off' autocorrect='off' spellcheck='false' required>"
            + "<button id='b'>Sign In on TV</button><div id='m' role='status'></div></form><script>"
            + "function go(e){e.preventDefault();var m=document.getElementById('m'),b=document.getElementById('b');"
            + "m.textContent='Checking\\u2026';b.disabled=true;"
            + "fetch('/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({"
            + "server:document.getElementById('s').value,username:document.getElementById('u').value,password:document.getElementById('p').value})})"
            + ".then(function(r){return r.json()}).then(function(j){b.disabled=false;"
            + "m.textContent=j.ok?'Signed in. Your TV is loading your library.':j.error})"
            + ".catch(function(){b.disabled=false;m.textContent='Could not reach the TV. Is the sign-in screen still open?'});return false}"
            + "</script></body></html>";
}

/** Full-screen states that sit in front of the app: signing in, and the first library download. */
final class Gate {
    private final MainActivity a;
    final FrameLayout view;
    private final LinearLayout form, progress;
    private final EditText server, user, pass;
    private final TextView error, stage, detail, phone;
    private final Btn go, retry, switchAccount;
    private final Spinner spinner;
    private final QrView qr;
    private final PairServer pairing = new PairServer();
    private boolean busy, hiding;

    Gate(final MainActivity a) {
        this.a = a;
        view = new FrameLayout(a);
        view.setBackgroundColor(Ui.BG);
        view.setVisibility(View.GONE);

        form = new LinearLayout(a);
        form.setOrientation(LinearLayout.HORIZONTAL);
        form.setGravity(Gravity.CENTER_VERTICAL);
        form.setClipChildren(false);

        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        TextView title = Ui.text(a, 30f, Ui.TEXT, Ui.BOLD);
        title.setText("Sign In");
        left.addView(title);
        TextView sub = Ui.text(a, 14f, Ui.TEXT2, Ui.REG);
        sub.setText("Use the details from your IPTV subscription.");
        sub.setPadding(0, App.dp(6), 0, App.dp(18));
        left.addView(sub);
        server = Field.make(a, "Server address", EditorInfo.IME_ACTION_NEXT);
        user = Field.make(a, "Username", EditorInfo.IME_ACTION_NEXT);
        pass = Field.make(a, "Password", EditorInfo.IME_ACTION_DONE);
        for (EditText e : new EditText[]{server, user, pass}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, App.dp(44));
            lp.bottomMargin = App.dp(10);
            left.addView(e, lp);
        }
        pass.setOnEditorActionListener((v, id, ev) -> {
            Field.keyboard(pass, false);
            attempt();
            return true;
        });
        go = new Btn(a, "Sign In", Glyph.NONE, this::attempt);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = App.dp(10);
        left.addView(go, lp);
        error = Ui.text(a, 13f, Ui.TEXT2, Ui.REG);
        error.setPadding(0, App.dp(14), 0, 0);
        error.setMinLines(2);
        left.addView(error, new LinearLayout.LayoutParams(-1, -2));
        form.addView(left, new LinearLayout.LayoutParams(App.dp(380), -2));

        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView alt = Ui.text(a, 16f, Ui.TEXT, Ui.MED);
        alt.setText("Easier from your phone");
        right.addView(alt);
        phone = Ui.text(a, 14f, Ui.TEXT2, Ui.REG);
        phone.setLineSpacing(App.dp(5), 1f);
        phone.setPadding(0, App.dp(8), 0, 0);
        right.addView(phone);
        qr = new QrView(a);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(App.dp(150), App.dp(150));
        ql.topMargin = App.dp(14);
        ql.bottomMargin = App.dp(6);
        // Above the text, so the on-screen keyboard (which rises from the bottom) never covers the code.
        right.addView(qr, 1, ql);
        lp = new LinearLayout.LayoutParams(App.dp(330), -2);
        lp.leftMargin = App.dp(72);
        form.addView(right, lp);
        view.addView(form, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        progress = new LinearLayout(a);
        progress.setOrientation(LinearLayout.VERTICAL);
        progress.setGravity(Gravity.CENTER_HORIZONTAL);
        progress.setClipChildren(false);
        spinner = new Spinner(a);
        progress.addView(spinner, new LinearLayout.LayoutParams(App.dp(34), App.dp(34)));
        stage = Ui.text(a, 22f, Ui.TEXT, Ui.BOLD);
        stage.setPadding(0, App.dp(22), 0, App.dp(8));
        stage.setGravity(Gravity.CENTER);
        progress.addView(stage);
        detail = Ui.text(a, 14f, Ui.TEXT2, Ui.REG);
        detail.setGravity(Gravity.CENTER);
        progress.addView(detail);
        retry = new Btn(a, "Try Again", Glyph.NONE, () -> a.sync(true));
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = App.dp(24);
        progress.addView(retry, lp);
        switchAccount = new Btn(a, "Use a Different Account", Glyph.NONE, a::signOut);
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = App.dp(12);
        progress.addView(switchAccount, lp);
        view.addView(progress, new FrameLayout.LayoutParams(App.dp(520), -2, Gravity.CENTER));
    }

    /** False as soon as the fade-out starts, so the app behind it owns focus and Back straight away. */
    boolean visible() { return view.getVisibility() == View.VISIBLE && !hiding; }

    boolean signingIn() { return visible() && form.getVisibility() == View.VISIBLE; }

    void showSignIn() {
        hiding = false;
        view.animate().cancel();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(1f);
        form.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        error.setText("");
        String addr = PairServer.address();
        phone.setText(addr == null ? "Connect the TV to your network to sign in from a phone."
                : "On the same Wi-Fi, scan this code with your phone's camera, or open\nhttp://" + addr);
        qr.setText(addr == null ? null : "http://" + addr);
        pairing.start((s, u, p) -> {
            String err = Xtream.signIn(s, u, p);
            if (err == null) App.main(a::onSignedIn);
            return err;
        });
        server.requestFocus();
    }

    private void attempt() {
        if (busy) return;
        busy = true;
        error.setText("Signing in…");
        final String s = server.getText().toString(), u = user.getText().toString(), p = pass.getText().toString();
        App.net(() -> {
            final String err = Xtream.signIn(s, u, p);
            App.main(() -> {
                busy = false;
                if (err == null) a.onSignedIn(); else error.setText(err);
            });
        });
    }

    void showProgress(String title, String text) {
        pairing.stop();
        hiding = false;
        view.animate().cancel();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(1f);
        form.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        switchAccount.setVisibility(View.GONE);
        stage.setText(title);
        detail.setText(text);
        spinner.setFocusable(true);
        spinner.requestFocus();
    }

    void setProgress(String text) { detail.setText(text); }

    void showFailure(String title, String text) {
        showProgress(title, text);
        spinner.setVisibility(View.INVISIBLE);
        retry.setVisibility(View.VISIBLE);
        switchAccount.setVisibility(View.VISIBLE);
        retry.requestFocus();
    }

    void hide() {
        pairing.stop();
        if (!visible()) return;
        hiding = true;
        view.animate().alpha(0f).setDuration(Ui.ms(260)).withEndAction(() -> {
            if (!hiding) return;
            hiding = false;
            view.setVisibility(View.GONE);
        }).start();
    }
}
