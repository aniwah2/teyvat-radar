package com.teyvatradar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tela de filtros: categorias a esquerda, itens com icone a direita, busca e Aplicar. */
public class FilterPanel extends LinearLayout {

    public interface Listener {
        void onApply(Set<Integer> selected);
        void onClose();
    }

    static final int GOLD = 0xFFD4AF37, GOLD_L = 0xFFFFE6A8, BG = 0xF212141F, BAR = 0xFF1E2235,
            TEXT = 0xFFE8E8F0, MUTED = 0xFF9A9FB5, SEL_BG = 0xFF3A3220;

    // categorias mostradas primeiro (o resto vem depois, na ordem da HoYoLAB)
    static final List<String> PRIORITY = Arrays.asList("Inimigos", "Animal", "Especialidade da Área",
            "Inventário / Materiais", "Minérios", "Madeira", "Pescar", "Baú", "Itens Especiais");

    final DataStore data;
    final Listener listener;
    final Set<Integer> sel;
    final float dp;
    final int panelW;
    final List<DataStore.Cat> cats = new ArrayList<>();
    final List<TextView> catRows = new ArrayList<>();
    DataStore.Cat current;
    LinearLayout grid;
    TextView countText;
    EditText search;

    public FilterPanel(Context c, DataStore d, Set<Integer> selected, int panelWidthPx, Listener l) {
        super(c);
        data = d;
        listener = l;
        sel = new HashSet<>(selected);
        dp = c.getResources().getDisplayMetrics().density;
        panelW = panelWidthPx;
        setOrientation(VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG);
        g.setCornerRadius(14 * dp);
        g.setStroke((int) (1.5f * dp), GOLD);
        setBackground(g);
        setClipToOutline(true);

        for (String name : PRIORITY)
            for (DataStore.Cat k : d.roots) if (k.name.equals(name)) cats.add(k);
        for (DataStore.Cat k : d.roots) if (!cats.contains(k)) cats.add(k);

        addView(header());
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(HORIZONTAL);
        body.addView(categoryColumn(), new LayoutParams((int) (panelW * 0.28f), ViewGroup.LayoutParams.MATCH_PARENT));
        ScrollView sv = new ScrollView(c);
        grid = new LinearLayout(c);
        grid.setOrientation(VERTICAL);
        grid.setPadding(px(8), px(6), px(8), px(8));
        sv.addView(grid);
        body.addView(sv, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(footer());

        showCategory(cats.isEmpty() ? null : cats.get(0));
    }

    int px(float v) { return Math.round(v * dp); }

    // ------------------------------------------------------------- partes

    View header() {
        LinearLayout h = new LinearLayout(getContext());
        h.setOrientation(HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(BAR);
        h.setPadding(px(14), px(6), px(6), px(6));
        TextView t = new TextView(getContext());
        t.setText("Filtros do Radar");
        t.setTextColor(GOLD_L);
        t.setTextSize(16);
        h.addView(t, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        search = new EditText(getContext());
        search.setHint("Buscar (ex.: Hilichurl, Lírio Calla)");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(13);
        search.setSingleLine(true);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xFF0B0D16);
        sb.setCornerRadius(8 * dp);
        search.setBackground(sb);
        search.setPadding(px(10), px(6), px(10), px(6));
        LayoutParams lp = new LayoutParams(0, px(36), 1f);
        lp.setMargins(px(16), 0, px(8), 0);
        h.addView(search, lp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim();
                if (q.length() >= 2) showSearch(q); else showCategory(current);
            }
        });

        TextView x = button("✕", false);
        x.setOnClickListener(new OnClickListener() { public void onClick(View v) { listener.onClose(); } });
        h.addView(x, new LayoutParams(px(40), px(36)));
        return h;
    }

