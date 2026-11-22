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
        Bitmap icon;
    }

    final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final float r;
    List<Layer> layers = new ArrayList<>();
    MapLocator.Pose pose;
    int capW, capH;

    /** Posicoes desenhadas no ultimo quadro, em px da captura (para mascarar). */
    public volatile float[] drawnCap = new float[0];
    public volatile float drawnRadiusCap = 0;
    public volatile int drawnCapH = 0;

    public MarkerView(Context c) {
        super(c);
        r = 15 * getResources().getDisplayMetrics().density;
        bg.setColor(0xEE12141F);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.2f * getResources().getDisplayMetrics().density);
        ring.setColor(0xFFD4AF37);
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

    @Override
    protected void onDraw(Canvas c) {
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
