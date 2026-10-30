#!/data/data/com.termux/files/usr/bin/sh
# Pi Web 看门狗：每 5 秒探一次服务，连续两次探不到就自动拉起。
#
# 说明：
#   - 自带单实例锁（flock .watchdog.lock），重复启动无害。
#   - 连续 2 次失败才动手，避免服务正在启动/正在重启时误判。
#   - 刚被人为停止（.last-stop 在 20 秒内）时不动手，给人留出成对启动的时间。
#   - 顺手确保权限策略服务（30142）在线，避免权限栏显示"已离线"吓人。
#
# 开关：PIWEB_NO_WATCHDOG=1 可临时禁用自动重启（排查用）。
set -u

APP_DIR="$HOME/piweb"
PORT="${PI_WEB_PORT:-30141}"
URL="http://127.0.0.1:${PORT}/"
LOG="$APP_DIR/watchdog.log"
INTERVAL=5
STRIKES_TO_RESTART=2
STOP_GRACE=20

say() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

exec 7>"$APP_DIR/.watchdog.lock"
flock -n 7 || exit 0

[ -x "$APP_DIR/launch.sh" ] || { say "找不到 launch.sh，看门狗退出"; exit 1; }

say "看门狗启动 pid=$$ 监测 $URL（间隔 ${INTERVAL}s）"

strikes=0
tick=0
while :; do
  if curl -s -o /dev/null --max-time 3 "$URL" 2>/dev/null; then
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

      # launch.sh 正在跑（人为重启中）就不要插手
      if ! flock -n "$APP_DIR/.launch.lock" -c 'exit 0' 2>/dev/null; then
        say "有人在启动/重启，本轮跳过"
      elif [ "$recently_stopped" -eq 1 ]; then
        say "刚被停止未满 ${STOP_GRACE}s，本轮跳过"
      elif [ "${PIWEB_NO_WATCHDOG:-0}" = "1" ]; then
        say "PIWEB_NO_WATCHDOG=1，本轮跳过"
      else
        say "开始自动拉起"
        PIWEB_NO_BROWSER=1 PIWEB_WATCHDOG=1 "$APP_DIR/launch.sh" >> "$LOG" 2>&1
        if curl -s -o /dev/null --max-time 3 "$URL" 2>/dev/null; then
          say "自动拉起成功"
        else
          say "自动拉起失败，下一轮重试"
        fi
      fi
      strikes=0
    fi
  fi

  # 每 6 轮（约 30 秒）顺带确认权限策略服务在线
  tick=$((tick + 1))
  if [ $((tick % 6)) -eq 0 ] && [ -x "$APP_DIR/ensure-policy.sh" ]; then
    "$APP_DIR/ensure-policy.sh" >/dev/null 2>&1 || true
  fi

  sleep "$INTERVAL"
done
