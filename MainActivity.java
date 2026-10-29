package com.koresh.microphone;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (status != null) status.setText(i.getStringExtra("status"));
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(36, 36, 36, 36);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(22);
        status.setText("🐸 Кореш\n\nОжидаю разрешение микрофона...");
        layout.addView(status);
        setContentView(layout);
        IntentFilter filter = new IntentFilter(ListeningService.STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, filter);
        requestAccess();
    }
    private void requestAccess() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100);
            return;
        }
        startListener();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 100 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListener();
        else status.setText("Для Кореша нужен доступ к микрофону. Открой приложение снова и разреши его.");
    }
    private void startListener() {
        status.setText("🐸 Кореш\n\nЗапускаю локальное распознавание...");
        Intent i = new Intent(this, ListeningService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }
    @Override protected void onDestroy() {
        unregisterReceiver(updates);
        super.onDestroy();
    }
}
