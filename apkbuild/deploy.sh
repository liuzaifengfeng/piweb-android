#!/data/data/com.termux/files/usr/bin/sh
# 通过 Shizuku(ADB 级) 部署 PiWebLauncher.apk —— 无需 root，无需手动点安装器。
set -e
APK="$HOME/apkbuild/PiWebLauncher.apk"
R="$HOME/shizuku/r.sh"
[ -f "$APK" ] || { echo "找不到 $APK"; exit 1; }
[ -x "$R" ] || { echo "找不到 $R（Shizuku 通道）"; exit 1; }

echo "1/3 传输 APK…"
B64=$(base64 -w0 "$APK" | tr -d '\n')
"$R" -c "echo $B64 | base64 -d > /data/local/tmp/pw.apk" >/dev/null

echo "2/3 安装…"
"$R" -c 'pm install -r -g /data/local/tmp/pw.apk' 2>&1 | tail -2

echo "3/3 授权并收尾…"
"$R" -c 'pm grant com.piweb.launcher com.termux.permission.RUN_COMMAND; rm -f /data/local/tmp/pw.apk; cmd package dump com.piweb.launcher | grep versionName | head -1' 2>&1 | tail -2
