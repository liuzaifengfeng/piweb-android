#!/data/data/com.termux/files/usr/bin/sh
# 确保权限策略服务（默认 127.0.0.1:30142）在运行。
# 由 launch.sh 和 watchdog.sh 调用；有锁，重复调用无害。
#
# 背景：这个服务被杀掉后，右侧权限栏会一直显示"策略服务已离线"，
# 看起来像整个应用坏了，其实它只影响权限读写，不影响对话。
set -u

APP_DIR="$HOME/piweb"
PORT="${PI_PERM_PORT:-30142}"
LOG="$APP_DIR/policy.log"

[ -f "$APP_DIR/policy-server.js" ] || exit 0

policy_up() {
  curl -s -o /dev/null --max-time 2 "http://127.0.0.1:${PORT}/health" 2>/dev/null
}

# 8 号 fd：检查+启动期间互斥，避免多个调用同时拉起两份
exec 8>"$APP_DIR/.policy.lock"
flock -n 8 || exit 0

policy_up && exit 0

setsid node "$APP_DIR/policy-server.js" >> "$LOG" 2>&1 8>&- &
i=0
while [ "$i" -lt 10 ]; do
  policy_up && exit 0
  sleep 0.3
  i=$((i + 1))
done
exit 0
