#!/system/bin/sh
echo "===== RUN_COMMAND / piweb 相关日志 ====="
logcat -d -v brief 2>/dev/null | grep -iE "runcommand|piweb|com.termux" | tail -40
