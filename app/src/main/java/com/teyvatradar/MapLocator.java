package com.teyvatradar;

import android.content.Context;

import org.json.JSONObject;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconhece o mapa do Genshin numa captura de tela (altura fixa CAP_H):
 *  1) se o mapa esta aberto (X de fechar + engrenagem nos lugares de sempre);
 *  2) a escala, pela posicao do controle da barra de zoom;
 *  3) a posicao, casando pontos ORB da tela com a assinatura do mapa de referencia.
 */
public class MapLocator {

    public static final int CAP_H = 640;

    /** Resultado: centro da tela em pixels do mapa (zoom 0) e quantos px do mapa valem 1 px da captura. */
    public static class Pose {
        public double cx, cy, k;
        public int votes;
        public boolean global;
    }

    final DataStore data;
    final Mat tplClose, tplGear, tplKnob;
    final JSONObject spec;
    final Mat refDes;                 // todas as descricoes (n x 32)
    final float[] refXY;              // em px da referencia (zoom -2)
    final int factor;                 // ref -> zoom 0
    final ORB orb;
    final BFMatcher bf;

    // grade espacial para busca local
    static final int CELL = 512;
    int gw, gh;
    int[] cellStart, cellIdx;

    public MapLocator(Context c, DataStore d) throws Exception {
        data = d;
        spec = new JSONObject(new String(asset(c, "tpl/spec.json"), "UTF-8"));
        tplClose = decode(asset(c, "tpl/close_x.png"));
        tplGear = decode(asset(c, "tpl/gear.png"));
        tplKnob = decode(asset(c, "tpl/knob.png"));
        refXY = d.featXY;
        factor = d.featFactor;
        refDes = new Mat(d.featCount, 32, CvType.CV_8UC1);
        refDes.put(0, 0, d.featDes);
        orb = ORB.create(3000, 1.3f, 3, 31, 0, 2, 0, 31, 8);
        bf = BFMatcher.create(Core.NORM_HAMMING, false);
        buildGrid();
    }

    // -------------------------------------------------------- mapa aberto?

    /** Retorna a menor das duas semelhancas (0..1). Mapa aberto se > ~0.75. */
    public double mapOpenScore(Mat gray) {
        try {
            JSONObject r = spec.getJSONObject("regions");
            return Math.min(regionScore(gray, r.getJSONObject("close_x"), tplClose),
                    regionScore(gray, r.getJSONObject("gear"), tplGear));
        } catch (Exception e) {
            return 0;
        }
    }

    private double regionScore(Mat g, JSONObject r, Mat tpl) throws Exception {
        int H = g.rows(), W = g.cols();
        double x = "right".equals(r.getString("anchor")) ? W - r.getDouble("x") * H : r.getDouble("x") * H;
        int x0 = clamp((int) (x - 0.03 * H), 0, W - 1);
        int y0 = clamp((int) ((r.getDouble("y") - 0.02) * H), 0, H - 1);
        int x1 = clamp((int) (x + (r.getDouble("w") + 0.03) * H), x0 + tpl.cols(), W);
        int y1 = clamp((int) ((r.getDouble("y") + r.getDouble("h") + 0.02) * H), y0 + tpl.rows(), H);
        if (x1 - x0 < tpl.cols() || y1 - y0 < tpl.rows()) return 0;
        Mat roi = g.submat(new Rect(x0, y0, x1 - x0, y1 - y0));
        Mat res = new Mat();
        Imgproc.matchTemplate(roi, tpl, res, Imgproc.TM_CCOEFF_NORMED);
        double v = Core.minMaxLoc(res).maxVal;
        res.release();
        return v;
    }

    // --------------------------------------------------------------- escala

