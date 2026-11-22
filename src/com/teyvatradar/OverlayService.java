package com.teyvatradar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Serviço que mantém a bolinha flutuante e a janela do mapa por cima do jogo.
 */
public class OverlayService extends Service {

    public static final String ACTION_TOGGLE = "com.teyvatradar.TOGGLE";
    public static final String ACTION_STOP = "com.teyvatradar.STOP";
    public static final String DEFAULT_URL = "https://act.hoyolab.com/ys/app/interactive-map/index.html";

    static final int GOLD = 0xFFD4AF37;
    static final int GOLD_LIGHT = 0xFFFFE6A8;
    static final int BG = 0xFF12141F;
    static final int BAR = 0xFF1E2235;
    static final int TEXT = 0xFFE8E8F0;

    // Android 12+ só deixa o toque "atravessar" uma janela de outro app
    // se ela tiver no máximo 80% de opacidade.
    static final float GHOST_MAX_ALPHA = 0.8f;

    static boolean running;

    Context wc;              // contexto de janela (overlay)
    Context ui;              // contexto com tema, para as views
    WindowManager wm;
    SharedPreferences prefs;

    TextView bubble;
    WindowManager.LayoutParams bp;

    LinearLayout panel;
    WindowManager.LayoutParams pp;
    WebView web;
    TextView title, ghostBtn, kbBtn;

