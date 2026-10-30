#!/system/bin/sh
PKG=com.piweb.launcher
echo "===== 1) 当前安装版本 ====="
cmd package dump $PKG 2>/dev/null | grep -E "versionName|versionCode=" | head -2
echo "===== 2) 运行时权限状态（改动前） ====="
cmd package dump $PKG 2>/dev/null | grep -A12 "runtime permissions" | head -16
echo "===== 3) 安装 v1.2 ====="
cp /data/data/com.termux/files/home/apkbuild/PiWebLauncher.apk /data/local/tmp/pw.apk
chmod 644 /data/local/tmp/pw.apk
pm install -r -g /data/local/tmp/pw.apk
echo "===== 4) 显式授予 RUN_COMMAND ====="
pm grant $PKG com.termux.permission.RUN_COMMAND; echo "grant_rc=$?"
echo "===== 5) 授权后状态 ====="
cmd package dump $PKG 2>/dev/null | grep -A12 "runtime permissions" | head -16
echo "===== 6) 版本确认 ====="
cmd package dump $PKG 2>/dev/null | grep -E "versionName|versionCode=" | head -2
