package com.piweb.launcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * 通过 Termux 的 RUN_COMMAND 服务执行 shell 命令。
 * 前提：1) Termux 已安装；2) 本应用被授予 com.termux.permission.RUN_COMMAND；
 *       3) Termux 的 allow-external-apps=true。
 */
final class TermuxRunner {

    static final String TERMUX_PKG = "com.termux";
    static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    static final String HOME = "/data/data/com.termux/files/home";
    static final String SHELL = "/data/data/com.termux/files/usr/bin/sh";

    private TermuxRunner() {
    }

    /** 启动 Pi Web 服务（不打开外部浏览器）。返回 null 表示成功下发，否则返回可读的错误。 */
    static String startServer(Context context) {
        if (!isTermuxInstalled(context)) {
            return "未检测到 Termux（com.termux），请先安装 Termux。";
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(TERMUX_PKG, TERMUX_SERVICE);
            intent.setAction(ACTION_RUN_COMMAND);
            intent.putExtra("com.termux.RUN_COMMAND_PATH", SHELL);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                    new String[]{"-c", "PIWEB_NO_BROWSER=1 " + quote(DeploymentConfig.dir() + "/launch.sh")} );
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Pi Web");
            intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "启动 Pi Web 服务");
            context.startService(intent);
            return null;
        } catch (SecurityException e) {
            return "权限被拒绝（SecurityException）：" + safeMsg(e)
                    + "\n请确认已允许「在 Termux 中运行命令」，并在 Termux 中开启 allow-external-apps。";
        } catch (IllegalStateException e) {
            return "系统阻止了后台服务启动（IllegalStateException）：" + safeMsg(e)
                    + "\n可能是 HyperOS 的后台/自启动限制，请在系统设置中允许本应用「自启动」和「后台弹出界面」。";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + safeMsg(t);
        }
    }

    /** 唤醒（冷启动）Termux 应用：从前景 App 启动另一个 App 的 Activity，系统允许。 */
    static boolean wakeTermux(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PKG);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在 Termux 应用 UID 下执行 sh；不是 ADB/root。 */
    static String runShell(Context context, String command, String labelText) {
        return runShell(context, command, labelText, null);
    }

    static String runShell(Context context, String command, String labelText, PendingIntent callback) {
        if (!isTermuxInstalled(context)) return "未检测到 Termux";
        try {
            Intent intent = new Intent();
            intent.setClassName(TERMUX_PKG, TERMUX_SERVICE);
            intent.setAction(ACTION_RUN_COMMAND);
            intent.putExtra("com.termux.RUN_COMMAND_PATH", SHELL);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", command});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", labelText);
            if (callback != null) intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback);
            context.startService(intent);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 重启 Pi Web 服务（保留 wakelock）。 */
    static String restartServer(Context context) {
        // --restart 让 stop.sh 启用连点保护（8 秒内刚启动过就跳过停止）；
        // 单独的 stop.sh 调用永不做保护，免得影响手动停止与安装器自检收尾。
        return runShell(context,
                "PIWEB_NO_BROWSER=1 " + quote(DeploymentConfig.dir() + "/stop.sh") + " --restart && "
                        + "PIWEB_NO_BROWSER=1 " + quote(DeploymentConfig.dir() + "/launch.sh"),
                "重启 Pi Web");
    }

    static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }

    static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m == null ? "(no message)" : m;
    }
}
