package com.teyvatradar;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Modo principal: ve a tela pelo servico de Acessibilidade (takeScreenshot).
 * Nao usa o recurso de gravacao de tela, entao nao conflita com o gravador do celular.
 * Liga sozinho quando o Genshin esta em primeiro plano e para quando voce sai do jogo.
 */
public class RadarA11yService extends AccessibilityService {

    static final String TAG = "TeyvatRadar";
    static final long INTERVAL_MS = 350;          // o Android limita a ~3 capturas por segundo
    static final long IDLE_MS = 2500;
    static final String[] GENSHIN = {"com.miHoYo.GenshinImpact", "com.miHoYo.Yuanshen"};

    public static volatile RadarA11yService instance;

    RadarEngine engine;
    SharedPreferences prefs;
    volatile boolean gameOnTop = false, shooting;
    String homePkg;
    volatile String lastEvent = "";
    volatile int shotsOk, shotsFail, lastFailCode = -1;
    final Map<String, Boolean> isApp = new HashMap<>();
    Executor exec;
    int failures;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = getSharedPreferences("radar", MODE_PRIVATE);
        engine = new RadarEngine(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
        exec = new Executor() { public void execute(Runnable r) { engine.worker.post(r); } };
        schedule(800);
        autoUpdateData();
    }

    /** Atualiza os dados do mapa sozinho (no maximo a cada 6 h), sem precisar abrir o app. */
    void autoUpdateData() {
        long last = prefs.getLong("lastDataCheck", 0);
        final DataStore d = new DataStore(this);
        boolean needsMaps = !new java.io.File(d.dir, "map_z-3.jpg").exists();
        if (!needsMaps && System.currentTimeMillis() - last < 6 * 3600_000L) return;
        new Thread(new Runnable() { public void run() {
            try {
                String remote = DataStore.remoteVersion();
                prefs.edit().putLong("lastDataCheck", System.currentTimeMillis()).apply();
                if (!remote.equals(d.localVersion()) || !d.isReady()) {
                    d.download(null);
                    if (engine != null) engine.reload();
                }
            } catch (Exception e) {
                Log.e(TAG, "autoUpdate", e);
            }
        } }).start();
    }

    public static boolean enabledByUser(SharedPreferences p) { return p.getBoolean("enabled", true); }

    /** Chamado pelo app quando o usuario liga/desliga o radar ou baixa dados novos. */
    public void onSettingsChanged(boolean dataChanged) {
        if (engine == null) return;
        if (dataChanged) engine.reload();
        if (!enabledByUser(prefs)) engine.hideAll();
        else schedule(100);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        String cls = e.getClassName() != null ? e.getClassName().toString() : "";
        lastEvent = pkg + "/" + cls;
        boolean genshin = false;
        for (String g : GENSHIN) if (g.equals(pkg)) genshin = true;
        if (genshin) {
            if (!gameOnTop) {
                gameOnTop = true;
                failures = 0;
                if (engine != null) engine.flashActive();
                schedule(100);
            }
        } else if (!pkg.equals(getPackageName()) && isRealAppScreen(pkg, cls)) {
            // outro app abriu uma TELA de verdade. Janelas flutuantes (gravador, Game Turbo,
            // bolhas, teclado, notificacoes) nao contam.
            if (gameOnTop) { gameOnTop = false; if (engine != null) engine.hideAll(); }
        }
    }

    /** True se (pkg, cls) e uma Activity (tela cheia de um app), ou a tela inicial. */
    boolean isRealAppScreen(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean b = isApp.get(key);
        if (b == null) {
            if (homePkg == null) {
                android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
                homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
            }
            boolean activity = false;
            if (!cls.isEmpty()) {
                try {
                    activity = getPackageManager().getActivityInfo(new android.content.ComponentName(pkg, cls), 0) != null;
                } catch (Exception ignored) { }
            }
            b = activity && !pkg.equals("com.android.systemui");
            isApp.put(key, b);
        }
        return b;
    }

