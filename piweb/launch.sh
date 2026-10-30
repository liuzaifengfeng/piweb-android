#!/data/data/com.termux/files/usr/bin/sh
# Pi Web 一键启动：确保服务在 127.0.0.1:30141 运行，然后（可选）打开浏览器。
# 供桌面图标（Termux:Widget）/ 启动器 APK / 看门狗调用。
#
# 环境变量：
#   PI_WEB_PORT=30141      端口
#   PIWEB_NO_BROWSER=1     只启动不打开浏览器（测试/纯后台用）
#   PIWEB_NO_WAKELOCK=1    不申请 Android wakelock
#   PIWEB_NO_WATCHDOG=1    不启动看门狗
#   PIWEB_LOG              server.log 路径（隔离测试用）
#   PIWEB_STAMP            .last-start 路径（隔离测试用）
#
# 本版关键改动（2026-10-30）：
#   1) flock 单实例锁 —— 连点/并发启动只会有一个真正干活，消除 EADDRINUSE 风暴
#   2) setsid 独立会话 —— 不再随 Termux 命令行会话一起被带走
#   3) 带时间戳日志 —— server.log 每行前面有时间，便于定位"几点死的"
#   4) 启动后自动确保权限策略服务 + 看门狗在跑
set -u

APP_DIR="$HOME/piweb"
PORT="${PI_WEB_PORT:-30141}"
HOST="127.0.0.1"
URL="http://${HOST}:${PORT}"
LOG="${PIWEB_LOG:-$APP_DIR/server.log}"
LAUNCH_LOG="$APP_DIR/launch.log"
PI_WEB_BIN="$APP_DIR/node_modules/@agegr/pi-web/bin/pi-web.js"
MAX_LOG_BYTES=2097152
STAMP="${PIWEB_STAMP:-$APP_DIR/.last-start}"

say() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LAUNCH_LOG"; }

# TCP 能连上就算"在跑"：服务忙（大会话/长回复）时 HTTP 可能几秒没响应，
# 但只要端口有人监听，就绝不能当作"没启动"再起一个。
port_open() {
  node -e '
    const net = require("node:net");
    const port = Number(process.argv[1]);
    const s = net.connect(port, "127.0.0.1");
    s.setTimeout(2000);
    s.on("connect", () => { s.destroy(); process.exit(0); });
    s.on("timeout", () => { s.destroy(); process.exit(1); });
    s.on("error", () => process.exit(1));
  ' "$PORT" 2>/dev/null
}

is_up() {
  curl -s -o /dev/null --max-time 3 "$URL/" 2>/dev/null && return 0
  port_open
}

take_wakelock() {
  [ "${PIWEB_NO_WAKELOCK:-0}" = "1" ] && return 0
  command -v termux-wake-lock >/dev/null 2>&1 || return 0
  # 让 Android 不要冻结/回收 Termux 进程；由 stop.sh 释放
  termux-wake-lock >/dev/null 2>&1 || true
}

trim_log() {
  [ -f "$LOG" ] || return 0
  size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
  if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
    tail -c 262144 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
}

if [ ! -f "$PI_WEB_BIN" ]; then
  echo "找不到 Pi Web：$PI_WEB_BIN" >&2
  exit 1
fi

# ---- 单实例锁：抢不到说明另一个 launch.sh 正在启动，直接退出（不是错误） ----
exec 9>"$APP_DIR/.launch.lock"
if ! flock -n 9; then
  echo "另一个启动流程正在进行，本次跳过（不会产生第二个实例）"
  say "跳过：已有启动流程持有锁"
  exit 0
fi

if is_up; then
  echo "Pi Web 已在运行：$URL"
  say "已在运行，跳过启动"
else
  echo "正在启动 Pi Web ..."
  cd "$APP_DIR" || exit 1
  trim_log
  take_wakelock
  say "开始启动（port=$PORT log=$LOG）"

  # setsid：自成会话/进程组，9>&- 把单实例锁交给 launch.sh 自己持有
  setsid "$APP_DIR/run-server.sh" >/dev/null 2>&1 9>&- &

  i=0
  while [ "$i" -lt 60 ]; do
    if is_up; then break; fi
    sleep 0.5
    i=$((i + 1))
  done

  if is_up; then
    date +%s > "$STAMP"
    echo "已启动：$URL（端口 $PORT，仅本机）"
    say "启动成功"
  else
    echo "启动失败，日志末尾：" >&2
    tail -n 20 "$LOG" >&2
    say "启动失败"
    exit 1
  fi
fi

# 权限策略服务（30142）：挂了会让权限栏显示"已离线"，这里顺手拉起来
[ -x "$APP_DIR/ensure-policy.sh" ] && "$APP_DIR/ensure-policy.sh" >/dev/null 2>&1 || true

# 看门狗：自带单实例锁，重复调用无害
if [ "${PIWEB_NO_WATCHDOG:-0}" != "1" ] && [ -x "$APP_DIR/watchdog.sh" ]; then
  setsid "$APP_DIR/watchdog.sh" >/dev/null 2>&1 9>&- &
fi

if [ "${PIWEB_NO_BROWSER:-0}" = "1" ]; then
  echo "(已跳过打开浏览器)"
  exit 0
fi

termux-open-url "$URL"
