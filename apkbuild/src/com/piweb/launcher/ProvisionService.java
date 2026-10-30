package com.piweb.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.JSONObject;
import java.util.UUID;

/** User-started foreground installation. Not an autostart or remote-control service. */
public class ProvisionService extends Service {
    static volatile ProvisionServer server;
    static volatile String jobId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final String CHANNEL = "piweb-install";
    private boolean finishing;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Pi Web 安装", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, SetupActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 42, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = n.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Pi Web 安装任务").setContentText("仅在本机传输资源；点此查看进度或取消")
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(42, notification);
    }

    @Override public int onStartCommand(final Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("cancel".equals(intent.getAction())) { if (server != null) server.cancel(); return START_NOT_STICKY; }
        if (server != null && !server.terminal()) return START_NOT_STICKY;
        handler.removeCallbacksAndMessages(null);
        if (server != null) server.close();
        finishing = false;
        try {
            server = new ProvisionServer(getAssets());
            jobId = UUID.randomUUID().toString();
            final String base = server.base();
            final String bootHash = ProvisionServer.assetHash(getAssets(), "bootstrap.sh");
            final String pyHash = ProvisionServer.assetHash(getAssets(), "provision.py");
            final String manifestHash = ProvisionServer.assetHash(getAssets(), "manifest.json");
            final String action = intent.getStringExtra("mode");
            final String name = intent.getStringExtra("name");
            final int web = intent.getIntExtra("web", 30141), policy = intent.getIntExtra("policy", 30142);
            if (!("probe".equals(action) || "install".equals(action)) || name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]{0,39}") || web < 1024 || policy < 1024 || web > 65535 || policy > 65535 || web == policy) throw new IllegalArgumentException("非法安装参数");
            // Short command only. Large assets never pass through Binder or command arguments.
            String qbase = TermuxRunner.quote(base);
            String command = "set -eu; umask 077; "
                    + "command -v curl >/dev/null && command -v python >/dev/null || { printf '请先安装 curl python\\n' >&2; exit 20; }; "
                    + "j=$(mktemp -d \"$PREFIX/tmp/piweb-bootstrap.XXXXXXXX\"); "
                    + "curl -fsS --connect-timeout 5 --max-time 60 " + qbase + "/bootstrap.sh -o \"$j/bootstrap.sh\"; "
                    + "printf '%s  %s\\n' " + TermuxRunner.quote(bootHash) + " \"$j/bootstrap.sh\" | sha256sum -c - >/dev/null; "
                    + "sh \"$j/bootstrap.sh\" " + qbase + " " + TermuxRunner.quote(pyHash) + " " + TermuxRunner.quote(action)
                    + " " + TermuxRunner.quote(name) + " " + web + " " + policy + " " + TermuxRunner.quote(manifestHash);
            Intent callback = new Intent(this, CommandResultReceiver.class).putExtra("job", jobId);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) piFlags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getBroadcast(this, 43, callback, piFlags);
            String error = TermuxRunner.runShell(this, command, "Pi Web 安装", result);
            if (error != null) server.localState("failed", error);
            getSharedPreferences("provision", MODE_PRIVATE).edit().putBoolean("interrupted", true).apply();
        } catch (Exception e) {
            if (server != null) server.localState("failed", "无法启动安装任务：" + e.getClass().getSimpleName());
            else { stopSelf(); return START_NOT_STICKY; }
        }
        handler.post(tick);
        return START_NOT_STICKY;
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (server == null || finishing) return;
            if (server.terminal()) {
                finishing = true;
                JSONObject state = server.state();
                if ("ready".equals(state.optString("phase"))) {
                    try { DeploymentConfig.save(ProvisionService.this, state.getJSONObject("config")); }
                    catch (Exception e) { server.localState("failed", "安装完成但 App 无法保存配置，请重新检查并接管"); }
                }
                getSharedPreferences("provision", MODE_PRIVATE).edit().putBoolean("interrupted", false).apply();
                // Give the last status response and Termux PendingIntent time to finish.
                handler.postDelayed(new Runnable() { public void run() { stopSelf(); } }, 3000);
            } else handler.postDelayed(this, 700);
        }
    };
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (server != null) server.close();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
