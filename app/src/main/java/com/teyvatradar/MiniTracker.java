package com.teyvatradar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;

import org.json.JSONObject;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;

/**
 * Acha a posicao do personagem pelo minimapa (canto superior esquerdo do jogo).
 * O minimapa e o mesmo desenho do mapa, com norte para cima e escala fixa: basta
 * casar o recorte dele com o mapa de referencia (template matching).
 *  - rastreamento: janela pequena em volta da ultima posicao (mapa em 1/4, "z-2");
 *  - relocalizacao (teleporte, inicio): busca no mapa todo em 1/8 ("z-3") e refina.
 */
public class MiniTracker {

    public final double cx, cy, r, k, minCorr;    // geometria relativa a altura da tela
    final double tplFrac;
    final Mat z3;                                  // mapa inteiro em 1/8, cinza
    final BitmapRegionDecoder dec2;                // mapa em 1/4, decodificado por regioes
    final int z2W, z2H;
    Mat win2;                                      // regiao decodificada em cache
    Rect win2Rect;

    public double px = Double.NaN, py = Double.NaN; // posicao do personagem (zoom 0)
    public double lastCorr;
    int misses;
    long lastGlobal;

    public MiniTracker(JSONObject spec, File dataDir) throws Exception {
        JSONObject m = spec.getJSONObject("minimap");
        cx = m.getDouble("cx"); cy = m.getDouble("cy"); r = m.getDouble("r");
        k = m.getDouble("k"); tplFrac = m.getDouble("tpl"); minCorr = m.getDouble("min_corr");
        File f3 = new File(dataDir, "map_z-3.jpg"), f2 = new File(dataDir, "map_z-2.jpg");
        if (!f3.exists() || !f2.exists()) throw new IllegalStateException("mapas de referencia ausentes");
        z3 = Imgcodecs.imread(f3.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
        @SuppressWarnings("deprecation") BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(f2.getAbsolutePath(), false);
        dec2 = d;
        z2W = dec2.getWidth();
        z2H = dec2.getHeight();
    }

    public boolean hasPosition() { return !Double.isNaN(px); }

    public void reset() { px = py = Double.NaN; misses = 0; }

    /** Palpite vindo do mapa grande (ele abre centralizado no personagem). */
    public void hint(double x, double y) { px = x; py = y; misses = 0; }

    /**
     * @param g     captura em cinza, altura CAP_H
     * @param erase pontos (px da captura) onde desenhamos icones: apagados do recorte
     * @return true se a posicao foi atualizada com confianca
     */
    public boolean update(Mat g, float[] erase, float eraseR) {
        int H = g.rows();
        int c0x = (int) Math.round(cx * H), c0y = (int) Math.round(cy * H), h = (int) Math.round(r * tplFrac * H);
        if (c0x - h < 0 || c0y - h < 0 || c0x + h > g.cols() || c0y + h > H) return false;
        Mat crop = g.submat(new Rect(c0x - h, c0y - h, 2 * h, 2 * h)).clone();
        // apaga nossos proprios icones (ficam por cima do minimapa na captura)
        if (erase != null && erase.length > 0) {
            Scalar mean = Core.mean(crop);
            for (int i = 0; i + 1 < erase.length; i += 2)
                Imgproc.circle(crop, new Point(erase[i] - (c0x - h), erase[i + 1] - (c0y - h)), Math.round(eraseR), mean, -1);
        }
        double s = k * MapLocator.CAP_H / H;          // px do zoom 0 por px da captura
        boolean ok = false;
        try {
            if (hasPosition()) ok = matchLocal(crop, s, 60);
            if (!ok && hasPosition() && misses >= 2) ok = matchLocal(crop, s, 250);   // andou rapido / planou
            if (!ok) {
                long now = System.currentTimeMillis();
                if (now - lastGlobal > 2500 && (!hasPosition() || misses >= 3)) {
                    lastGlobal = now;
                    ok = matchGlobal(crop, s);
                }
            }
        } finally {
            crop.release();
        }
        if (ok) misses = 0; else misses++;
        if (misses >= 6) reset();
        return ok;
    }

    /** Busca na regiao (em px do mapa 1/4) em volta da posicao atual. */
    private boolean matchLocal(Mat crop, double s, int R) {
        Mat t = new Mat();
        Imgproc.resize(crop, t, new Size(), s / 4, s / 4, Imgproc.INTER_AREA);
        try {
            int x0 = (int) (px / 4 - R - t.cols() / 2.0), y0 = (int) (py / 4 - R - t.rows() / 2.0);
            int w = 2 * R + t.cols(), hh = 2 * R + t.rows();
            Mat win = region2(x0, y0, w, hh);
            if (win == null) return false;
            Mat res = new Mat();
            Imgproc.matchTemplate(win, t, res, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(res);
            res.release();
            lastCorr = mm.maxVal;
            if (mm.maxVal < minCorr) return false;
            px = (x0 + mm.maxLoc.x + t.cols() / 2.0) * 4;
            py = (y0 + mm.maxLoc.y + t.rows() / 2.0) * 4;
            return true;
        } finally {
            t.release();
        }
    }

    /** Busca no mapa inteiro (1/8) e refina localmente. */
    private boolean matchGlobal(Mat crop, double s) {
        Mat t = new Mat();
        Imgproc.resize(crop, t, new Size(), s / 8, s / 8, Imgproc.INTER_AREA);
        try {
            Mat res = new Mat();
            Imgproc.matchTemplate(z3, t, res, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(res);
            res.release();
            if (mm.maxVal < minCorr) { lastCorr = mm.maxVal; return false; }
            double oldX = px, oldY = py;
            px = (mm.maxLoc.x + t.cols() / 2.0) * 8;
            py = (mm.maxLoc.y + t.rows() / 2.0) * 8;
            if (matchLocal(crop, s, 20)) return true;
            px = oldX; py = oldY;
            return false;
        } finally {
            t.release();
        }
    }

    /** Recorte do mapa 1/4 em cinza, com cache de uma regiao maior. */
    private Mat region2(int x0, int y0, int w, int h) {
        if (x0 < 0 || y0 < 0 || x0 + w > z2W || y0 + h > z2H) return null;
        if (win2Rect == null || x0 < win2Rect.x || y0 < win2Rect.y
                || x0 + w > win2Rect.x + win2Rect.width || y0 + h > win2Rect.y + win2Rect.height) {
            int bw = Math.max(1024, w + 400), bh = Math.max(1024, h + 400);
            int bx = Math.max(0, Math.min(z2W - bw, x0 + w / 2 - bw / 2));
            int by = Math.max(0, Math.min(z2H - bh, y0 + h / 2 - bh / 2));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = dec2.decodeRegion(new android.graphics.Rect(bx, by, bx + bw, by + bh), o);
            if (b == null) return null;
            Mat rgba = new Mat();
            Utils.bitmapToMat(b, rgba);
            b.recycle();
            if (win2 != null) win2.release();
            win2 = new Mat();
            Imgproc.cvtColor(rgba, win2, Imgproc.COLOR_RGBA2GRAY);
            rgba.release();
            win2Rect = new Rect(bx, by, bw, bh);
        }
        return win2.submat(new Rect(x0 - win2Rect.x, y0 - win2Rect.y, w, h));
    }

    public void release() {
        if (z3 != null) z3.release();
        if (win2 != null) win2.release();
        dec2.recycle();
    }
}