    boolean panelShown, ghost, keyboard;
    float alpha;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        wc = createWindowContext(d, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        ui = new ContextThemeWrapper(wc, android.R.style.Theme_DeviceDefault);
        wm = wc.getSystemService(WindowManager.class);
        prefs = getSharedPreferences("radar", MODE_PRIVATE);
        alpha = prefs.getFloat("alpha", 0.75f);
        startInForeground();
        createBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_STOP.equals(a)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_TOGGLE.equals(a)) {
                if (ghost) toggleGhost();
                togglePanel();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (panelShown) wm.removeView(panel); } catch (Exception ignored) { }
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) { }
        if (web != null) {
            try { web.destroy(); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Girou a tela: garante que nada fique fora dela.
        if (bubble != null) { clamp(bp); safeUpdate(bubble, bp); }
        if (panel != null) { clamp(pp); if (panelShown) safeUpdate(panel, pp); }
    }

    // ------------------------------------------------------------------ util

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    Rect screen() {
        return wm.getCurrentWindowMetrics().getBounds();
    }

    WindowManager.LayoutParams baseParams(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return p;
    }

    void clamp(WindowManager.LayoutParams p) {
        Rect s = screen();
        int sw = s.width(), sh = s.height();
        if (p.width > sw) p.width = sw;
        if (p.height > sh) p.height = sh;
        int w = p.width > 0 ? p.width : dp(52);
        int h = p.height > 0 ? p.height : dp(52);
        p.x = Math.max(0, Math.min(p.x, sw - w));
        p.y = Math.max(0, Math.min(p.y, sh - h));
    }

    void safeUpdate(View v, WindowManager.LayoutParams p) {
        try { wm.updateViewLayout(v, p); } catch (Exception ignored) { }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------- notificação

    void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                "radar", "Teyvat Radar", NotificationManager.IMPORTANCE_LOW));

        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_TOGGLE), f);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), f);
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class), f);

        Notification n = new Notification.Builder(this, "radar")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("Teyvat Radar ativo")
                .setContentText("Toque na bolinha ⚔ para abrir/fechar o mapa")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Mostrar/ocultar", toggle).build())
                .addAction(new Notification.Action.Builder((Icon) null, "Fechar radar", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    // --------------------------------------------------------------- bolinha

    void createBubble() {
        bubble = new TextView(ui);
        bubble.setText("⚔");
        bubble.setTextSize(22);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(GOLD_LIGHT);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xCC1E2235);
        g.setStroke(dp(2), GOLD);
        bubble.setBackground(g);

        bp = baseParams(dp(52), dp(52));
        bp.x = prefs.getInt("bx", dp(8));
        bp.y = prefs.getInt("by", dp(140));
        clamp(bp);
        bubble.setOnTouchListener(new Drag(bp, bubble, "b", false, new Runnable() {
            public void run() { onBubbleTap(); }
        }));
        wm.addView(bubble, bp);
    }

    void onBubbleTap() {
        if (ghost) {
            toggleGhost();      // sai do modo fantasma
        } else {
            togglePanel();
        }
    }

    void refreshBubble() {
        if (bubble == null) return;
        bubble.setText(ghost ? "👻" : (panelShown ? "✕" : "⚔"));
    }

    // ---------------------------------------------------------------- painel

    TextView barButton(LinearLayout bar, String label, View.OnClickListener l) {
        TextView b = new TextView(ui);
        b.setText(label);
        b.setTextSize(17);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        bar.addView(b, new LinearLayout.LayoutParams(dp(38), dp(40)));
        return b;
    }

    void createPanel() {
        panel = new LinearLayout(ui);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), GOLD);
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setPadding(dp(1), dp(1), dp(1), dp(1));

        // Barra superior
        LinearLayout bar = new LinearLayout(ui);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);

        title = new TextView(ui);
        title.setTextColor(GOLD_LIGHT);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(10), 0, dp(4), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1f));

        barButton(bar, "←", new View.OnClickListener() {           // ←
            public void onClick(View v) { if (web.canGoBack()) web.goBack(); }
        });
        barButton(bar, "⌂", new View.OnClickListener() {           // ⌂
            public void onClick(View v) { web.loadUrl(prefs.getString("url", DEFAULT_URL)); }
        });
        barButton(bar, "⟳", new View.OnClickListener() {           // ⟳
            public void onClick(View v) { web.reload(); }
        });
        barButton(bar, "−", new View.OnClickListener() {           // −
            public void onClick(View v) { changeAlpha(-0.1f); }
        });
        barButton(bar, "+", new View.OnClickListener() {
            public void onClick(View v) { changeAlpha(0.1f); }
        });
        ghostBtn = barButton(bar, "👻", new View.OnClickListener() { // 👻
            public void onClick(View v) { toggleGhost(); }
        });
        kbBtn = barButton(bar, "⌨", new View.OnClickListener() {   // ⌨
            public void onClick(View v) { toggleKeyboard(); }
        });
        barButton(bar, "▾", new View.OnClickListener() {           // ▾ minimizar
            public void onClick(View v) { togglePanel(); }
        });
        panel.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Conteúdo: mapa + alça de redimensionar
        FrameLayout content = new FrameLayout(ui);
        web = new WebView(ui);
        setupWeb();
        content.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView grip = new TextView(ui);
        grip.setText("◢");                                          // ◢
        grip.setTextColor(GOLD);
        grip.setTextSize(24);
        grip.setGravity(Gravity.BOTTOM | Gravity.RIGHT);
        content.addView(grip, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.RIGHT));

        panel.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Posição e tamanho (lembrados entre usos)
        Rect s = screen();
        int defW = (int) (s.width() * 0.55f);
        int defH = (int) (s.height() * 0.85f);
        pp = baseParams(prefs.getInt("pw", defW), prefs.getInt("ph", defH));
        pp.x = prefs.getInt("px", s.width() - defW - dp(16));
        pp.y = prefs.getInt("py", (s.height() - defH) / 2);
        clamp(pp);

        title.setOnTouchListener(new Drag(pp, panel, "p", false, null));
        grip.setOnTouchListener(new Drag(pp, panel, "p", true, null));
        applyAlpha();
    }

    void setupWeb() {
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        st.setSupportZoom(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        st.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(BG);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                // Links http(s) abrem dentro da janela; links de apps são ignorados
                // (evita sair do jogo sem querer).
                return !("http".equals(sc) || "https".equals(sc));
            }
        });
        web.loadUrl(prefs.getString("url", DEFAULT_URL));
    }

    void togglePanel() {
        if (panel == null) createPanel();
        if (panelShown) {
            if (keyboard) toggleKeyboard();
            try { wm.removeView(panel); } catch (Exception ignored) { }
            web.onPause();                  // economiza bateria enquanto oculto
            panelShown = false;
        } else {
            clamp(pp);
            wm.addView(panel, pp);
            web.onResume();
            panelShown = true;
        }
        refreshBubble();
    }

    void changeAlpha(float delta) {
        alpha = Math.round((alpha + delta) * 10f) / 10f;
        alpha = Math.max(0.2f, Math.min(1.0f, alpha));
        prefs.edit().putFloat("alpha", alpha).apply();
        applyAlpha();
    }

    void applyAlpha() {
        if (pp == null) return;
        pp.alpha = ghost ? Math.min(alpha, GHOST_MAX_ALPHA) : alpha;
        title.setText("Radar " + Math.round(pp.alpha * 100) + "%");
        if (panelShown) safeUpdate(panel, pp);
    }

    void toggleGhost() {
        if (panel == null) createPanel();
        if (!ghost && !panelShown) togglePanel();
        ghost = !ghost;
        if (ghost) {
            if (keyboard) toggleKeyboard();
            pp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            toast("Modo fantasma: os toques vão para o jogo. Toque na bolinha 👻 para sair.");
        } else {
            pp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        ghostBtn.setTextColor(ghost ? GOLD : TEXT);
        applyAlpha();
        refreshBubble();
    }

    void toggleKeyboard() {
        keyboard = !keyboard;
        if (keyboard) {
            pp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            safeUpdate(panel, pp);
            web.requestFocus();
            toast("Teclado liberado: toque no campo de busca do mapa.");
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            pp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            safeUpdate(panel, pp);
        }
        kbBtn.setTextColor(keyboard ? GOLD : TEXT);
    }

    // ------------------------------------------------- arrastar/redimensionar

    class Drag implements View.OnTouchListener {
        final WindowManager.LayoutParams p;
        final View v;
        final String key;
        final boolean resize;
        final Runnable tap;
        float sx, sy;
        int ox, oy, ow, oh;
        boolean moved;

        Drag(WindowManager.LayoutParams p, View v, String key, boolean resize, Runnable tap) {
            this.p = p; this.v = v; this.key = key; this.resize = resize; this.tap = tap;
        }

        @Override
        public boolean onTouch(View view, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX(); sy = e.getRawY();
                    ox = p.x; oy = p.y; ow = p.width; oh = p.height;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                    if (!moved && Math.hypot(dx, dy) < dp(6)) return true;
                    moved = true;
                    if (resize) {
                        p.width = Math.max(dp(220), (int) (ow + dx));
                        p.height = Math.max(dp(160), (int) (oh + dy));
                    } else {
                        p.x = (int) (ox + dx);
                        p.y = (int) (oy + dy);
                    }
                    clamp(p);
                    safeUpdate(v, p);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        if (tap != null) tap.run();
                    } else {
                        SharedPreferences.Editor ed = prefs.edit()
                                .putInt(key + "x", p.x).putInt(key + "y", p.y);
                        if (p.width > 0) ed.putInt(key + "w", p.width).putInt(key + "h", p.height);
                        ed.apply();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        }
    }
}
