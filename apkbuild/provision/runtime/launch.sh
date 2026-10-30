#!/data/data/com.termux/files/usr/bin/sh
# Pi Web 启动（托管安装版）
#
# 端口、agent 目录来自本目录的 deployment.json（由安装器写入，不接受外部覆盖）。
#
# 加固（与个人版一致的思路）：
#   1. flock 单实例锁 —— 连点"重启服务"不会并发起多个实例（旧版的 EADDRINUSE 风暴）
#   2. setsid 独立会话 —— 不随 Termux 命令行会话一起被清理
#   3. 带时间戳日志 —— server.log 每行前面有时间
#   4. 存活判定 HTTP + TCP 双探测 —— 服务忙时不会被误判成"没启动"
#   5. 自动拉起看门狗
set -eu

APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LOCK="$APP_DIR/.launch.lock"
LAST_START="$APP_DIR/.last-start"
SERVER_LOG="$APP_DIR/server.log"
MAX_LOG_BYTES=2097152

read_config() { node "$APP_DIR/runtime-config.js" "$1"; }
PORT=$(read_config web_port)
POLICY_PORT=$(read_config policy_port)

export PI_CODING_AGENT_DIR
PI_CODING_AGENT_DIR=$(read_config agent_dir)
export PIWEB_CONFIG="$APP_DIR/deployment.json"
export PI_PERM_PORT="$POLICY_PORT"
export PIWEB_POLICY_PATH="$PI_CODING_AGENT_DIR/permission-policy.json"
export PI_WEB_NO_OPEN=1 PI_WEB_SKIP_VERSION_CHECK=1

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$APP_DIR/launch.log"; }

http_ok() { curl -fsS --max-time 2 "http://127.0.0.1:$1$2" >/dev/null 2>&1; }

# 端口有人监听就算活着：服务忙时 HTTP 可能几秒不应答，此时绝不能当"没启动"再起一个
port_open() {
  node -e '
    const net = require("node:net");
    const s = net.connect(Number(process.argv[1]), "127.0.0.1");
    s.setTimeout(2000);
    s.on("connect", () => { s.destroy(); process.exit(0); });
    s.on("timeout", () => { s.destroy(); process.exit(1); });
    s.on("error", () => process.exit(1));
  ' "$1" 2>/dev/null
}

web_up() { http_ok "$PORT" /api/home && return 0; port_open "$PORT"; }
policy_up() { http_ok "$POLICY_PORT" /health; }

trim_log() {
  [ -f "$SERVER_LOG" ] || return 0
  size=$(wc -c < "$SERVER_LOG" 2>/dev/null || echo 0)
  if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
    tail -c 262144 "$SERVER_LOG" > "$SERVER_LOG.tmp" && mv "$SERVER_LOG.tmp" "$SERVER_LOG"
  fi
}

# ---- 单实例锁：抢不到说明另一个流程正在启动，直接退出（不是错误） ----
exec 9>"$LOCK"
if ! flock -n 9; then
  printf '另一个启动流程正在进行，本次跳过\n'
  log "跳过：已有启动流程持有锁"
  exit 0
fi

cd "$APP_DIR"

if ! web_up; then
  trim_log
  if [ "${PIWEB_NO_WAKELOCK:-0}" != 1 ] && command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock || true
  fi
  # setsid：自成会话；9>&- 把锁留给本脚本，别传给服务
  PIWEB_PIDFILE="$APP_DIR/server.pid" setsid "$APP_DIR/run-server.sh" >/dev/null 2>&1 9>&- &
fi

"$APP_DIR/ensure-policy.sh" >/dev/null 2>&1 || true

if [ "${PIWEB_NO_WATCHDOG:-0}" != "1" ] && [ -x "$APP_DIR/watchdog.sh" ]; then
  setsid "$APP_DIR/watchdog.sh" >/dev/null 2>&1 9>&- &
fi

i=0
while [ "$i" -lt 90 ]; do
  if web_up && policy_up; then
    date +%s > "$LAST_START"
    printf 'Pi Web ready: http://127.0.0.1:%s\n' "$PORT"
    exit 0
  fi
  sleep 1
  i=$((i + 1))
done

printf '启动超时，请检查 %s/server.log 和 policy.log\n' "$APP_DIR" >&2
log "启动超时（web=$PORT policy=$POLICY_PORT）"
exit 1
