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
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
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
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Captura a tela algumas vezes por segundo. Quando o mapa do Genshin esta aberto,
 * mostra o botao do radar no canto esquerdo e desenha os itens filtrados no mapa.
 */
public class RadarService extends Service {

    static final String TAG = "TeyvatRadar";
    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data", ACTION_STOP = "com.teyvatradar.STOP";
    static final long TICK_MS = 220;

    public static volatile boolean running;

    Handler main, worker;
    HandlerThread workerThread;
    WindowManager wm;
    Context ui;
    SharedPreferences prefs;

    MediaProjection projection;
    VirtualDisplay vdisplay;
    ImageReader reader;
    int capW, capH;
    boolean landscape;

    DataStore data;
    MapLocator locator;
    volatile boolean ready;

    // janelas
    TextView button;
    WindowManager.LayoutParams btnLp;
    boolean buttonShown;
    MarkerView markers;
    WindowManager.LayoutParams mkLp;
    boolean markersShown;
    FilterPanel panel;
    WindowManager.LayoutParams panelLp;
    volatile boolean panelOpen;

    // estado
    Set<Integer> selected = new HashSet<>();
    int openStreak, closedStreak, missStreak;
    boolean mapOpen;
    MapLocator.Pose pose;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        main = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("radar-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        prefs = getSharedPreferences("radar", MODE_PRIVATE);
        for (String s : prefs.getStringSet("selected", new HashSet<String>())) {
            try { selected.add(Integer.parseInt(s)); } catch (Exception ignored) { }
        }
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Context wc = createWindowContext(dm.getDisplay(Display.DEFAULT_DISPLAY),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        ui = new ContextThemeWrapper(wc, android.R.style.Theme_DeviceDefault);
        wm = wc.getSystemService(WindowManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (projection == null && intent != null && intent.hasExtra(EXTRA_DATA)) {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            @SuppressWarnings("deprecation") Intent d = intent.getParcelableExtra(EXTRA_DATA);
            projection = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), d);
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(new Runnable() { public void run() { stopSelf(); } }); }
            }, main);
            setupViews();
            setupCapture();
            worker.post(new Runnable() { public void run() { loadData(); } });
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        worker.removeCallbacksAndMessages(null);
        main.removeCallbacksAndMessages(null);
        removeSafe(button); removeSafe(markers); removeSafe(panel);
        if (vdisplay != null) vdisplay.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        workerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        main.postDelayed(new Runnable() { public void run() { setupCapture(); } }, 300);
    }

    // ------------------------------------------------------------ dados

    void loadData() {
        try {
            OpenCVLoader.initDebug();
            data = new DataStore(this);
            data.load();
            locator = new MapLocator(this, data);
            ready = true;
            main.post(new Runnable() { public void run() {
                rebuildLayers();
                toast("Radar pronto. Abra o mapa do jogo.");
            } });
            worker.postDelayed(tick, TICK_MS);
        } catch (Throwable e) {
            Log.e(TAG, "loadData", e);
            main.post(new Runnable() { public void run() {
                toast("Não consegui carregar os dados do mapa. Abra o app e baixe os dados.");
                stopSelf();
            } });
        }
    }

    // ---------------------------------------------------------- captura

    void setupCapture() {
        if (projection == null) return;
        Rect b = wm.getMaximumWindowMetrics().getBounds();
        int sw = b.width(), sh = b.height();
        landscape = sw > sh;
        int short_ = Math.min(sw, sh);
        int w = Math.round(sw * (float) MapLocator.CAP_H / short_);
        int h = Math.round(sh * (float) MapLocator.CAP_H / short_);
        if (reader != null && w == capW && h == capH) return;
        ImageReader old = reader;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        capW = w;
        capH = h;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        if (vdisplay == null) {
            vdisplay = projection.createVirtualDisplay("teyvat-radar", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        } else {
            vdisplay.resize(w, h, dpi);
            vdisplay.setSurface(reader.getSurface());
        }
        if (old != null) old.close();
    }

    final Runnable tick = new Runnable() {
        public void run() {
            long t0 = System.currentTimeMillis();
            try { process(); } catch (Throwable e) { Log.e(TAG, "process", e); }
            long dt = System.currentTimeMillis() - t0;
            if (running) worker.postDelayed(this, Math.max(40, TICK_MS - dt));
        }
    };

    Mat grab() {
        ImageReader r = reader;
        if (r == null) return null;
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return null;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            Mat rgba = new Mat(h, p.getRowStride() / p.getPixelStride(), CvType.CV_8UC4, buf, p.getRowStride());
            Mat gray = new Mat();
            Imgproc.cvtColor(rgba.submat(0, h, 0, w), gray, Imgproc.COLOR_RGBA2GRAY);
            rgba.release();
            return gray;
        } catch (Exception e) {
            return null;
        } finally {
            if (img != null) img.close();
        }
    }

    void process() {
        if (!ready || panelOpen) return;
        Mat g = grab();
        if (g == null) return;
        try {
            if (!landscape || g.cols() < g.rows()) { setMapOpen(false); return; }
            boolean open = locator.mapOpenScore(g) > 0.75;
            if (open) { openStreak++; closedStreak = 0; } else { closedStreak++; openStreak = 0; }
            if (!mapOpen && openStreak >= 2) setMapOpen(true);
            if (mapOpen && closedStreak >= 2) setMapOpen(false);
            if (!mapOpen || !open || selected.isEmpty()) return;

            double k = locator.scaleFromKnob(g);
            if (k <= 0) { miss(); return; }
            Mat mask = MapLocator.uiMask(g.rows(), g.cols());
            // nao deixar nossos proprios icones e o botao confundirem o reconhecimento
            float[] d = markers.drawnCap;
            float rr = markers.drawnRadiusCap;
            for (int i = 0; i + 1 < d.length; i += 2)
                Imgproc.circle(mask, new Point(d[i], d[i + 1]), Math.round(rr), new Scalar(0), -1);

            MapLocator.Pose p = null;
            if (pose != null) p = locator.locate(g, mask, k, pose);
            if (p == null) {
                MapLocator.Pose last = lastSavedPose();
                if (pose == null && last != null) p = locator.locate(g, mask, k, last);
            }
            if (p == null) p = locator.locate(g, mask, k, null);
            mask.release();
            if (p == null) { miss(); return; }
            missStreak = 0;
            pose = p;
            final MapLocator.Pose fp = p;
            final int cw = g.cols(), ch = g.rows();
            main.post(new Runnable() { public void run() {
                markers.setPose(fp, cw, ch);
                showMarkers(true);
            } });
            prefs.edit().putFloat("lx", (float) p.cx).putFloat("ly", (float) p.cy).apply();
        } finally {
            g.release();
        }
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
        if (!open) pose = null;
        main.post(new Runnable() { public void run() {
            showButton(open);
            if (!open) showMarkers(false);
        } });
    }

    // ----------------------------------------------------------- janelas

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    WindowManager.LayoutParams overlayParams(int w, int h, int flags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        // camada de marcadores: tela cheia, sem toque (alpha <= 0.8 p/ o toque passar ao jogo)
        markers = new MarkerView(ui);
        mkLp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        mkLp.alpha = 0.8f;

        // botao do radar
        button = new TextView(ui);
        button.setText("⚔");
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(0xFFFFE6A8);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xE61E2235);
        g.setStroke(dp(2), 0xFFD4AF37);
        button.setBackground(g);
        btnLp = overlayParams(dp(48), dp(48),
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
    }

    void showButton(boolean on) {
        if (on == buttonShown) return;
        if (on) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            btnLp.x = dp(4);
            btnLp.y = prefs.getInt("btnY", (int) (Math.min(b.width(), b.height()) * 0.70f));
            addSafe(button, btnLp);
            button.setText(selected.isEmpty() ? "⚔" : String.valueOf(selected.size()));
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
                button.setText(s.isEmpty() ? "⚔" : String.valueOf(s.size()));
                toast(s.isEmpty() ? "Filtros removidos" : "Mostrando " + s.size() + " item(ns) no mapa");
            }
            public void onClose() { closePanel(); }
        });
        panelLp = overlayParams(w, h, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
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
        panelOpen = false;
        openStreak = closedStreak = 0;
        mapOpen = false;                    // forca nova checagem: botao volta se o mapa seguir aberto
        pose = null;
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

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ----------------------------------------------------- notificacao

    void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("radar", "Teyvat Radar", NotificationManager.IMPORTANCE_LOW));
        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, RadarService.class).setAction(ACTION_STOP), f);
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class), f);
        Notification n = new Notification.Builder(this, "radar")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("Teyvat Radar ativo")
                .setContentText("Abra o mapa do jogo: o botão aparece no canto esquerdo")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Desligar radar", stop).build())
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }
}
