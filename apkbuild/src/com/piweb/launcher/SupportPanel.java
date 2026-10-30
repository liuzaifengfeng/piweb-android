package com.piweb.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Read-only local support tools; no shell, root or model credentials required. */
final class SupportPanel {
    private SupportPanel() {}

    static void show(final Activity activity, final boolean logs) {
        final TextView text = new TextView(activity);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setTextSize(12);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(text);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(logs ? "最近日志（基础脱敏）" : "本机诊断")
                .setView(scroll).setPositiveButton("关闭", null)
                .setNeutralButton("刷新", null).setNegativeButton("复制", null).create();
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] busy = {false};
        final String[] result = {null};
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                if (busy[0]) return;
                busy[0] = true;
                result[0] = null;
                text.setText("检测中…");
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                new Thread(new Runnable() {
                    @Override public void run() {
                        String value;
                        if (logs) {
                            try {
                                value = new JSONObject(fetch(DeploymentConfig.policyUrl() + "/logs", true)).getString("text");
                                value = "仅做常见凭据格式脱敏，分享前仍请人工检查。\n\n" + value;
                            } catch (Exception e) {
                                value = "无法获取日志：" + e.getMessage()
                                        + "\n旧版接管安装可能不支持日志接口；请升级托管运行时，或在 Termux 查看安装目录中的 server.log。";
                            }
                        } else {
                            String version = "未知";
                            try { version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; }
                            catch (Exception ignored) { version = "无法读取"; }
                            value = "APK：" + version + "\n安装目录：" + DeploymentConfig.dir()
                                    + "\n安装类型：" + (DeploymentConfig.managed() ? "托管" : "接管")
                                    + "\n\nWeb：" + DeploymentConfig.webUrl() + "\n" + probe(DeploymentConfig.webUrl(), false)
                                    + "\n\n策略服务：" + DeploymentConfig.policyUrl() + "\n" + probe(DeploymentConfig.policyUrl() + "/health", false)
                                    + "\n策略读取：" + probe(DeploymentConfig.policyUrl() + "/policy", true)
                                    + "\n\nHTTP 可达不代表模型或当前任务运行正常。";
                        }
                        final String output = redact(value);
                        main.post(new Runnable() {
                            @Override public void run() {
                                busy[0] = false;
                                if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
                                result[0] = output;
                                text.setText(output);
                                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            }
                        });
                    }
                }, "piweb-support").start();
            }
        };
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { refresh.run(); }
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                if (result[0] == null) return;
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Pi Web", result[0]));
                Toast.makeText(activity, "已复制，分享前请检查敏感信息", Toast.LENGTH_SHORT).show();
            }
        });
        refresh.run();
    }

    private static String redact(String value) {
        String token = DeploymentConfig.token();
        if (!token.isEmpty()) value = value.replace(token, "[REDACTED]");
        return value.replaceAll("(?i)(Bearer\\s+)[^\\s\"',}]+", "$1[REDACTED]");
    }

    private static String probe(String url, boolean auth) {
        long start = android.os.SystemClock.elapsedRealtime();
        try {
            fetch(url, auth);
            return "正常（" + (android.os.SystemClock.elapsedRealtime() - start) + " ms）";
        } catch (Exception e) { return "异常：" + e.getMessage(); }
    }

    private static String fetch(String url, boolean auth) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setInstanceFollowRedirects(false);
            if (auth && !DeploymentConfig.token().isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + DeploymentConfig.token());
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            // Diagnostics only need the status, not a potentially large HTML response.
            if (!url.endsWith("/logs")) return "";
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 262144) throw new Exception("响应过大");
                    out.write(buffer, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally { conn.disconnect(); }
    }
}
