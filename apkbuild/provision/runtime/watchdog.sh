#!/data/data/com.termux/files/usr/bin/sh
# 看门狗（托管版）：每 5 秒探一次本安装的两个服务，连续 2 次失败自动拉起。
#
#   - 自带单实例锁（.watchdog.lock），重复启动无害；pid 写入 watchdog.pid 供 stop.sh 使用
#   - 连续 2 次失败才动手，避免"正在启动/正在重启"时误判
#   - .last-stop 在 20 秒内表示刚被人为停止，不插手（给人留出成对启动的时间）
#   - 每 30 秒顺带确认策略服务在线
set -u

APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PORT=$(node "$APP_DIR/runtime-config.js" web_port)
POLICY_PORT=$(node "$APP_DIR/runtime-config.js" policy_port)
LOG="$APP_DIR/watchdog.log"
INTERVAL=5
STRIKES_TO_RESTART=2
STOP_GRACE=20

say() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

exec 7>"$APP_DIR/.watchdog.lock"
flock -n 7 || exit 0

printf '%s\n' "$$" > "$APP_DIR/watchdog.pid"
[ -x "$APP_DIR/launch.sh" ] || { say "找不到 launch.sh，看门狗退出"; exit 1; }
say "看门狗启动 pid=$$ 监测 web=$PORT policy=$POLICY_PORT"

web_up() {
  curl -fsS --max-time 3 "http://127.0.0.1:$PORT/api/home" >/dev/null 2>&1 || \
  node -e '
    const net = require("node:net");
    const s = net.connect(Number(process.argv[1]), "127.0.0.1");
    s.setTimeout(2000);
    s.on("connect", () => { s.destroy(); process.exit(0); });
    s.on("timeout", () => { s.destroy(); process.exit(1); });
    s.on("error", () => process.exit(1));
  ' "$PORT" 2>/dev/null
}

policy_up() { curl -fsS --max-time 2 "http://127.0.0.1:$POLICY_PORT/health" >/dev/null 2>&1; }

strikes=0
tick=0
while :; do
  if web_up; then
    strikes=0
  else
    strikes=$((strikes + 1))
    say "探测失败（第 ${strikes} 次）"
    if [ "$strikes" -ge "$STRIKES_TO_RESTART" ]; then
      recently_stopped=0
      if [ -f "$APP_DIR/.last-stop" ]; then
        age=$(( $(date +%s) - $(stat -c %Y "$APP_DIR/.last-stop" 2>/dev/null || echo 0) ))
        [ "$age" -lt "$STOP_GRACE" ] && recently_stopped=1
      fi
      if ! flock -n "$APP_DIR/.launch.lock" -c 'exit 0' 2>/dev/null; then
        say "有人在启动/重启，本轮跳过"
      elif [ "$recently_stopped" -eq 1 ]; then
        say "刚被停止未满 ${STOP_GRACE}s，本轮跳过"
      else
        say "开始自动拉起"
        PIWEB_NO_BROWSER=1 "$APP_DIR/launch.sh" >> "$LOG" 2>&1
        if web_up; then say "自动拉起成功"; else say "自动拉起失败，下一轮重试"; fi
      fi
      strikes=0
    fi
  fi

  tick=$((tick + 1))
  if [ $((tick % 6)) -eq 0 ] && ! policy_up; then
    say "策略服务不在，尝试拉起"
    "$APP_DIR/ensure-policy.sh" >/dev/null 2>&1 || true
  fi

  sleep "$INTERVAL"
done
