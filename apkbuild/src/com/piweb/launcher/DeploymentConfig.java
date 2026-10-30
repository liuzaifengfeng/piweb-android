package com.piweb.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** App-side mirror of a validated Termux deployment.json; never contains model credentials. */
final class DeploymentConfig {
    static final String HOME = "/data/data/com.termux/files/home";
    private static volatile String dir = HOME + "/piweb";
    private static volatile int web = 30141, policy = 30142;
    private static volatile String token = "";
    private static volatile boolean ready = false, managed = false;

    static synchronized void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("deployment", Context.MODE_PRIVATE);
        ready = p.getBoolean("ready", false);
        dir = p.getString("dir", HOME + "/piweb");
        web = p.getInt("web", 30141);
        policy = p.getInt("policy", 30142);
        token = p.getString("token", "");
        managed = p.getBoolean("managed", false);
    }

    static synchronized void save(Context ctx, JSONObject c) throws Exception {
        String d = c.getString("install_dir");
        int w = c.getInt("web_port"), p = c.getInt("policy_port");
        if (!d.matches("/data/data/com\\.termux/files/home/[A-Za-z][A-Za-z0-9_-]{0,39}")) throw new IllegalArgumentException("非法安装目录");
        if (w < 1024 || w > 65535 || p < 1024 || p > 65535 || w == p) throw new IllegalArgumentException("非法端口");
        String t = c.optString("policy_token", "");
        if (!t.isEmpty() && !t.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("非法策略令牌");
        boolean m = c.optBoolean("managed", false);
        if (m && t.isEmpty()) throw new IllegalArgumentException("缺少策略令牌");
        if (!ctx.getSharedPreferences("deployment", Context.MODE_PRIVATE).edit()
                .putString("dir", d).putInt("web", w).putInt("policy", p)
                .putString("token", t).putBoolean("managed", m).putBoolean("ready", true).commit()) {
            throw new IllegalStateException("无法保存配置");
        }
        load(ctx);
    }

    static boolean ready() { return ready; }
    static boolean managed() { return managed; }
    static String dir() { return dir; }
    static String token() { return token; }
    static String webUrl() { return "http://127.0.0.1:" + web; }
    static String policyUrl() { return "http://127.0.0.1:" + policy; }
    private DeploymentConfig() {}
}
