package com.teyvatradar;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/** Tela invisivel que so pede a captura de tela de novo (modo alternativo) e volta ao jogo. */
public class CaptureActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), 1);
    }

    @Override
    protected void onActivityResult(int req, int code, Intent d) {
        super.onActivityResult(req, code, d);
        if (code == RESULT_OK && d != null) {
            startForegroundService(new Intent(this, RadarService.class)
                    .putExtra(RadarService.EXTRA_CODE, code)
                    .putExtra(RadarService.EXTRA_DATA, d));
        } else {
            Toast.makeText(this, "Radar continua pausado.", Toast.LENGTH_SHORT).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
