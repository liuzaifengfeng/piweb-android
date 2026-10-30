package com.piweb.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Explicit mutable PendingIntent result from Termux. Does not treat dispatch as success. */
public class CommandResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || ProvisionService.server == null || ProvisionService.jobId == null
                || !ProvisionService.jobId.equals(intent.getStringExtra("job"))) return;
        Bundle result = intent.getBundleExtra("com.termux.RUN_COMMAND_RESULT");
        if (result == null) return;
        int exit = result.getInt("exitCode", -1);
        int error = result.getInt("err", 0);
        if (!ProvisionService.server.terminal()) {
            ProvisionService.server.localState("failed", "Termux 命令结束但安装未完成（exit=" + exit + ", err=" + error
                    + "）。请先在 Termux 完成基础初始化、安装 curl/python 并开启 allow-external-apps；可检查安装工作目录日志后重试。");
        }
        // Never display raw stdout/stderr: it may contain a transport URL or account information.
    }
}
