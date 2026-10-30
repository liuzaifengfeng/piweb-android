#!/data/data/com.termux/files/usr/bin/sh
# 确保本安装的权限策略服务在运行（托管版）。
# 由 launch.sh 与 watchdog.sh 调用；有锁，重复调用无害。
set -u

APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
POLICY_PORT=$(node "$APP_DIR/runtime-config.js" policy_port)

policy_up() { curl -fsS --max-time 2 "http://127.0.0.1:${POLICY_PORT}/health" >/dev/null 2>&1; }
policy_up && exit 0

exec 8>"$APP_DIR/.policy.lock"
flock -n 8 || exit 0
policy_up && exit 0

export PIWEB_CONFIG="$APP_DIR/deployment.json"
export PI_PERM_PORT="$POLICY_PORT"
PIWEB_PIDFILE="$APP_DIR/policy.pid" setsid node "$APP_DIR/policy-server.js" >> "$APP_DIR/policy.log" 2>&1 8>&- &
exit 0
