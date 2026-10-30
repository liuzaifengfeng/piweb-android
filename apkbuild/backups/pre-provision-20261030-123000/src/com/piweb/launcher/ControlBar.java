package com.piweb.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 右侧控制栏（Pi Web 深色风格）：状态 + 权限等级 + 批准模式 + 常用动作。
 * root 档必须通过指纹验证才会生效。
 */
final class ControlBar extends ScrollView {

    interface Host {
        void reloadPage();

        void showToast(String msg);
    }

    // 主题色（由 setTheme 根据网页实际背景切换）
    private int cBg, cLine, cText, cMuted, cAccent, cSelBg, cPanel;

    private static final int[] DARK_BG = {0xFF0A0C10, 0xFF1B222B, 0xFFE6EDF3, 0xFF6E7781, 0xFF22D3EE, 0xFF11333B};
    private static final int[] LIGHT_BG = {0xFFF7F8FA, 0xFFDDE2E8, 0xFF1F2328, 0xFF6B7280, 0xFF0891B2, 0xFFDBF3F8};
    private static final int ACCENT = 0xFF22D3EE;
    private static final int DANGER = 0xFFF87171;
    private static final int OK = 0xFF4ADE80;

    private static final String[] LEVELS = {"app", "adb", "root"};
    private static final String[] LEVEL_LABELS = {"普通", "ADB", "root"};
    private static final String[] MODES = {"ask", "auto", "full"};
    private static final String[] MODE_LABELS = {"请示", "半自动", "放行"};
    private static final String[] LEVEL_ZH = {"普通", "ADB", "root"};
    private static final String[] MODE_ZH = {"请示", "半自动", "放行"};

    private final Activity activity;
    private final Host host;
    private final TextView[] levelViews = new TextView[3];
    private final TextView[] modeViews = new TextView[3];
    private final TextView statusView;
    private final TextView policyView;
    private final java.util.List<TextView> labels = new java.util.ArrayList<>();
    private final java.util.List<TextView> actions = new java.util.ArrayList<>();
    private LinearLayout col;
    private View dividerView;

    private String level = "app";
    private String mode = "ask";

    ControlBar(Activity activity, Host host) {
        super(activity);
        this.activity = activity;
        this.host = host;

        setTheme(true);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);

        float d = density();
        col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(7 * d);
        col.setPadding(padH, Math.round(10 * d), padH, Math.round(10 * d));

