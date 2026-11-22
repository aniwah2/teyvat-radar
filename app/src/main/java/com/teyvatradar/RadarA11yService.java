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
    static final String[] GENSHIN = {"com.miHoYo.GenshinImpact", "com.miHoYo.Yuanshen"};

    public static volatile RadarA11yService instance;

    RadarEngine engine;
    SharedPreferences prefs;
    volatile boolean gameOnTop = false, shooting;
    String homePkg;
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
        boolean genshin = false;
        for (String g : GENSHIN) if (g.equals(pkg)) genshin = true;
        if (genshin) {
            if (!gameOnTop) { gameOnTop = true; schedule(100); }
        } else if (!pkg.equals(getPackageName()) && isLauncherApp(pkg)) {
            // outro app de verdade veio para a frente (ignora teclado, barra de notificacao, etc.)
            if (gameOnTop) { gameOnTop = false; if (engine != null) engine.hideAll(); }
        }
    }

    boolean isLauncherApp(String pkg) {
        Boolean b = isApp.get(pkg);
        if (b == null) {
            if (homePkg == null) {
                android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
                homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
            }
            b = pkg.equals(homePkg) || (getPackageManager().getLaunchIntentForPackage(pkg) != null
                    && !pkg.equals("com.android.systemui"));
            isApp.put(pkg, b);
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
            if (engine == null || !gameOnTop || !enabledByUser(prefs) || shooting) return;
            if (!engine.ready) { schedule(1000); return; }
            if (engine.panelOpen) { schedule(INTERVAL_MS); return; }
            shooting = true;
            final long t0 = SystemClock.uptimeMillis();
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, exec, new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult r) {
                        failures = 0;
                        try {
                            Mat g = toGray(r);
                            if (g != null) {
                                engine.process(g);
                                g.release();
                            }
                        } catch (Throwable e) {
                            Log.e(TAG, "process", e);
                        }
                        shooting = false;
                        schedule(Math.max(0, INTERVAL_MS - (SystemClock.uptimeMillis() - t0)));
                    }
                    @Override public void onFailure(int code) {
                        shooting = false;
                        failures++;
                        schedule(failures > 5 ? 2000 : INTERVAL_MS);   // tela desligada etc.
                    }
                });
            } catch (Throwable e) {
                shooting = false;
                Log.e(TAG, "takeScreenshot", e);
                schedule(2000);
            }
        }
    };

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
