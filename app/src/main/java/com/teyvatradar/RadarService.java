package com.teyvatradar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;
import android.view.WindowManager;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

/**
 * Modo alternativo: ve a tela pela captura de tela do Android (MediaProjection).
 * O Android so permite UMA captura por vez: se outro app (ex.: gravador) comecar,
 * o radar pausa e mostra "toque para religar".
 */
public class RadarService extends Service implements RadarEngine.Host {

    static final String TAG = "TeyvatRadar";
    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data", ACTION_STOP = "com.teyvatradar.STOP";
    static final long TICK_MS = 220;

    public static volatile boolean running, paused;

    RadarEngine engine;
    MediaProjection projection;
    VirtualDisplay vdisplay;
    ImageReader reader;
    int capW, capH;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        engine = new RadarEngine(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection == null && intent != null && intent.hasExtra(EXTRA_DATA)) {
            startInForeground(true);               // precisa vir antes de getMediaProjection
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            @SuppressWarnings("deprecation") Intent d = intent.getParcelableExtra(EXTRA_DATA);
            try { projection = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), d); }
            catch (Exception e) { Log.e(TAG, "projection", e); projection = null; }
            if (projection == null) { pause(); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { engine.worker.post(new Runnable() { public void run() { pause(); } }); }
            }, engine.worker);
            paused = false;
            engine.showPaused(false);
            setupCapture();
            engine.worker.removeCallbacks(tick);
            engine.worker.postDelayed(tick, TICK_MS);
        } else if (projection == null) {
            pause();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        paused = false;
        engine.worker.removeCallbacks(tick);
        releaseCapture();
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        engine.destroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        engine.worker.postDelayed(new Runnable() { public void run() { setupCapture(); } }, 300);
    }

    void pause() {
        paused = true;
        engine.worker.removeCallbacks(tick);
        releaseCapture();
        projection = null;
        engine.showPaused(true);
        startInForeground(false);
    }

    @Override
    public void requestResume() {
        Intent i = new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { Log.e(TAG, "resume", e); }
    }

    // ---------------------------------------------------------- captura

    void releaseCapture() {
        if (vdisplay != null) { try { vdisplay.release(); } catch (Exception ignored) { } vdisplay = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) { } reader = null; }
        capW = capH = 0;
    }

    void setupCapture() {
        if (projection == null) return;
        Rect b = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        int sw = b.width(), sh = b.height(), s = Math.min(sw, sh);
        int w = Math.round(sw * (float) MapLocator.CAP_H / s), h = Math.round(sh * (float) MapLocator.CAP_H / s);
        if (reader != null && w == capW && h == capH) return;
        ImageReader old = reader;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        capW = w;
        capH = h;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        try {
            if (vdisplay == null) {
                vdisplay = projection.createVirtualDisplay("teyvat-radar", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
            } else {
                vdisplay.resize(w, h, dpi);
                vdisplay.setSurface(reader.getSurface());
            }
        } catch (Exception e) {
            Log.e(TAG, "capture", e);
            pause();
        }
        if (old != null) old.close();
    }

    final Runnable tick = new Runnable() {
        public void run() {
            long t0 = System.currentTimeMillis();
            Mat g = grab();
            if (g != null) {
                try { engine.process(g); } catch (Throwable e) { Log.e(TAG, "process", e); }
                g.release();
            }
            long dt = System.currentTimeMillis() - t0;
            if (running && !paused) engine.worker.postDelayed(this, Math.max(40, TICK_MS - dt));
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

    // ----------------------------------------------------- notificacao

    void startInForeground(boolean capturing) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("radar", "Teyvat Radar", NotificationManager.IMPORTANCE_LOW));
        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RadarService.class).setAction(ACTION_STOP), f);
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class), f);
        PendingIntent resume = PendingIntent.getActivity(this, 4, new Intent(this, CaptureActivity.class), f);
        Notification.Builder nb = new Notification.Builder(this, "radar")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(capturing ? "Teyvat Radar ativo (captura de tela)" : "Teyvat Radar pausado")
                .setContentText(capturing ? "Abra o mapa do jogo: o botão aparece no canto esquerdo"
                        : "Outra gravação de tela assumiu. Toque em Religar quando terminar.")
                .setContentIntent(capturing ? open : resume)
                .setOngoing(true);
        if (!capturing) nb.addAction(new Notification.Action.Builder((Icon) null, "Religar radar", resume).build());
        nb.addAction(new Notification.Action.Builder((Icon) null, "Desligar radar", stop).build());
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                | (capturing ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0);
        try {
            startForeground(1, nb.build(), type);
        } catch (Exception e) {
            Log.e(TAG, "startForeground", e);
            try { startForeground(1, nb.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); } catch (Exception ignored) { }
        }
    }
}
