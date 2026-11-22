package com.teyvatradar;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    static final String[] GENSHIN_PACKAGES = {"com.miHoYo.GenshinImpact", "com.miHoYo.Yuanshen"};

    SharedPreferences prefs;
    TextView status;
    EditText url;
    Button overlayBtn, notifBtn;

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("radar", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(OverlayService.BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(56), dp(20), dp(40));
        scroll.addView(col);

        TextView h = text("⚔  Teyvat Radar", 28, OverlayService.GOLD_LIGHT);
        col.addView(h);
        col.addView(text("Mapa de inimigos flutuando por cima do Genshin, sem trocar de app.", 15, OverlayService.TEXT));

        status = text("", 14, OverlayService.GOLD);
        status.setPadding(0, dp(16), 0, dp(8));
        col.addView(status);

        overlayBtn = button(col, "1. Permitir “Exibir sobre outros apps”", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        notifBtn = button(col, "2. Permitir notificação (controles do radar)", new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                }
            }
        });

        col.addView(label("Endereço do mapa"));
        url = new EditText(this);
        url.setSingleLine(true);
        url.setTextColor(OverlayService.TEXT);
        url.setTextSize(13);
        url.setText(prefs.getString("url", OverlayService.DEFAULT_URL));
        col.addView(url);
        button(col, "Voltar ao mapa oficial (HoYoLAB)", new View.OnClickListener() {
            public void onClick(View v) { url.setText(OverlayService.DEFAULT_URL); }
        });

        col.addView(label("Usar"));
        button(col, "▶  Iniciar radar e abrir o Genshin", new View.OnClickListener() {
            public void onClick(View v) {
                if (startRadar()) openGenshin();
            }
        });
        button(col, "Iniciar só o radar", new View.OnClickListener() {
            public void onClick(View v) { startRadar(); }
        });
        button(col, "Parar radar", new View.OnClickListener() {
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                refresh();
            }
        });
        button(col, "Restaurar posição, tamanho e opacidade", new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().remove("px").remove("py").remove("pw").remove("ph")
                        .remove("bx").remove("by").remove("alpha").apply();
                toast("Restaurado. Pare e inicie o radar para aplicar.");
            }
        });

        col.addView(label("Como usar"));
        col.addView(text(
                "• Toque na bolinha ⚔ para abrir/fechar o mapa. Arraste-a para onde quiser.\n" +
                "• Arraste a barra de título para mover a janela e o ◢ no canto para redimensionar.\n" +
                "• − / + mudam a transparência.\n" +
                "• 👻 Modo fantasma: o mapa fica visível, mas os toques vão para o jogo (andar, câmera). Toque na bolinha para sair. Nesse modo a opacidade fica no máximo em 80% — é uma regra do Android.\n" +
                "• ⌨ libera o teclado para buscar um inimigo no mapa. Desligue depois, para o teclado não atrapalhar o jogo.\n" +
                "• ▾ minimiza. ← ⌂ ⟳ = voltar, início e recarregar.\n" +
                "• Os filtros que você marca no mapa do HoYoLAB ficam salvos.\n\n" +
                "No Poco (HyperOS), para o radar não ser fechado sozinho: Configurações → Apps → Teyvat Radar → Economia de bateria → “Sem restrições”.",
                14, OverlayService.TEXT));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    void refresh() {
        boolean ov = Settings.canDrawOverlays(this);
        boolean nt = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        overlayBtn.setText((ov ? "✔ " : "") + "1. Permitir “Exibir sobre outros apps”");
        notifBtn.setText((nt ? "✔ " : "") + "2. Permitir notificação (controles do radar)");
        status.setText(OverlayService.running ? "● Radar ligado" : "○ Radar desligado");
    }

    boolean startRadar() {
        String u = url.getText().toString().trim();
        if (!u.startsWith("http")) u = "https://" + u;
        prefs.edit().putString("url", u).apply();
        if (!Settings.canDrawOverlays(this)) {
            toast("Primeiro libere “Exibir sobre outros apps” (passo 1).");
            return false;
        }
        if (OverlayService.running) {
            toast("O radar já está ligado. Para trocar o mapa, pare e inicie de novo.");
        } else {
            startForegroundService(new Intent(this, OverlayService.class));
        }
        status.setText("● Radar ligado");
        return true;
    }

    void openGenshin() {
        for (String p : GENSHIN_PACKAGES) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) {
                startActivity(i);
                return;
            }
        }
        toast("Não encontrei o Genshin instalado. Abra o jogo manualmente.");
    }

    // ---------------------------------------------------------------- ui

    TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    TextView label(String s) {
        TextView t = text(s.toUpperCase(), 12, OverlayService.GOLD);
        t.setPadding(0, dp(22), 0, dp(6));
        return t;
    }

    Button button(LinearLayout parent, String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextColor(OverlayService.TEXT);
        btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        btn.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(OverlayService.BAR);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), 0x66D4AF37);
        btn.setBackground(g);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(8);
        parent.addView(btn, lp);
        return btn;
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
