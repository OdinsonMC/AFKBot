package com.afkbot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    // Defaults shown in the boxes (the friend can change them)
    static final String DEF_SERVER = "play.legocraft.net";
    static final String DEF_USER = "lala123";
    static final String DEF_PASS = "b.gurdas00@";

    private EditText hostBox, userBox, passBox;
    private Button toggle;
    private TextView logView;
    private ScrollView scroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int seenVersion = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (BotLog.version != seenVersion) {
                seenVersion = BotLog.version;
                logView.setText(BotLog.text());
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
            toggle.setText(BotLog.running ? "Stop bot" : "Start bot");
            handler.postDelayed(this, 500);
        }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private EditText field(LinearLayout root, String label, String value) {
        TextView t = new TextView(this);
        t.setText(label);
        root.addView(t);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value);
        root.addView(e);
        return e;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences prefs = getSharedPreferences("afk", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("AFK Bot");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        hostBox = field(root, "Server", prefs.getString("host", DEF_SERVER));
        userBox = field(root, "Username", prefs.getString("user", DEF_USER));
        passBox = field(root, "Password", prefs.getString("pass", DEF_PASS));

        toggle = new Button(this);
        toggle.setText("Start bot");
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle);

        Button battery = new Button(this);
        battery.setText("Allow running in background (battery)");
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        root.addView(battery);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(8), 0, 0);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void onToggle() {
        if (BotLog.running) {
            startService(new Intent(this, BotService.class).setAction(BotService.ACTION_STOP));
            return;
        }
        String host = hostBox.getText().toString().trim();
        String user = userBox.getText().toString().trim();
        String pass = passBox.getText().toString();
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            BotLog.add("Fill in server, username and password first.");
            return;
        }
        int port = 25565;
        int idx = host.lastIndexOf(':');
        if (idx > 0) {
            try { port = Integer.parseInt(host.substring(idx + 1)); host = host.substring(0, idx); }
            catch (NumberFormatException ignored) { }
        }
        getSharedPreferences("afk", MODE_PRIVATE).edit()
                .putString("host", hostBox.getText().toString().trim())
                .putString("user", user).putString("pass", pass).apply();

        Intent i = new Intent(this, BotService.class).setAction(BotService.ACTION_START)
                .putExtra("host", host).putExtra("port", port).putExtra("user", user).putExtra("pass", pass);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override protected void onResume() { super.onResume(); seenVersion = -1; handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }
}