    /** px do mapa (zoom 0) por px da captura, lido da barra de zoom; -1 se nao achou. */
    public double scaleFromKnob(Mat g) {
        try {
            JSONObject k = spec.getJSONObject("knob");
            int H = g.rows();
            int x0 = (int) (k.getDouble("x0") * H), x1 = (int) (k.getDouble("x1") * H);
            int b0 = (int) (k.getJSONArray("band").getDouble(0) * H);
            int b1 = (int) (k.getJSONArray("band").getDouble(1) * H);
            if (x1 - x0 < tplKnob.cols()) x1 = x0 + tplKnob.cols();
            Mat roi = g.submat(new Rect(x0, b0, x1 - x0, b1 - b0));
            Mat res = new Mat();
            Imgproc.matchTemplate(roi, tplKnob, res, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(res);
            res.release();
            if (mm.maxVal < 0.4) return -1;
            double y = (mm.maxLoc.y + k.getDouble("half") * H + b0) / H;
            double half = k.getDouble("scale_a") + k.getDouble("scale_b") * (y - k.getDouble("scale_y0"));
            double sFull = Math.max(0.15, half / 2.0);           // normalizado p/ altura 1280
            return sFull * 1280.0 / H;
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------- posicao

    /** Mascara com as areas de interface do jogo (onde nao ha mapa). */
    public static Mat uiMask(int H, int W) {
        Mat m = new Mat(H, W, CvType.CV_8UC1, new Scalar(255));
        double[][] z = {{0, 0, 0.245, 0.37}, {0, 0, 0.09, 1}, {0.47, 0, 1, 0.10},
                {0.84, 0, 1, 0.20}, {0.66, 0.86, 1, 1}, {0.38, 0.88, 0.63, 1}};
        for (double[] r : z)
            Imgproc.rectangle(m, new Point(r[0] * W, r[1] * H), new Point(r[2] * W, r[3] * H), new Scalar(0), -1);
        return m;
    }

    /**
     * @param k     px do mapa por px da captura (da barra de zoom)
     * @param mask  areas a ignorar (interface + nossos marcadores)
     * @param prior posicao anterior (busca local) ou null (busca no mapa todo)
     */
    public Pose locate(Mat g, Mat mask, double k, Pose prior) {
        double fct = k / factor;                                  // captura -> referencia
        Mat small = new Mat(), smask = new Mat();
        Imgproc.resize(g, small, new Size(), fct, fct, Imgproc.INTER_AREA);
        Imgproc.resize(mask, smask, small.size(), 0, 0, Imgproc.INTER_NEAREST);
        MatOfKeyPoint kps = new MatOfKeyPoint();
        Mat des = new Mat();
        orb.detectAndCompute(small, smask, kps, des);
        try {
            if (des.rows() < 10) return null;
            KeyPoint[] q = kps.toArray();

            int[] sub = null;
            Mat train = refDes;
            if (prior != null) {
                double halfW = small.cols() * 1.5 + 200, halfH = small.rows() * 1.5 + 200;
                sub = windowIndices(prior.cx / factor, prior.cy / factor, halfW, halfH);
                if (sub.length < 50) return null;
                train = rows(sub);
            }
            List<MatOfDMatch> knn = new ArrayList<>();
            bf.knnMatch(des, train, knn, 2);
            if (train != refDes) train.release();

            // votacao da translacao (referencia - tela)
            List<float[]> T = new ArrayList<>(), Q = new ArrayList<>(), R = new ArrayList<>();
            for (MatOfDMatch mm : knn) {
                DMatch[] d = mm.toArray();
                if (d.length < 2 || d[0].distance >= 0.85f * d[1].distance) continue;
                int ri = sub != null ? sub[d[0].trainIdx] : d[0].trainIdx;
                Point p = q[d[0].queryIdx].pt;
                T.add(new float[]{refXY[2 * ri] - (float) p.x, refXY[2 * ri + 1] - (float) p.y});
                Q.add(new float[]{(float) p.x, (float) p.y});
                R.add(new float[]{refXY[2 * ri], refXY[2 * ri + 1]});
            }
            for (MatOfDMatch mm : knn) mm.release();
            if (T.size() < 5) return null;
            final int B = 6;
            Map<Long, Integer> votes = new HashMap<>();
            for (float[] t : T) {
                long key = key(Math.round(t[0] / B), Math.round(t[1] / B));
                Integer v = votes.get(key);
                votes.put(key, v == null ? 1 : v + 1);
            }
            long bestKey = 0;
            int best = -1;
            for (Long key : votes.keySet()) {
                int bx = (int) (key >> 32), by = (int) (long) key;
                int s = 0;
                for (int a = -1; a <= 1; a++)
                    for (int b = -1; b <= 1; b++) {
                        Integer v = votes.get(key(bx + a, by + b));
                        if (v != null) s += v;
                    }
                if (s > best) { best = s; bestKey = key; }
            }
            int need = prior != null ? 5 : 6;
            if (best < need) return null;
            float bx = (int) (bestKey >> 32) * B, by = (int) (long) bestKey * B;
            int nT = T.size();
            boolean[] inl = new boolean[nT];
            for (int i = 0; i < nT; i++) inl[i] = Math.hypot(T.get(i)[0] - bx, T.get(i)[1] - by) < 2 * B;
            List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (int i = 0; i < nT; i++) if (inl[i]) { xs.add(T.get(i)[0]); ys.add(T.get(i)[1]); }
            double sc = 1, tx = median(xs), ty = median(ys);
            // ajuste fino da escala (a barra de zoom tem alguns % de erro, que pesa nas bordas)
            for (int it = 0; it < 3; it++) {
                double qx = 0, qy = 0, rx = 0, ry = 0; int m = 0;
                for (int i = 0; i < nT; i++) if (inl[i]) { qx += Q.get(i)[0]; qy += Q.get(i)[1]; rx += R.get(i)[0]; ry += R.get(i)[1]; m++; }
                if (m < 8) break;
                qx /= m; qy /= m; rx /= m; ry /= m;
                double num = 0, den = 0;
                for (int i = 0; i < nT; i++) if (inl[i]) {
                    double dqx = Q.get(i)[0] - qx, dqy = Q.get(i)[1] - qy;
                    num += dqx * (R.get(i)[0] - rx) + dqy * (R.get(i)[1] - ry);
                    den += dqx * dqx + dqy * dqy;
                }
                if (den <= 0) break;
                double s2 = num / den;
                if (s2 < 0.75 || s2 > 1.33) break;
                sc = s2; tx = rx - sc * qx; ty = ry - sc * qy;
                for (int i = 0; i < nT; i++)
                    inl[i] = Math.hypot(Q.get(i)[0] * sc + tx - R.get(i)[0], Q.get(i)[1] * sc + ty - R.get(i)[1]) < 8;
            }
            Pose p = new Pose();
            p.cx = (small.cols() / 2.0 * sc + tx) * factor;
            p.cy = (small.rows() / 2.0 * sc + ty) * factor;
            p.k = k * sc;
            p.votes = best;
            p.global = prior == null;
            return p;
        } finally {
            small.release(); smask.release(); kps.release(); des.release();
        }
    }

    // ---------------------------------------------------------------- grade

    private void buildGrid() {
        float maxX = 0, maxY = 0;
        int n = refXY.length / 2;
        for (int i = 0; i < n; i++) { maxX = Math.max(maxX, refXY[2 * i]); maxY = Math.max(maxY, refXY[2 * i + 1]); }
        gw = (int) (maxX / CELL) + 1;
        gh = (int) (maxY / CELL) + 1;
        int[] cnt = new int[gw * gh + 1];
        int[] cellOf = new int[n];
        for (int i = 0; i < n; i++) {
            int c = (int) (refXY[2 * i + 1] / CELL) * gw + (int) (refXY[2 * i] / CELL);
            cellOf[i] = c;
            cnt[c + 1]++;
        }
        for (int i = 0; i < gw * gh; i++) cnt[i + 1] += cnt[i];
        cellStart = cnt.clone();
        cellIdx = new int[n];
        int[] pos = cnt.clone();
        for (int i = 0; i < n; i++) cellIdx[pos[cellOf[i]]++] = i;
    }

    private int[] windowIndices(double cx, double cy, double hw, double hh) {
        int x0 = clamp((int) ((cx - hw) / CELL), 0, gw - 1), x1 = clamp((int) ((cx + hw) / CELL), 0, gw - 1);
        int y0 = clamp((int) ((cy - hh) / CELL), 0, gh - 1), y1 = clamp((int) ((cy + hh) / CELL), 0, gh - 1);
        int total = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) total += cellStart[y * gw + x + 1] - cellStart[y * gw + x];
        int[] out = new int[total];
        int o = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int c = y * gw + x;
                for (int j = cellStart[c]; j < cellStart[c + 1]; j++) out[o++] = cellIdx[j];
            }
        return out;
    }

    private Mat rows(int[] idx) {
        byte[] b = new byte[idx.length * 32];
        for (int i = 0; i < idx.length; i++) System.arraycopy(data.featDes, idx[i] * 32, b, i * 32, 32);
        Mat m = new Mat(idx.length, 32, CvType.CV_8UC1);
        m.put(0, 0, b);
        return m;
    }

    // ---------------------------------------------------------------- util

    static long key(int a, int b) { return ((long) a << 32) | (b & 0xffffffffL); }

    static float median(List<Float> v) {
        float[] a = new float[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    static int clamp(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }

    static byte[] asset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    static Mat decode(byte[] png) {
        return Imgcodecs.imdecode(new MatOfByte(png), Imgcodecs.IMREAD_GRAYSCALE);
    }
}
