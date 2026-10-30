package com.piweb.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.AlphaAnimation;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 一键启动：检查权限 → 检测 127.0.0.1:30141 → 未运行则通过 Termux RUN_COMMAND 拉起
 * → 就绪后淡出启动页并加载界面。
 */
public class MainActivity extends Activity {

    private static final String BASE_URL = "http://127.0.0.1:30141";
    private static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_PERM = 1001;
    private static final int MAX_POLLS = 200;          // 200 * 350ms ≈ 70s
    private static final int POLL_INTERVAL_MS = 350;
    private static final int WAKE_AT_POLL = 7;         // ≈2.5s 仍无响应 → 认为 Termux 已被杀

    private WebView webView;
    private SplashView splash;
    private ControlBar controlBar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int polls = 0;
    private boolean loaded = false;
    private boolean starting = false;
    private boolean askedPermission = false;
    private boolean wokeTermux = false;
    private boolean resumed = false;
    private int wakeStealLeft = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Color.parseColor("#0E1116"));

        FrameLayout webWrap = new FrameLayout(this);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 不要在平板上做桌面式缩放：它会让页面的滚动容器行为异常（滑不动）
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                detectPageTheme();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.parseColor("#0E1116"));
        // 确保 WebView 自己能拿到焦点和触摸事件
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        webView.setLongClickable(true);
        webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        webView.requestFocus();
        webWrap.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        row.addView(webWrap, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        controlBar = new ControlBar(this, new ControlBar.Host() {
            @Override
            public void reloadPage() {
                webView.reload();
            }

            @Override
            public void showToast(String msg) {
                android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        float barD = getResources().getDisplayMetrics().density;
        row.addView(controlBar, new LinearLayout.LayoutParams(
                Math.round(104 * barD), ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splash = new SplashView(this);
        splash.setStatus("正在启动 Pi Web");
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        // Android 15+ 强制 edge-to-edge：自己给根布局加系统栏内边距，否则会被状态栏/导航栏遮住
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left, top, right, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = i.left;
                    top = i.top;
                    right = i.right;
                    bottom = i.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(left, top, right, bottom);
                return insets;
            }
        });

        // 深色背景 → 状态栏/导航栏图标用浅色
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (loaded) return;
        if (!hasRunCommandPermission()) {
            if (!askedPermission) {
                askedPermission = true;
                splash.setStatus("需要授权才能在 Termux 中启动服务");
                requestPermissions(new String[]{PERM_RUN_COMMAND}, REQ_PERM);
            } else {
                showError("缺少「在 Termux 中运行命令」权限\n\n"
                        + "请到 设置 → 应用 → Pi Web → 权限 中开启，\n"
                        + "然后重新打开本应用。");
            }
            return;
        }
        if (!starting) begin();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    private boolean hasRunCommandPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            begin();
        } else {
            showError("你拒绝了「在 Termux 中运行命令」权限\n\n"
                    + "请到 设置 → 应用 → Pi Web → 权限 中开启。");
        }
    }

    private void begin() {
        starting = true;
        polls = 0;
        wokeTermux = false;
        splash.setOnClickListener(null);
        splash.setStatus("正在启动 Pi Web");

        String error = TermuxRunner.startServer(this);
        if (error != null) {
            showError("无法调用 Termux 的 RUN_COMMAND 服务\n\n" + error);
            return;
        }
        if (ping()) {
            showApp();
            return;
        }
        pollServer();
    }

    private void pollServer() {
        polls++;
        final int current = polls;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean up = ping();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (up) {
                            showApp();
                        } else if (current >= MAX_POLLS) {
                            showError("启动超时\n\n"
                                    + "可在 Termux 执行 pi-web status 查看状态，\n"
                                    + "或检查 ~/piweb/server.log。");
                        } else {
                            if (current == WAKE_AT_POLL && !wokeTermux) {
                                wokeTermux = true;
                                splash.setStatus("正在唤醒 Termux");
                                TermuxRunner.wakeTermux(MainActivity.this);
                                startFrontStealing();
                                scheduleResend(1200);
                                scheduleResend(3000);
                                scheduleResend(5000);
                                scheduleResend(8000);
                            } else if (current == 20 && wokeTermux) {
                                splash.setStatus("Termux 已唤醒，等待服务");
                            }
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    pollServer();
                                }
                            }, POLL_INTERVAL_MS);
                        }
                    }
                });
            }
        }).start();
    }

    private void scheduleResend(long delayMs) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loaded) return;
                String err = TermuxRunner.startServer(MainActivity.this);
                if (err != null) {
                    showError("无法调用 Termux 的 RUN_COMMAND 服务\n\n" + err);
                }
            }
        }, delayMs);
    }

    /** Termux 被唤醒后会顶到前台（冷启动约 4s），发现被压下去就反复抢回。 */
    private void startFrontStealing() {
        wakeStealLeft = 45;
        stealStep();
    }

    private void stealStep() {
        if (loaded || wakeStealLeft <= 0) return;
        wakeStealLeft--;
        if (!resumed) returnToFront();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stealStep();
            }
        }, 350);
    }

    /** 把自己拉回前台，避免界面被 Termux 覆盖。 */
    private void returnToFront() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(getTaskId(), ActivityManager.MOVE_TASK_NO_USER_ACTION);
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent self = new Intent(this, MainActivity.class);
            self.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(self);
        } catch (Throwable ignored) {
        }
    }

    private void showError(String detail) {
        starting = false;
        splash.setError(detail);
        splash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                begin();
            }
        });
    }

    private void showApp() {
        if (loaded) return;
        loaded = true;
        starting = false;
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(BASE_URL);
        splash.stop();
        AlphaAnimation fade = new AlphaAnimation(1f, 0f);
        fade.setDuration(280);
        fade.setFillAfter(true);
        splash.startAnimation(fade);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 彻底移除启动页，避免任何残留视图干扰触摸/滚动
                try {
                    ViewGroup parent = (ViewGroup) splash.getParent();
                    if (parent != null) parent.removeView(splash);
                } catch (Throwable ignored) {
                    splash.setVisibility(View.GONE);
                }
                webView.requestFocus();
                if (controlBar != null) controlBar.refresh();
                detectPageTheme();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        detectPageTheme();
                    }
                }, 1500);
            }
        }, 320);
    }

    /** 读取网页 body 的实际背景色，让侧栏跟随 Pi Web 的浅色/深色主题。 */
    private void detectPageTheme() {
        try {
            webView.evaluateJavascript(
                    "(function(){try{var s=getComputedStyle(document.body);return (s.backgroundColor||'')+'|'+(s.color||'');}catch(e){return '';}})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            Boolean dark = parseDark(value);
                            if (dark != null && controlBar != null) {
                                controlBar.setTheme(dark.booleanValue());
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private Boolean parseDark(String jsValue) {
        if (jsValue == null) return null;
        int p = jsValue.indexOf("rgb");
        if (p < 0) return null;
        int open = jsValue.indexOf('(', p);
        int close = jsValue.indexOf(')', open + 1);
        if (open < 0 || close < 0) return null;
        String[] parts = jsValue.substring(open + 1, close).split(",");
        if (parts.length < 3) return null;
        try {
            int r = Integer.parseInt(parts[0].trim());
            int g = Integer.parseInt(parts[1].trim());
            int b = Integer.parseInt(parts[2].trim());
            double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            return lum < 128;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean ping() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE_URL + "/api/home").openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
