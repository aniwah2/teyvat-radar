package com.teyvatradar;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * O "cerebro" do radar, independente de como a tela e capturada:
 * recebe quadros em tons de cinza (altura MapLocator.CAP_H), detecta o mapa aberto,
 * mostra o botao, o painel de filtros e desenha os marcadores.
 * Usado pelo servico de Acessibilidade (modo principal) e pelo de captura de tela (alternativo).
 */
public class RadarEngine {

    static final String TAG = "TeyvatRadar";

    public interface Host {
        /** So no modo captura de tela: o usuario tocou em "religar". */
        void requestResume();
    }

    final Context svc, ui;
    final WindowManager wm;
    final int winType;
    final boolean trusted;            // janela de acessibilidade: toque sempre atravessa
    final Host host;
    final Handler main;
    final HandlerThread workerThread;
    public final Handler worker;
    final SharedPreferences prefs;

    DataStore data;
    MapLocator locator;
    public volatile boolean ready;
    volatile boolean loading, destroyed;

    TextView button, pill;
    WindowManager.LayoutParams btnLp, pillLp, mkLp, panelLp;
    MarkerView markers, miniView;
    WindowManager.LayoutParams miniLp;
    boolean miniShown;
    MiniTracker mini;
    volatile boolean justOpened;
    volatile String lastMini = "-";
    FilterPanel panel;
    boolean buttonShown, markersShown, pillShown;
    public volatile boolean panelOpen;

    Set<Integer> selected = new HashSet<>();
    int openStreak, closedStreak, missStreak;
    volatile boolean mapOpen;
    MapLocator.Pose pose;

