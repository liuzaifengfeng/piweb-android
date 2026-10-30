#!/data/data/com.termux/files/usr/bin/sh
# 停止 Pi Web 服务。
#
# 用法：
#   stop.sh            常规停止（带"连点保护"：10 秒内刚启动过就不重复停，避免
#                      连续点"重启服务"时互相踩，这是 2026-10-30 那次故障的直接原因）
#   stop.sh --force    强制停止，不做连点保护
#
# 注意（重要）：默认 **不释放 wakelock**。
# 在 HyperOS 上，wakelock 是 Termux 唯一的"我还活着"信号；一旦释放，
# 只要切到别的 App，系统就会把 Termux 回收（连正在跑的 shell 一起杀掉）。
# 需要释放时显式加 --release-lock。
set -u
APP_DIR="$HOME/piweb"
PI_WEB_BIN="$APP_DIR/node_modules/@agegr/pi-web/bin/pi-web.js"
STAMP="$APP_DIR/.last-start"
LOG="$APP_DIR/stop.log"
DEBOUNCE=${STOP_DEBOUNCE:-10}
RELEASE_LOCK=0
FORCE=0

for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    --release-lock) RELEASE_LOCK=1 ;;
  esac
done

say() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

# ---- 连点保护 ----
if [ "$FORCE" = "0" ] && [ -f "$STAMP" ]; then
  age=$(( $(date +%s) - $(stat -c %Y "$STAMP" 2>/dev/null || echo 0) ))
  if [ "$age" -lt "$DEBOUNCE" ]; then
    echo "服务 ${age}s 前刚启动，本次跳过停止（连点保护，避免把刚起的服务又杀掉）"
    say "跳过停止：${age}s 前刚启动（debounce ${DEBOUNCE}s）"
    exit 0
  fi
fi

say "开始停止（force=$FORCE）"
# 给看门狗一个"别插手"的信号：20 秒内不自动重启
date +%s > "$APP_DIR/.last-stop"

pkill -f '^node .*policy-server\.js' >/dev/null 2>&1 || true
[ -f "$PI_WEB_BIN" ] && node "$PI_WEB_BIN" stop 2>&1 | head -3

if [ "$RELEASE_LOCK" = "1" ]; then
  if command -v termux-wake-unlock >/dev/null 2>&1; then
    termux-wake-unlock >/dev/null 2>&1 || true
    echo "已释放 wakelock（Termux 失去后台保护，切走 App 可能被杀）"
  fi
else
  echo "（保留 wakelock，保护 Termux 不被系统回收）"
fi

if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:30141/"; then
  echo "警告：127.0.0.1:30141 仍有进程在响应"
  say "停止后端口仍在响应"
else
  echo "服务已停止"
  say "停止完成"
fi
