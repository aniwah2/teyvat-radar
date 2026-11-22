package com.teyvatradar;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    static final int REQ_CAPTURE = 7;
    static final int GOLD = 0xFFD4AF37, GOLD_L = 0xFFFFE6A8, BG = 0xFF12141F, BAR = 0xFF1E2235, TEXT = 0xFFE8E8F0;
    static final String[] GENSHIN = {"com.miHoYo.GenshinImpact", "com.miHoYo.Yuanshen"};

    DataStore data;
    TextView status, dataStatus;
    Button overlayBtn, notifBtn, dataBtn;
    boolean downloading;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        data = new DataStore(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(56), dp(20), dp(40));
        scroll.addView(col);

        col.addView(text("⚔  Teyvat Radar", 28, GOLD_L));
        col.addView(text("Mostra monstros, animais, plantas e materiais direto no mapa do Genshin.", 15, TEXT));
        status = text("", 14, GOLD);
        status.setPadding(0, dp(14), 0, dp(4));
        col.addView(status);

        col.addView(label("Preparar (uma vez)"));
        overlayBtn = button(col, "", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            }
        });
        notifBtn = button(col, "", new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        });
        dataBtn = button(col, "", new View.OnClickListener() {
            public void onClick(View v) { downloadData(true); }
        });
        dataStatus = text("", 13, 0xFF9A9FB5);
        dataStatus.setPadding(dp(4), dp(4), 0, 0);
        col.addView(dataStatus);

        col.addView(label("Usar"));
        button(col, "▶  Ligar radar e abrir o Genshin", new View.OnClickListener() {
            public void onClick(View v) { requestCapture(); }
        });
        button(col, "Desligar radar", new View.OnClickListener() {
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, RadarService.class));
                refresh();
            }
        });

        col.addView(label("Como funciona"));
        col.addView(text(
                "1. Ao ligar, o Android pergunta se pode capturar a tela. Escolha “Tela inteira” (ou o Genshin, se aparecer a opção de um app só). O app só olha a tela para reconhecer o mapa; nada é gravado nem enviado.\n\n" +
                "2. No jogo, abra o mapa. O botão ⚔ aparece no canto esquerdo. Com o jogo normal, ele fica escondido.\n\n" +
                "3. Toque no botão, marque o que quer ver (Inimigos, Animal, plantas...) e toque em Aplicar. Os ícones aparecem nos lugares certos do mapa e acompanham quando você arrasta ou dá zoom.\n\n" +
                "4. Para tirar as marcações: abra o mapa, toque no botão e use “Limpar filtros”.\n\n" +
                "Dica: se os ícones não aparecerem, afaste um pouco o zoom do mapa por um instante para o radar se localizar.\n\n" +
                "No Poco (HyperOS): Configurações → Apps → Teyvat Radar → Economia de bateria → “Sem restrições”.",
                14, TEXT));
        setContentView(scroll);
        downloadData(false);
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
        notifBtn.setText((nt ? "✔ " : "") + "2. Permitir notificação");
        dataBtn.setText((data.isReady() ? "✔ " : "") + "3. Baixar dados do mapa");
        if (!downloading) dataStatus.setText(data.isReady() ? "Dados prontos (versão " + data.localVersion() + ")" : "Dados ainda não baixados");
        status.setText(RadarService.running ? "● Radar ligado" : "○ Radar desligado");
    }

    void downloadData(final boolean force) {
        if (downloading) return;
        downloading = true;
        dataStatus.setText("Verificando dados do mapa…");
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    String remote = DataStore.remoteVersion();
                    if (force || !data.isReady() || !remote.equals(data.localVersion())) {
                        data.download(new DataStore.Progress() {
                            public void on(final int pc) {
                                runOnUiThread(new Runnable() { public void run() { dataStatus.setText("Baixando dados do mapa… " + pc + "%"); } });
                            }
                        });
                        msg = "Dados atualizados (versão " + remote + ")";
                    } else {
                        msg = "Dados prontos (versão " + remote + ")";
                    }
                } catch (Exception e) {
                    msg = data.isReady() ? "Sem internet agora; usando os dados salvos." : "Falha ao baixar: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() { public void run() {
                    downloading = false;
                    refresh();
                    dataStatus.setText(m);
                } });
            }
        }).start();
    }

    void requestCapture() {
        if (!Settings.canDrawOverlays(this)) { toast("Primeiro libere “Exibir sobre outros apps” (passo 1)."); return; }
        if (!data.isReady()) { toast("Baixe os dados do mapa primeiro (passo 3)."); return; }
        if (RadarService.running) { openGenshin(); return; }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int code, Intent d) {
        super.onActivityResult(req, code, d);
        if (req != REQ_CAPTURE) return;
        if (code != RESULT_OK || d == null) { toast("Sem a captura de tela o radar não consegue ver o mapa."); return; }
        Intent i = new Intent(this, RadarService.class)
                .putExtra(RadarService.EXTRA_CODE, code)
                .putExtra(RadarService.EXTRA_DATA, d);
        startForegroundService(i);
        status.setText("● Radar ligado");
        openGenshin();
    }

    void openGenshin() {
        for (String p : GENSHIN) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) { startActivity(i); return; }
        }
        toast("Abra o Genshin normalmente; o radar já está ligado.");
    }

    // ------------------------------------------------------------- ui

    TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    TextView label(String s) {
        TextView t = text(s.toUpperCase(), 12, GOLD);
        t.setPadding(0, dp(22), 0, dp(6));
        return t;
    }

    Button button(LinearLayout parent, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BAR);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), 0x66D4AF37);
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