    void schedule(long delay) {
        if (engine == null) return;
        engine.worker.removeCallbacks(shot);
        engine.worker.postDelayed(shot, delay);
    }

    final Runnable shot = new Runnable() {
        public void run() {
            if (engine == null || !enabledByUser(prefs) || shooting) return;
            if (!engine.ready) { schedule(1000); return; }
            if (engine.panelOpen) { schedule(INTERVAL_MS); return; }
            shooting = true;
            final long t0 = SystemClock.uptimeMillis();
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, exec, new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult r) {
                        failures = 0;
                        shotsOk++;
                        try {
                            Mat g = toGray(r);
                            if (g != null && g.cols() < g.rows() && displayIsLandscape()) {
                                Mat rot = new Mat();
                                org.opencv.core.Core.rotate(g, rot, rotationGuess);
                                g.release();
                                g = rot;
                            }
                            if (g != null) {
                                engine.process(g);
                                g.release();
                            }
                        } catch (Throwable e) {
                            Log.e(TAG, "process", e);
                        }
                        shooting = false;
                        // rapido com o jogo na frente; devagar fora dele (rede de seguranca:
                        // se o mapa aparecer sem o aviso do Android, o radar acorda sozinho)
                        if (!gameOnTop && engine.mapOpen) gameOnTop = true;
                        long iv = gameOnTop ? INTERVAL_MS : IDLE_MS;
                        schedule(Math.max(0, iv - (SystemClock.uptimeMillis() - t0)));
                    }
                    @Override public void onFailure(int code) {
                        shooting = false;
                        failures++;
                        shotsFail++;
                        lastFailCode = code;
                        schedule(failures > 5 || !gameOnTop ? IDLE_MS : INTERVAL_MS);   // tela desligada etc.
                    }
                });
            } catch (Throwable e) {
                shooting = false;
                Log.e(TAG, "takeScreenshot", e);
                schedule(2000);
            }
        }
    };

    int rotationGuess = org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE;

    boolean displayIsLandscape() {
        try {
            android.hardware.display.DisplayManager dm = getSystemService(android.hardware.display.DisplayManager.class);
            int r = dm.getDisplay(Display.DEFAULT_DISPLAY).getRotation();
            rotationGuess = r == android.view.Surface.ROTATION_270
                    ? org.opencv.core.Core.ROTATE_90_CLOCKWISE : org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE;
            return r == android.view.Surface.ROTATION_90 || r == android.view.Surface.ROTATION_270;
        } catch (Exception e) {
            return false;
        }
    }

    /** Texto de diagnostico mostrado no app. */
    public String diagnostics() {
        StringBuilder b = new StringBuilder();
        b.append("Jogo na tela: ").append(gameOnTop ? "sim" : "não").append('\n');
        b.append("Último evento: ").append(lastEvent).append('\n');
        b.append("Capturas: ").append(shotsOk).append(" ok, ").append(shotsFail).append(" falhas");
        if (lastFailCode >= 0) b.append(" (código ").append(lastFailCode).append(')');
        b.append('\n');
        if (engine != null) b.append(engine.diagnostics());
        return b.toString();
    }

    /** Converte a captura (tela cheia) para cinza com altura CAP_H. */
    static Mat toGray(ScreenshotResult r) {
        HardwareBuffer hb = r.getHardwareBuffer();
        try {
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
            if (hw == null) return null;
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            int h = Math.min(sw.getWidth(), sw.getHeight());
            float f = (float) MapLocator.CAP_H / h;
            Bitmap small = Bitmap.createScaledBitmap(sw, Math.round(sw.getWidth() * f), Math.round(sw.getHeight() * f), true);
            sw.recycle();
            Mat rgba = new Mat();
            Utils.bitmapToMat(small, rgba);
            small.recycle();
            Mat gray = new Mat();
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            rgba.release();
            return gray;
        } finally {
            hb.close();
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    void shutdown() {
        instance = null;
        if (engine != null) { engine.destroy(); engine = null; }
    }

    public static boolean isConnected() { return instance != null; }
}