    View categoryColumn() {
        ScrollView sv = new ScrollView(getContext());
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setBackgroundColor(0xFF161927);
        for (final DataStore.Cat k : cats) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(8), px(6), px(6), px(6));
            ImageView iv = new ImageView(getContext());
            Bitmap b = data.iconFor(k);
            if (b != null) iv.setImageBitmap(b);
            row.addView(iv, new LayoutParams(px(26), px(26)));
            TextView t = new TextView(getContext());
            t.setTextColor(TEXT);
            t.setTextSize(13);
            t.setPadding(px(8), 0, 0, 0);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTag(k);
            row.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    search.setText("");
                    showCategory(k);
                }
            });
            row.setTag(k);
            catRows.add(t);
            col.addView(row);
        }
        sv.addView(col);
        refreshCats();
        return sv;
    }

    View footer() {
        LinearLayout f = new LinearLayout(getContext());
        f.setOrientation(HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setBackgroundColor(BAR);
        f.setPadding(px(10), px(6), px(10), px(6));
        TextView clear = button("Limpar filtros", false);
        clear.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                sel.clear();
                refreshCats();
                if (search.getText().length() >= 2) showSearch(search.getText().toString()); else showCategory(current);
            }
        });
        f.addView(clear, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)));
        countText = new TextView(getContext());
        countText.setTextColor(MUTED);
        countText.setTextSize(13);
        countText.setGravity(Gravity.CENTER);
        f.addView(countText, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView apply = button("Aplicar", true);
        apply.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { listener.onApply(new HashSet<>(sel)); }
        });
        f.addView(apply, new LayoutParams(px(130), px(40)));
        updateCount();
        return f;
    }

    TextView button(String s, boolean primary) {
        TextView b = new TextView(getContext());
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setPadding(px(14), 0, px(14), 0);
        b.setTextColor(primary ? 0xFF1A1405 : TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(10 * dp);
        if (primary) g.setColor(GOLD); else { g.setColor(0x00000000); g.setStroke(px(1), 0x88D4AF37); }
        b.setBackground(g);
        return b;
    }

    // ------------------------------------------------------------ conteudo

    void showCategory(DataStore.Cat k) {
        current = k;
        for (TextView t : catRows) {
            View row = (View) t.getParent();
            row.setBackgroundColor(t.getTag() == k ? SEL_BG : 0x00000000);
        }
        grid.removeAllViews();
        if (k == null) return;
        // agrupa por subcategoria quando houver niveis intermediarios
        boolean grouped = false;
        for (DataStore.Cat c : k.children) if (!c.isLeaf()) grouped = true;
        if (!grouped) {
            List<DataStore.Cat> leaves = new ArrayList<>();
            k.leaves(leaves);
            addItems(leaves);
        } else {
            List<DataStore.Cat> loose = new ArrayList<>();
            for (DataStore.Cat c : k.children) if (c.isLeaf()) loose.add(c);
            if (!loose.isEmpty()) addItems(loose);
            for (DataStore.Cat c : k.children) {
                if (c.isLeaf()) continue;
                addSection(c.name);
                List<DataStore.Cat> leaves = new ArrayList<>();
                c.leaves(leaves);
                addItems(leaves);
            }
        }
    }

    void showSearch(String q) {
        grid.removeAllViews();
        String nq = norm(q);
        List<DataStore.Cat> found = new ArrayList<>();
        for (DataStore.Cat r : cats) {
            List<DataStore.Cat> leaves = new ArrayList<>();
            r.leaves(leaves);
            for (DataStore.Cat c : leaves) if (norm(c.name).contains(nq)) found.add(c);
        }
        addSection(found.isEmpty() ? "Nada encontrado" : found.size() + " resultado(s)");
        addItems(found);
    }

    void addSection(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(GOLD);
        t.setTextSize(12);
        t.setPadding(px(4), px(10), 0, px(4));
        grid.addView(t);
    }

    void addItems(List<DataStore.Cat> items) {
        int avail = (int) (panelW * 0.72f) - px(20);
        int cell = px(104);
        int cols = Math.max(2, avail / cell);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                grid.addView(row);
            }
            row.addView(item(items.get(i)), new LayoutParams(avail / cols, px(96)));
        }
    }

    View item(final DataStore.Cat c) {
        final FrameLayout f = new FrameLayout(getContext());
        f.setPadding(px(3), px(3), px(3), px(3));
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(px(4), px(6), px(4), px(4));
        ImageView iv = new ImageView(getContext());
        Bitmap b = data.icon(c.id);
        if (b != null) iv.setImageBitmap(b);
        box.addView(iv, new LayoutParams(px(40), px(40)));
        TextView t = new TextView(getContext());
        t.setText(c.name);
        t.setTextColor(TEXT);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        f.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        styleItem(box, sel.contains(c.id));
        final LinearLayout fbox = box;
        f.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (!sel.remove(c.id)) sel.add(c.id);
                styleItem(fbox, sel.contains(c.id));
                refreshCats();
            }
        });
        return f;
    }

    void styleItem(View v, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(10 * dp);
        g.setColor(on ? SEL_BG : 0xFF1B1F31);
        g.setStroke(px(on ? 2 : 1), on ? GOLD : 0x33FFFFFF);
        v.setBackground(g);
    }

    void refreshCats() {
        for (TextView t : catRows) {
            DataStore.Cat k = (DataStore.Cat) t.getTag();
            List<DataStore.Cat> leaves = new ArrayList<>();
            k.leaves(leaves);
            int n = 0;
            for (DataStore.Cat c : leaves) if (sel.contains(c.id)) n++;
            t.setText(n > 0 ? k.name + "  • " + n : k.name);
            t.setTextColor(n > 0 ? GOLD_L : TEXT);
        }
        updateCount();
    }

    void updateCount() {
        if (countText != null)
            countText.setText(sel.isEmpty() ? "Nenhum item marcado" : sel.size() + " item(ns) marcado(s)");
    }

    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
