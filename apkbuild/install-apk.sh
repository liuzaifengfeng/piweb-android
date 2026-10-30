#!/data/data/com.termux/files/usr/bin/sh
# 一键安装/更新 Pi Web 启动器（同签名覆盖安装，不丢数据）
APK="$HOME/apkbuild/PiWebLauncher.apk"
[ -f "$APK" ] || { echo "找不到 $APK"; exit 1; }
echo "正在打开系统安装器…"
termux-open "$APK"
echo "若未弹出，请检查 Termux 是否已 termux-setup-storage 或有文件访问权限。"
