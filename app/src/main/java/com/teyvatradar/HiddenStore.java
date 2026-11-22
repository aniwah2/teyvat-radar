package com.teyvatradar;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Pontos ja feitos (monstro derrotado, item coletado), escondidos ate renascerem.
 * Chave: "idDaCategoria:x:y" (coordenadas arredondadas), valor: quando volta (ms).
 * Long.MAX_VALUE = nao volta (baus, oculos).
 */
public class HiddenStore {

    static final long H = 3600_000L;
    final File file;
    final Map<String, Long> until = new HashMap<>();
    public volatile int version;                 // muda a cada alteracao (para redesenhar)

    public HiddenStore(File dir) {
        file = new File(dir, "hidden.json");
        try {
            JSONObject o = new JSONObject(DataStore.readText(file));
            Iterator<String> it = o.keys();
            long now = System.currentTimeMillis();
            while (it.hasNext()) {
                String k = it.next();
                long u = o.getLong(k);
                if (u > now) until.put(k, u);
            }
        } catch (Exception ignored) { }
    }

    public static String key(int label, float x, float y) {
        return label + ":" + Math.round(x) + ":" + Math.round(y);
    }

    public synchronized boolean isHidden(String k) {
        Long u = until.get(k);
        if (u == null) return false;
        if (u <= System.currentTimeMillis()) { until.remove(k); version++; return false; }
        return true;
    }

    public synchronized void hide(String k, long ms) {
        until.put(k, ms == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + ms);
        version++;
        save();
    }

    public synchronized int count() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (long u : until.values()) if (u > now) n++;
        return n;
    }

    public synchronized void clear() {
        until.clear();
        version++;
        save();
    }

    void save() {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new JSONObject(until).toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    /**
     * Tempo ate renascer, pela categoria. Fontes: wiki do Genshin (animais e inimigos
     * comuns 12 h; especialidades locais 48 h; ferro diario; cristal 72 h; baus nao voltam).
     */
    public static long respawnMs(DataStore.Cat leaf) {
        DataStore.Cat root = leaf;
        while (root.parent != null) root = root.parent;
        String r = root.name.toLowerCase(Locale.ROOT), n = leaf.name.toLowerCase(Locale.ROOT);
        if (r.startsWith("inimigo") || r.startsWith("animal")) return 12 * H;
        if (r.startsWith("especialidade")) return 48 * H;
        if (r.startsWith("minério") || r.startsWith("minerio")) {
            if (n.contains("cristal")) return 72 * H;
            if (n.contains("branco")) return 48 * H;
            return 24 * H;
        }
        if (r.startsWith("baú") || r.startsWith("bau") || r.startsWith("itens especiais")) return Long.MAX_VALUE;
        return 24 * H;
    }

    /** Segundos parado perto do ponto para contar como feito. */
    public static double dwellSeconds(DataStore.Cat leaf) {
        DataStore.Cat root = leaf;
        while (root.parent != null) root = root.parent;
        return root.name.toLowerCase(Locale.ROOT).startsWith("inimigo") ? 4.0 : 1.5;
    }

    /** Se a categoria entra na ocultacao automatica (pesca e pontos de referencia nao). */
    public static boolean autoHideable(DataStore.Cat leaf) {
        DataStore.Cat root = leaf;
        while (root.parent != null) root = root.parent;
        String r = root.name.toLowerCase(Locale.ROOT);
        return !(r.startsWith("pescar") || r.startsWith("ponto") || r.startsWith("notícia") || r.startsWith("guía"));
    }
}
