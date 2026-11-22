package com.teyvatradar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Baixa e carrega o pacote de dados (categorias, pontos, icones e a assinatura
 * visual do mapa) publicado pelo GitHub Actions no branch "data".
 */
public class DataStore {

    static final String BASE = "https://raw.githubusercontent.com/aniwah2/teyvat-radar/data/";

    public interface Progress { void on(int percent); }

    public static class Cat {
        public int id;
        public String name;
        public int count;
        public Cat parent;
        public final List<Cat> children = new ArrayList<>();
        public boolean isLeaf() { return children.isEmpty(); }
        /** Todas as folhas (itens marcaveis) abaixo desta categoria. */
        public void leaves(List<Cat> out) {
            if (isLeaf()) { out.add(this); return; }
            for (Cat c : children) c.leaves(out);
        }
    }

    final File dir;
    public List<Cat> roots = new ArrayList<>();
    public final Map<Integer, Cat> byId = new HashMap<>();
    public final Map<Integer, float[]> points = new HashMap<>();   // x,y no zoom 0
    public int featCount, featFactor;
    public float[] featXY;
    public byte[] featDes;
    private final LruCache<Integer, Bitmap> icons = new LruCache<>(400);

    public DataStore(Context c) {
        dir = new File(c.getFilesDir(), "data");
    }

    public boolean isReady() {
        return new File(dir, "features.bin").exists() && new File(dir, "points.json").exists();
    }

    public String localVersion() {
        try {
            return new JSONObject(readText(new File(dir, "meta.json"))).optString("pack_version", "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String remoteVersion() throws IOException {
        return new String(httpGet(BASE + "pack_version.txt", null), "UTF-8").trim();
    }

    /** Baixa pack.zip e substitui os dados locais. */
    public void download(Progress p) throws IOException {
        byte[] zip = httpGet(BASE + "pack.zip", p);
        File tmp = new File(dir.getParentFile(), "data_tmp");
        deleteRec(tmp);
        tmp.mkdirs();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File f = new File(tmp, e.getName());
                if (!f.getCanonicalPath().startsWith(tmp.getCanonicalPath())) continue;
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
        deleteRec(dir);
        if (!tmp.renameTo(dir)) throw new IOException("Falha ao salvar os dados");
        icons.evictAll();
    }

    /** Carrega tudo na memoria (chamar fora da thread principal). */
    public void load() throws Exception {
        // categorias
        roots = new ArrayList<>();
        byId.clear();
        JSONArray arr = new JSONArray(readText(new File(dir, "categories.json")));
        for (int i = 0; i < arr.length(); i++) roots.add(parseCat(arr.getJSONObject(i), null));

        // pontos
        points.clear();
        JSONObject pj = new JSONObject(readText(new File(dir, "points.json")));
        Iterator<String> it = pj.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = pj.getJSONArray(k);
            float[] xy = new float[a.length() * 2];
            for (int i = 0; i < a.length(); i++) {
                JSONArray p = a.getJSONArray(i);
                xy[2 * i] = (float) p.getDouble(0);
                xy[2 * i + 1] = (float) p.getDouble(1);
            }
            points.put(Integer.parseInt(k), xy);
        }

        // assinatura visual
        byte[] fb = readBytes(new File(dir, "features.bin"));
        ByteBuffer bb = ByteBuffer.wrap(fb).order(ByteOrder.LITTLE_ENDIAN);
        featCount = bb.getInt();
        featFactor = bb.getInt();
        featXY = new float[featCount * 2];
        bb.asFloatBuffer().get(featXY);
        bb.position(8 + featCount * 8);
        featDes = new byte[featCount * 32];
        bb.get(featDes);
    }

    private Cat parseCat(JSONObject o, Cat parent) throws Exception {
        Cat c = new Cat();
        c.id = o.getInt("id");
        c.name = o.getString("name");
        c.count = o.optInt("count", 0);
        c.parent = parent;
        byId.put(c.id, c);
        JSONArray ch = o.optJSONArray("children");
        if (ch != null) for (int i = 0; i < ch.length(); i++) c.children.add(parseCat(ch.getJSONObject(i), c));
        return c;
    }

    public Bitmap icon(int id) {
        Bitmap b = icons.get(id);
        if (b != null) return b;
        File f = new File(dir, "icons/" + id + ".png");
        if (!f.exists()) return null;
        b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) icons.put(id, b);
        return b;
    }

    /** Icone para uma categoria: o proprio ou o do primeiro filho que tiver. */
    public Bitmap iconFor(Cat c) {
        Bitmap b = icon(c.id);
        if (b != null) return b;
        for (Cat k : c.children) {
            b = iconFor(k);
            if (b != null) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ io

    static byte[] httpGet(String url, Progress p) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            long total = c.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(total > 0 ? (int) total : 1 << 20);
                byte[] buf = new byte[65536];
                long got = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (p != null && total > 0) {
                        int pc = (int) (got * 100 / total);
                        if (pc != last) { last = pc; p.on(pc); }
                    }
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] readBytes(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return b;
        }
    }

    static String readText(File f) throws IOException {
        return new String(readBytes(f), "UTF-8");
    }

    static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] l = f.listFiles();
            if (l != null) for (File k : l) deleteRec(k);
        }
        f.delete();
    }
}
