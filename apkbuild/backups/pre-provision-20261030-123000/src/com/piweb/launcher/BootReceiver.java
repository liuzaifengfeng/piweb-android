package com.piweb.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机后自动在 Termux 里后台拉起 Pi Web 服务（不弹界面）。 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "PiWebBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        String error = TermuxRunner.startServer(context);
        if (error == null) {
            Log.i(TAG, "Pi Web start requested at boot");
        } else {
            // Android 12+ 可能因后台启动服务限制被拦，属预期内的降级：点图标仍可启动
            Log.w(TAG, "Boot start failed: " + error);
        }
    }
}
