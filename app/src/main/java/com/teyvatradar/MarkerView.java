package com.teyvatradar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Camada transparente, em tela cheia e sem toque, que desenha os icones no mapa. */
public class MarkerView extends View {

    static final int MAX_DRAWN = 900;

    public static class Layer {
        int id;
        float[] xy;     // pontos no zoom 0
        String[] keys;  // chaves para a lista de ocultos
        Bitmap icon;
    }

    final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final float r;
    List<Layer> layers = new ArrayList<>();
    HiddenStore hidden;
    MapLocator.Pose pose;
    int capW, capH;

    /** Posicoes desenhadas no ultimo quadro, em px da captura (para mascarar). */
    public volatile float[] drawnCap = new float[0];
    public volatile float drawnRadiusCap = 0;
    public volatile int drawnCapH = 0;

    // modo minimapa
    boolean mini;
    double mCx, mCy, mR, mK, mPx = Double.NaN, mPy;

    public MarkerView(Context c) { this(c, false); }

    public MarkerView(Context c, boolean miniMode) {
        super(c);
        mini = miniMode;
        r = (miniMode ? 5.5f : 15) * getResources().getDisplayMetrics().density;
        bg.setColor(0xEE12141F);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth((miniMode ? 1.0f : 2.2f) * getResources().getDisplayMetrics().density);
        ring.setColor(0xFFD4AF37);
        if (miniMode) {                 // minimapa: menor e translucido, para o mapa continuar visivel
            bg.setColor(0x7012141F);
            ring.setColor(0xB0D4AF37);
            img.setAlpha(170);
        }
    }

    public void setLayers(List<Layer> l) {
        layers = l;
        invalidate();
    }

    public void setPose(MapLocator.Pose p, int capW, int capH) {
        pose = p;
        this.capW = capW;
        this.capH = capH;
        invalidate();
    }

    /** Minimapa: geometria (fracoes da altura), escala e posicao do personagem. */
    public void setMini(double cx, double cy, double rr, double k, double px, double py, int capH) {
        mCx = cx; mCy = cy; mR = rr; mK = k; mPx = px; mPy = py; this.capH = capH;
        invalidate();
    }

    void drawMini(Canvas c) {
        if (Double.isNaN(mPx) || layers.isEmpty() || capH == 0) { drawnCap = new float[0]; return; }
        float H = getHeight();
        float sc = H / capH;
        float ccx = (float) (mCx * H), ccy = (float) (mCy * H), rad = (float) (mR * H * 0.93) - r * 0.6f;
        double perScreenPx = mK * MapLocator.CAP_H / H;
        float[] out = new float[MAX_DRAWN * 2];
        int n = 0;
        RectF dst = new RectF();
        outer:
        for (Layer L : layers) {
            for (int i = 0; i < L.xy.length; i += 2) {
                if (hidden != null && L.keys != null && hidden.isHidden(L.keys[i / 2])) continue;
                float x = (float) ((L.xy[i] - mPx) / perScreenPx + ccx);
                float y = (float) ((L.xy[i + 1] - mPy) / perScreenPx + ccy);
                if (Math.hypot(x - ccx, y - ccy) > rad) continue;
                c.drawCircle(x, y, r, bg);
                if (L.icon != null) {
                    float ir = r * 0.82f;
                    dst.set(x - ir, y - ir, x + ir, y + ir);
                    c.drawBitmap(L.icon, null, dst, img);
                }
                c.drawCircle(x, y, r, ring);
                out[2 * n] = x / sc;
                out[2 * n + 1] = y / sc;
                if (++n >= MAX_DRAWN) break outer;
            }
        }
        float[] d = new float[2 * n];
        System.arraycopy(out, 0, d, 0, 2 * n);
        drawnCap = d;
        drawnRadiusCap = (r + 2) / sc;
        drawnCapH = capH;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mini) { drawMini(c); return; }
        MapLocator.Pose p = pose;
        if (p == null || layers.isEmpty() || capH == 0) { drawnCap = new float[0]; return; }
        float W = getWidth(), H = getHeight();
        float sc = H / capH;                         // captura -> tela
        double perScreenPx = p.k / sc;              // px do mapa por px da tela
        float[] out = new float[MAX_DRAWN * 2];
        int n = 0;
        RectF dst = new RectF();
        outer:
        for (Layer L : layers) {
            for (int i = 0; i < L.xy.length; i += 2) {
                if (hidden != null && L.keys != null && hidden.isHidden(L.keys[i / 2])) continue;
                float x = (float) ((L.xy[i] - p.cx) / perScreenPx + W / 2);
                float y = (float) ((L.xy[i + 1] - p.cy) / perScreenPx + H / 2);
                if (x < -r || y < -r || x > W + r || y > H + r) continue;
                c.drawCircle(x, y, r, bg);
                if (L.icon != null) {
                    float ir = r * 0.82f;
                    dst.set(x - ir, y - ir, x + ir, y + ir);
                    c.drawBitmap(L.icon, null, dst, img);
                }
                c.drawCircle(x, y, r, ring);
                out[2 * n] = x / sc;
                out[2 * n + 1] = y / sc;
                if (++n >= MAX_DRAWN) break outer;
            }
        }
        float[] d = new float[2 * n];
        System.arraycopy(out, 0, d, 0, 2 * n);
        drawnCap = d;
        drawnRadiusCap = (r + 3) / sc;
        drawnCapH = capH;
    }
}
