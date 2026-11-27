package com.afkbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class BotService extends Service {
    public static final String ACTION_START = "com.afkbot.START";
    public static final String ACTION_STOP = "com.afkbot.STOP";
    private static final String CHANNEL = "afkbot";

    private BotClient client;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        goForeground();
        stopClient();
        String host = intent.getStringExtra("host");
        String user = intent.getStringExtra("user");
        String pass = intent.getStringExtra("pass");
        int port = intent.getIntExtra("port", 25565);
        acquireLocks();
        BotLog.clear();
        BotLog.running = true;
        BotLog.add("Bot started.");
        client = new BotClient(host, port, user, pass, BotLog::add);
        client.start();
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "AFK Bot", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, BotService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("AFK Bot is running")
                .setContentText("Keeping the bot connected to the server")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @SuppressWarnings({"deprecation", "WakelockTimeout"})
    private void acquireLocks() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "afkbot:cpu");
            }
            if (!wake.isHeld()) wake.acquire();
            if (wifi == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "afkbot:wifi");
            }
            if (!wifi.isHeld()) wifi.acquire();
        } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) { }
    }

    private void stopClient() {
        if (client != null) { client.stop(); client = null; }
    }

    private void shutdown() {
        stopClient();
        releaseLocks();
        BotLog.running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Called when the app is swiped away from the recent-apps list. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        stopClient();
        releaseLocks();
        BotLog.running = false;
        super.onDestroy();
    }
}