        // 状态徽标（点击刷新）
        statusView = new TextView(activity);
        statusView.setTextSize(10f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(cMuted);
        statusView.setText("● 检测中");
        statusView.setPadding(0, Math.round(5 * d), 0, Math.round(5 * d));
        statusView.setBackground(round(cSelBg, cLine, 7 * d, d));
        statusView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        col.addView(statusView, lp(d, 26));

        col.addView(sectionLabel("权限", d));

        for (int i = 0; i < LEVELS.length; i++) {
            final String value = LEVELS[i];
            TextView b = button(LEVEL_LABELS[i], d);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onLevelTap(value);
                }
            });
            levelViews[i] = b;
            col.addView(b, lp(d, 32));
        }

        col.addView(sectionLabel("批准", d));

        for (int i = 0; i < MODES.length; i++) {
            final String value = MODES[i];
            TextView b = button(MODE_LABELS[i], d);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onModeTap(value);
                }
            });
            modeViews[i] = b;
            col.addView(b, lp(d, 32));
        }

        col.addView(divider(d));
        col.addView(sectionLabel("操作", d));

        addAction(col, "重载", d, new Runnable() {
            @Override
            public void run() {
                host.reloadPage();
            }
        });
        addAction(col, "浏览器", d, new Runnable() {
            @Override
            public void run() {
                openInBrowser();
            }
        });
        addAction(col, "重启服务", d, new Runnable() {
            @Override
            public void run() {
                String err = TermuxRunner.restartServer(activity);
                if (err != null) {
                    host.showToast("重启失败：" + err);
                } else {
                    host.showToast("已请求重启服务");
                    statusView.setText("● 重启中");
                }
            }
        });
        addAction(col, "Termux", d, new Runnable() {
            @Override
            public void run() {
                openTermux();
            }
        });

        // 底部策略摘要
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        col.addView(new View(activity), spacer);

        policyView = new TextView(activity);
        policyView.setTextSize(9f);
        policyView.setTextColor(cMuted);
        policyView.setGravity(Gravity.CENTER);
        policyView.setPadding(0, Math.round(8 * d), 0, 0);
        policyView.setText("app · ask");
        col.addView(policyView);

        addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        renderSelection();
        refresh();
    }

    void setTheme(boolean dark) {
        int[] t = dark ? DARK_BG : LIGHT_BG;
        cBg = t[0]; cLine = t[1]; cText = t[2]; cMuted = t[3]; cSelBg = t[5];
        setBackgroundColor(cBg);
        if (col != null) {
            for (TextView v : labels) v.setTextColor(cMuted);
            for (TextView v : actions) v.setTextColor(cText);
        }
        if (policyView != null) policyView.setTextColor(cMuted);
        if (dividerView != null) dividerView.setBackgroundColor(cLine);
        if (levelViews[0] != null) renderSelection();
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private LinearLayout.LayoutParams lp(float d, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, Math.round(heightDp * d));
        p.bottomMargin = Math.round(4 * d);
        return p;
    }

    private TextView sectionLabel(String text, float d) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(cMuted);
        t.setTextSize(9f);
        t.setLetterSpacing(0.14f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Math.round(9 * d), 0, Math.round(5 * d));
        labels.add(t);
        return t;
    }

    private View divider(float d) {
        View v = new View(activity);
        v.setBackgroundColor(cLine);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, Math.max(1, Math.round(d)));
        p.topMargin = Math.round(7 * d);
        p.bottomMargin = Math.round(2 * d);
        v.setLayoutParams(p);
        dividerView = v;
        return v;
    }

    private TextView button(String label, float d) {
        TextView b = new TextView(activity);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(11f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackground(round(0x00000000, cLine, 7 * d, d));
        b.setTextColor(cText);
        return b;
    }

    private void addAction(LinearLayout col, String label, float d, final Runnable action) {
        TextView b = button(label, d);
        b.setTextSize(10.5f);
        b.setTypeface(Typeface.DEFAULT);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        actions.add(b);
        col.addView(b, lp(d, 30));
    }

    private GradientDrawable round(int fill, int stroke, float radius, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setColor(fill);
        g.setStroke(Math.max(1, Math.round(d)), stroke);
        return g;
    }

    private GradientDrawable selected(float radius, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setColor(cSelBg);
        g.setStroke(Math.max(1, Math.round(1.2f * d)), ACCENT);
        return g;
    }

    private void renderSelection() {
        float d = density();
        for (int i = 0; i < LEVELS.length; i++) {
            boolean on = LEVELS[i].equals(level);
            levelViews[i].setBackground(on ? selected(7 * d, d) : round(0x00000000, cLine, 7 * d, d));
            levelViews[i].setTextColor(on ? ACCENT : ("root".equals(LEVELS[i]) ? DANGER : cText));
        }
        for (int i = 0; i < MODES.length; i++) {
            boolean on = MODES[i].equals(mode);
            modeViews[i].setBackground(on ? selected(7 * d, d) : round(0x00000000, cLine, 7 * d, d));
            modeViews[i].setTextColor(on ? ACCENT : cText);
        }
        if (policyView != null) {
            policyView.setText(zh(level, LEVELS, LEVEL_ZH) + " · " + zh(mode, MODES, MODE_ZH));
        }
    }

    private String zh(String value, String[] keys, String[] names) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(value)) return names[i];
        }
        return value;
    }

    private final android.os.Handler syncHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable syncTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            syncHandler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncHandler.postDelayed(syncTask, 3000);
    }

    @Override
    protected void onDetachedFromWindow() {
        syncHandler.removeCallbacks(syncTask);
        super.onDetachedFromWindow();
    }

    private void onLevelTap(String next) {
        if (next.equals(level)) return;
        if ("root".equals(next)) {
            requireFingerprint(next, mode);
            return;
        }
        apply(next, mode);
    }

    private void onModeTap(String next) {
        if (next.equals(mode)) return;
        apply(level, next);
    }

    private void apply(final String nextLevel, final String nextMode) {
        statusView.setText("● 保存中");
        PolicyClient.set(nextLevel, nextMode, new PolicyClient.Callback() {
            @Override
            public void onResult(String l, String m, String err) {
                if (err != null) {
                    statusView.setText("● 保存失败");
                    host.showToast("策略保存失败：" + err);
                    return;
                }
                level = l != null ? l : nextLevel;
                mode = m != null ? m : nextMode;
                renderSelection();
                statusView.setText("● 已生效");
            }
        });
    }

    void refresh() {
        PolicyClient.get(new PolicyClient.Callback() {
            @Override
            public void onResult(String l, String m, String err) {
                if (err != null) {
                    statusView.setText("● 策略服务离线");
                    statusView.setTextColor(DANGER);
                    renderSelection();
                    return;
                }
                level = l != null ? l : "app";
                mode = m != null ? m : "ask";
                renderSelection();
                pingServer();
            }
        });
    }

    /** 顺带探测 Pi Web 是否在跑，更新状态徽标。 */
    private void pingServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean up = false;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL("http://127.0.0.1:30141/api/home").openConnection();
                    c.setConnectTimeout(1200);
                    c.setReadTimeout(1200);
                    up = c.getResponseCode() < 500;
                    c.disconnect();
                } catch (Throwable ignored) {
                }
                final boolean ok = up;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusView.setText(ok ? "● 运行中" : "● 已停止");
                        statusView.setTextColor(ok ? OK : DANGER);
                    }
                });
            }
        }).start();
    }

    private void openInBrowser() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:30141"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable t) {
            host.showToast("无法打开浏览器");
        }
    }

    private void openTermux() {
        try {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage("com.termux");
            if (i == null) {
                host.showToast("未安装 Termux");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable t) {
            host.showToast("无法打开 Termux");
        }
    }

    /** root 档：先指纹验证，通过后才写策略。 */
    private void requireFingerprint(final String nextLevel, final String nextMode) {
        if (Build.VERSION.SDK_INT < 28) {
            host.showToast("系统不支持指纹 API");
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                BiometricManager bm = (BiometricManager)
                        activity.getSystemService(android.content.Context.ACTIVITY_SERVICE);
                if (bm != null) {
                    int can = bm.canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG
                                    | BiometricManager.Authenticators.BIOMETRIC_WEAK);
                    Log.i("PiWebGate", "canAuthenticate=" + can);
                    if (can != BiometricManager.BIOMETRIC_SUCCESS) {
                        statusView.setText(can == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                                ? "● 未录入指纹" : "● 指纹不可用");
                        statusView.setTextColor(DANGER);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Log.i("PiWebGate", "showing biometric prompt");
        final CancellationSignal cancel = new CancellationSignal();
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                    .setTitle("验证指纹以启用 root")
                    .setSubtitle("root 档允许 Agent 执行任意系统命令")
                    .setNegativeButton("取消", activity.getMainExecutor(),
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dlg, int which) {
                                    statusView.setText("● 已取消");
                                }
                            })
                    .build();

            prompt.authenticate(cancel, activity.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            Log.i("PiWebGate", "biometric success");
                            apply(nextLevel, nextMode);
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            Log.w("PiWebGate", "biometric error " + code + " " + msg);
                            statusView.setText("● 指纹失败 " + code);
                            statusView.setTextColor(DANGER);
                            renderSelection();
                        }
                    });
        } catch (Throwable t) {
            statusView.setText("● 无法调用指纹");
            statusView.setTextColor(DANGER);
            host.showToast("指纹调用异常：" + t.getMessage());
            renderSelection();
        }
    }
}
