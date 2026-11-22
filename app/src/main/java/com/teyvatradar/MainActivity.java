package com.teyvatradar;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
    static final int GOLD = 0xFFD4AF37, GOLD_L = 0xFFFFE6A8, BG = 0xFF12141F, BAR = 0xFF1E2235,
            TEXT = 0xFFE8E8F0, MUTED = 0xFF9A9FB5;

    DataStore data;
    SharedPreferences prefs;
    TextView status, dataStatus;
    Button dataBtn, a11yBtn, toggleBtn, overlayBtn, notifBtn;
    boolean downloading;
    TextView diag;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        data = new DataStore(this);
        prefs = getSharedPreferences("radar", MODE_PRIVATE);

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

        col.addView(label("Preparar (só uma vez)"));
        dataBtn = button(col, "", new View.OnClickListener() {
            public void onClick(View v) { downloadData(true); }
        });
        dataStatus = text("", 13, MUTED);
        dataStatus.setPadding(dp(4), dp(4), 0, 0);
        col.addView(dataStatus);
        a11yBtn = button(col, "", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        });
        col.addView(text(
                "Na tela que abrir: Apps instalados (ou Serviços baixados) → Teyvat Radar → ativar.\n" +
                "Se aparecer “Configuração restrita”, toque no botão abaixo, depois no menu ⋮ → " +
                "“Permitir configurações restritas”, e volte para ativar.", 13, MUTED));
        button(col, "Abrir informações do app (configuração restrita)", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });

        col.addView(label("Usar"));
        button(col, "▶  Abrir o Genshin", new View.OnClickListener() {
            public void onClick(View v) { openGenshin(); }
        });
        toggleBtn = button(col, "", new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putBoolean("enabled", !RadarA11yService.enabledByUser(prefs)).apply();
                RadarA11yService s = RadarA11yService.instance;
                if (s != null) s.onSettingsChanged(false);
                refresh();
            }
        });
        col.addView(text(
                "Com a acessibilidade ativada, o radar liga sozinho quando o Genshin abre. Abra o mapa do jogo: " +
                "o botão ⚔ aparece no canto esquerdo. Toque nele, marque o que quer ver e toque em Aplicar.\n\n" +
                "Pode gravar a tela normalmente: este modo não usa o recurso de gravação.\n\n" +
                "Para tirar as marcações: abra o mapa, toque no botão e use “Limpar filtros”.", 14, TEXT));

        col.addView(label("Diagnóstico"));
        diag = text("", 12, MUTED);
        diag.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(diag);
        button(col, "Atualizar diagnóstico", new View.OnClickListener() {
            public void onClick(View v) { refresh(); }
        });

        col.addView(label("Modo alternativo (captura de tela)"));
        col.addView(text("Só use se não conseguir ativar a acessibilidade. Neste modo, gravar a tela pausa o radar " +
                "(o Android só permite uma captura por vez); depois é só tocar em “toque para religar”.", 13, MUTED));
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
        button(col, "Ligar modo captura de tela", new View.OnClickListener() {
            public void onClick(View v) { requestCapture(); }
        });
        button(col, "Desligar modo captura de tela", new View.OnClickListener() {
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, RadarService.class));
                refresh();
            }
        });

        setContentView(scroll);
        downloadData(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    void refresh() {
        boolean a11y = RadarA11yService.isConnected();
        boolean on = RadarA11yService.enabledByUser(prefs);
        dataBtn.setText((data.isReady() ? "✔ " : "") + "1. Baixar dados do mapa");
        if (!downloading) dataStatus.setText(data.isReady() ? "Dados prontos (versão " + data.localVersion() + ")" : "Dados ainda não baixados");
        a11yBtn.setText((a11y ? "✔ " : "") + "2. Ativar o Teyvat Radar na Acessibilidade");
        toggleBtn.setText(on ? "Radar ligado — toque para desligar" : "Radar desligado — toque para ligar");
        boolean ov = Settings.canDrawOverlays(this);
        boolean nt = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        overlayBtn.setText((ov ? "✔ " : "") + "Permitir “Exibir sobre outros apps”");
        notifBtn.setText((nt ? "✔ " : "") + "Permitir notificação");
        RadarA11yService svc = RadarA11yService.instance;
        diag.setText("Versão " + BuildConfigVersion() + "\nAcessibilidade: " + (svc != null ? "conectada" : "desligada") + "\n"
                + (svc != null ? svc.diagnostics() : ""));
        if (!data.isReady()) status.setText("○ Falta baixar os dados do mapa");
        else if (a11y && on) status.setText("● Radar pronto (acessibilidade)");
        else if (a11y) status.setText("○ Radar desligado");
        else if (RadarService.running) status.setText(RadarService.paused ? "○ Radar pausado (captura de tela)" : "● Radar ligado (captura de tela)");
        else status.setText("○ Falta ativar na Acessibilidade");
    }

    void downloadData(final boolean force) {
        if (downloading) return;
        downloading = true;
        dataStatus.setText("Verificando dados do mapa…");
        new Thread(new Runnable() {
            public void run() {
                String msg;
                boolean changed = false;
                try {
                    String remote = DataStore.remoteVersion();
                    if (force || !data.isReady() || !remote.equals(data.localVersion())) {
                        data.download(new DataStore.Progress() {
                            public void on(final int pc) {
                                runOnUiThread(new Runnable() { public void run() { dataStatus.setText("Baixando dados do mapa… " + pc + "%"); } });
                            }
                        });
                        changed = true;
                        msg = "Dados atualizados (versão " + remote + ")";
                    } else {
                        msg = "Dados prontos (versão " + remote + ")";
                    }
                } catch (Exception e) {
                    msg = data.isReady() ? "Sem internet agora; usando os dados salvos." : "Falha ao baixar: " + e.getMessage();
                }
                final String m = msg;
                final boolean ch = changed;
                runOnUiThread(new Runnable() { public void run() {
                    downloading = false;
                    RadarA11yService s = RadarA11yService.instance;
                    if (ch && s != null) s.onSettingsChanged(true);
                    refresh();
                    dataStatus.setText(m);
                } });
            }
        }).start();
    }

    void requestCapture() {
        if (RadarA11yService.isConnected()) { toast("A acessibilidade já está ativa; não precisa do modo alternativo."); return; }
        if (!Settings.canDrawOverlays(this)) { toast("Primeiro libere “Exibir sobre outros apps”."); return; }
        if (!data.isReady()) { toast("Baixe os dados do mapa primeiro."); return; }
        if (RadarService.running && !RadarService.paused) { openGenshin(); return; }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int code, Intent d) {
        super.onActivityResult(req, code, d);
        if (req != REQ_CAPTURE) return;
        if (code != RESULT_OK || d == null) { toast("Sem a captura de tela o radar não consegue ver o mapa."); return; }
        startForegroundService(new Intent(this, RadarService.class)
                .putExtra(RadarService.EXTRA_CODE, code)
                .putExtra(RadarService.EXTRA_DATA, d));
        openGenshin();
    }

    void openGenshin() {
        for (String p : RadarA11yService.GENSHIN) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) { startActivity(i); return; }
        }
        toast("Não encontrei o Genshin instalado.");
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

    String BuildConfigVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