    public RadarEngine(Context service, int windowType, Host host) {
        svc = service;
        winType = windowType;
        trusted = windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        this.host = host;
        main = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("radar-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        prefs = service.getSharedPreferences("radar", Context.MODE_PRIVATE);
        for (String s : prefs.getStringSet("selected", new HashSet<String>())) {
            try { selected.add(Integer.parseInt(s)); } catch (Exception ignored) { }
        }
        Context wc = service;
        if (!trusted) {
            DisplayManager dm = (DisplayManager) service.getSystemService(Context.DISPLAY_SERVICE);
            wc = service.createWindowContext(dm.getDisplay(Display.DEFAULT_DISPLAY), windowType, null);
        }
        ui = new ContextThemeWrapper(wc, android.R.style.Theme_DeviceDefault);
        wm = wc.getSystemService(WindowManager.class);
        setupViews();
        loading = true;
        worker.post(new Runnable() { public void run() { loadData(); } });
    }

    public void destroy() {
        destroyed = true;
        worker.removeCallbacksAndMessages(null);
        main.removeCallbacksAndMessages(null);
        removeSafe(button); removeSafe(markers); removeSafe(panel); removeSafe(pill); removeSafe(miniView);
        workerThread.quitSafely();
    }

    // ------------------------------------------------------------ dados

    void loadData() {
        try {
            OpenCVLoader.initDebug();
            DataStore d = new DataStore(svc);
            if (!d.isReady()) throw new IllegalStateException("sem dados");
            d.load();
            locator = new MapLocator(svc, d);
            if (mini != null) mini.release();
            mini = null;
            try { mini = new MiniTracker(locator.spec, d.dir); }
            catch (Throwable e) { Log.e(TAG, "minimapa", e); lastMini = "sem mapas de referência (atualize os dados)"; }
            data = d;
            ready = true;
            main.post(new Runnable() { public void run() { rebuildLayers(); } });
        } catch (Throwable e) {
            Log.e(TAG, "loadData", e);
            main.post(new Runnable() { public void run() {
                toast("Teyvat Radar: abra o app e baixe os dados do mapa.");
            } });
        } finally {
            loading = false;
        }
    }

    /** Recarrega os dados (ex.: depois de um download novo). */
    public void reload() {
        if (loading) return;
        ready = false;
        loading = true;
        worker.post(new Runnable() { public void run() { loadData(); } });
    }

    // -------------------------------------------------------- processamento

    /** Chamar na thread "worker" com uma captura em cinza (altura CAP_H). */
    public void process(Mat g) {
        frames++;
        lastW = g.cols(); lastH = g.rows();
        if (!ready || panelOpen || destroyed) return;
        if (g.cols() < g.rows()) { setMapOpen(false); return; }   // retrato: o jogo nao esta na tela
        double score = locator.mapOpenScore(g);
        lastScore = score;
        boolean open = score > 0.75;
        if (open) { openStreak++; closedStreak = 0; } else { closedStreak++; openStreak = 0; }
        if (!mapOpen && openStreak >= 2) setMapOpen(true);
        if (mapOpen && closedStreak >= 2) setMapOpen(false);
        if (!open && !mapOpen) { processMini(g); return; }
        if (miniShown) hideMini();
        if (!mapOpen || !open || selected.isEmpty()) return;

        double k = locator.scaleFromKnob(g);
        lastK = k;
        if (k <= 0) { miss(); lastLocate = "barra de zoom não encontrada"; return; }
        Mat mask = MapLocator.uiMask(g.rows(), g.cols());
        // nossos proprios icones aparecem na captura: nao podem confundir o reconhecimento
        float[] d = markers.drawnCap;
        float rr = markers.drawnRadiusCap;
        if (markers.drawnCapH > 0 && markers.drawnCapH != g.rows()) d = new float[0];
        for (int i = 0; i + 1 < d.length; i += 2)
            Imgproc.circle(mask, new Point(d[i], d[i + 1]), Math.round(rr), new Scalar(0), -1);

        MapLocator.Pose p = null;
        if (pose != null) p = locator.locate(g, mask, k, pose);
        if (p == null && pose == null) {
            MapLocator.Pose last = lastSavedPose();
            if (last != null) p = locator.locate(g, mask, k, last);
        }
        if (p == null) p = locator.locate(g, mask, k, null);
        mask.release();
        if (p == null) { miss(); lastLocate = "posição não encontrada"; return; }
        lastLocate = "ok (" + p.votes + " votos)";
        if (justOpened) {                  // o mapa abre centralizado no personagem
            justOpened = false;
            if (mini != null) mini.hint(p.cx, p.cy);
        }
        missStreak = 0;
        pose = p;
        final MapLocator.Pose fp = p;
        final int cw = g.cols(), ch = g.rows();
        main.post(new Runnable() { public void run() {
            if (!mapOpen || panelOpen) return;
            markers.setPose(fp, cw, ch);
            showMarkers(true);
        } });
        prefs.edit().putFloat("lx", (float) p.cx).putFloat("ly", (float) p.cy).apply();
    }

    volatile int frames, lastW, lastH;
    volatile double lastScore = -1, lastK = -1;
    volatile String lastLocate = "-";

    public String diagnostics() {
        return "Dados carregados: " + (ready ? "sim" : (loading ? "carregando" : "NÃO")) + "\n"
                + "Quadros analisados: " + frames + " (" + lastW + "x" + lastH + ")\n"
                + String.format(java.util.Locale.ROOT, "Mapa aberto: %s (semelhança %.2f)%n", mapOpen ? "sim" : "não", lastScore)
                + String.format(java.util.Locale.ROOT, "Escala: %.2f | Localização: %s%n", lastK, lastLocate)
                + "Minimapa: " + lastMini + "\n"
                + "Itens filtrados: " + selected.size();
    }

    /** Aviso curto "Teyvat Radar ativo" ao entrar no jogo. */
    public void flashActive() {
        main.post(new Runnable() { public void run() {
            if (pillShown || buttonShown) return;
            pill.setText("\u2694  Teyvat Radar ativo \u00B7 abra o mapa");
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            pillLp.x = dp(4);
            pillLp.y = (int) (Math.min(b.width(), b.height()) * 0.30f);
            addSafe(pill, pillLp);
            main.postDelayed(new Runnable() { public void run() {
                if (!pillShown) removeSafe(pill);
            } }, 3500);
        } });
    }

    void processMini(Mat g) {
        if (mini == null || selected.isEmpty() || !prefs.getBoolean("minimap", true)) {
            if (miniShown) hideMini();
            return;
        }
        float[] er = miniView.drawnCapH == g.rows() ? miniView.drawnCap : null;
        boolean ok = mini.update(g, er, miniView.drawnRadiusCap);
        lastMini = String.format(java.util.Locale.ROOT, "%s (semelhança %.2f)", ok ? "rastreando" : "procurando", mini.lastCorr);
        if (ok) {
            final double px = mini.px, py = mini.py;
            final int ch = g.rows();
            main.post(new Runnable() { public void run() {
                if (mapOpen || panelOpen) return;
                miniView.setMini(mini.cx, mini.cy, mini.r, mini.k, px, py, ch);
                if (!miniShown) { addSafe(miniView, miniLp); miniShown = true; }
            } });
        } else if (mini.misses >= 2 && miniShown) {
            hideMini();
        }
    }

    void hideMini() {
        main.post(new Runnable() { public void run() {
            removeSafe(miniView);
            miniView.drawnCap = new float[0];
            miniShown = false;
        } });
    }

    MapLocator.Pose lastSavedPose() {
        if (!prefs.contains("lx")) return null;
        MapLocator.Pose p = new MapLocator.Pose();
        p.cx = prefs.getFloat("lx", 0);
        p.cy = prefs.getFloat("ly", 0);
        return p;
    }

    void miss() {
        if (++missStreak >= 4) {
            pose = null;
            main.post(new Runnable() { public void run() { showMarkers(false); } });
        }
    }

    void setMapOpen(final boolean open) {
        if (mapOpen == open) return;
        mapOpen = open;
        if (open) justOpened = true;
        if (!open) pose = null;
        main.post(new Runnable() { public void run() {
            showButton(open);
            if (!open) showMarkers(false);
        } });
    }

    /** O jogo saiu da tela (outro app em primeiro plano): esconde tudo. */
    public void hideAll() {
        openStreak = closedStreak = 0;
        mapOpen = false;
        pose = null;
        main.post(new Runnable() { public void run() {
            if (panelOpen) closePanel();
            showButton(false);
            showMarkers(false);
            removeSafe(miniView);
            miniView.drawnCap = new float[0];
            miniShown = false;
        } });
    }

    public void showPaused(final boolean on) {
        main.post(new Runnable() { public void run() {
            if (on) {
                hideAll();
                pill.setText("\u23F8  Radar pausado \u00B7 toque para religar");
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                pillLp.x = dp(4);
                pillLp.y = (int) (Math.min(b.width(), b.height()) * 0.30f);
                addSafe(pill, pillLp);
            } else {
                removeSafe(pill);
            }
            pillShown = on;
        } });
    }

    // ----------------------------------------------------------- janelas

    int dp(float v) { return Math.round(v * svc.getResources().getDisplayMetrics().density); }

    WindowManager.LayoutParams params(int w, int h, int flags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, winType,
                flags | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        return p;
    }

    void setupViews() {
        markers = new MarkerView(ui);
        mkLp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        // fora da acessibilidade, o Android so deixa o toque passar se a janela tiver <= 80% de opacidade
        mkLp.alpha = trusted ? 1f : 0.8f;
        miniView = new MarkerView(ui, true);
        miniLp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        miniLp.alpha = trusted ? 1f : 0.8f;

        pill = new TextView(ui);
        pill.setText("⏸  Radar pausado · toque para religar");
        pill.setTextSize(13);
        pill.setTextColor(0xFFFFE6A8);
        pill.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xE61E2235);
        pg.setCornerRadius(dp(20));
        pg.setStroke(dp(1), 0xFFD4AF37);
        pill.setBackground(pg);
        pillLp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        pill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (host != null) host.requestResume(); }
        });

        button = new TextView(ui);
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(0xFFFFE6A8);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xE61E2235);
        g.setStroke(dp(2), 0xFFD4AF37);
        button.setBackground(g);
        btnLp = params(dp(48), dp(48),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        button.setOnTouchListener(new View.OnTouchListener() {
            float sy; int oy; boolean moved;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sy = e.getRawY(); oy = btnLp.y; moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - sy;
                        if (Math.abs(dy) > dp(6)) moved = true;
                        if (moved) { btnLp.y = Math.max(0, (int) (oy + dy)); updateSafe(button, btnLp); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) prefs.edit().putInt("btnY", btnLp.y).apply(); else openPanel();
                        return true;
                }
                return false;
            }
        });
        refreshButton();
    }

    void refreshButton() {
        button.setText(selected.isEmpty() ? "⚔" : String.valueOf(selected.size()));
    }

    void showButton(boolean on) {
        if (on == buttonShown) return;
        if (on) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            btnLp.x = dp(4);
            btnLp.y = prefs.getInt("btnY", (int) (Math.min(b.width(), b.height()) * 0.70f));
            refreshButton();
            addSafe(button, btnLp);
        } else {
            removeSafe(button);
        }
        buttonShown = on;
    }

    void showMarkers(boolean on) {
        if (on == markersShown) return;
        if (on) addSafe(markers, mkLp); else removeSafe(markers);
        markersShown = on;
    }

    void openPanel() {
        if (panelOpen || data == null) return;
        Rect b = wm.getMaximumWindowMetrics().getBounds();
        int w = (int) (b.width() * 0.74f), h = (int) (b.height() * 0.88f);
        panel = new FilterPanel(ui, data, selected, w, new FilterPanel.Listener() {
            public void onApply(Set<Integer> s) {
                selected = s;
                HashSet<String> ss = new HashSet<>();
                for (Integer i : s) ss.add(String.valueOf(i));
                prefs.edit().putStringSet("selected", ss).apply();
                rebuildLayers();
                closePanel();
                toast(s.isEmpty() ? "Filtros removidos" : "Mostrando " + s.size() + " item(ns) no mapa");
            }
            public void onClose() { closePanel(); }
        });
        panelLp = params(w, h, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        panelLp.gravity = Gravity.CENTER;
        panelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        panelOpen = true;
        showMarkers(false);
        removeSafe(button);
        buttonShown = false;
        addSafe(panel, panelLp);
    }

    void closePanel() {
        removeSafe(panel);
        panel = null;
        openStreak = closedStreak = 0;
        mapOpen = false;          // forca nova checagem: o botao volta se o mapa seguir aberto
        pose = null;
        panelOpen = false;
    }

    void rebuildLayers() {
        List<MarkerView.Layer> ls = new ArrayList<>();
        if (data != null) for (Integer id : selected) {
            float[] xy = data.points.get(id);
            if (xy == null) continue;
            MarkerView.Layer L = new MarkerView.Layer();
            L.id = id;
            L.xy = xy;
            L.icon = data.icon(id);
            ls.add(L);
        }
        markers.setLayers(ls);
        miniView.setLayers(ls);
        refreshButton();
    }

    void addSafe(View v, WindowManager.LayoutParams p) {
        try { if (v != null && v.getParent() == null) wm.addView(v, p); } catch (Exception e) { Log.e(TAG, "add", e); }
    }

    void removeSafe(View v) {
        try { if (v != null && v.getParent() != null) wm.removeView(v); } catch (Exception ignored) { }
    }

    void updateSafe(View v, WindowManager.LayoutParams p) {
        try { if (v.getParent() != null) wm.updateViewLayout(v, p); } catch (Exception ignored) { }
    }

    void toast(String s) { Toast.makeText(svc, s, Toast.LENGTH_SHORT).show(); }
}
