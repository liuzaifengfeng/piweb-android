#!/data/data/com.termux/files/usr/bin/sh
# 停止 Pi Web（托管安装版）
#
# 只停"本安装目录记录的、命令行能对上号的"进程；pid 对不上就报错退出，绝不按名字乱杀。
#
# 连点保护：只有带 --restart（启动器"重启服务"按钮就是这么调的）时才生效——
# 8 秒内刚启动过就跳过这次停止，避免连点把刚起来的服务又杀掉。
# 单独执行 stop.sh（手动停止、安装器自检收尾）永不做连点保护。
#
# 用法：stop.sh [--restart] [--force] [--release-lock]
set -u

APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
STAMP="$APP_DIR/.last-start"
DEBOUNCE=${STOP_DEBOUNCE:-8}
FORCE=0
RELEASE_LOCK=0

RESTART=0
for arg in "$@"; do
  case "$arg" in
    --restart) RESTART=1 ;;
    --force) FORCE=1 ;;
    --release-lock) RELEASE_LOCK=1 ;;
  esac
done

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$APP_DIR/stop.log"; }

# ---- 连点保护（仅 --restart） ----
if [ "$FORCE" = 0 ] && [ "$RESTART" = 1 ] && [ -f "$STAMP" ]; then
  age=$(( $(date +%s) - $(stat -c %Y "$STAMP" 2>/dev/null || echo 0) ))
  if [ "$age" -lt "$DEBOUNCE" ]; then
    printf '服务 %ss 前刚启动，本次跳过停止（连点保护）\n' "$age"
    log "跳过停止：${age}s 前刚启动"
    exit 0
  fi
fi

log "开始停止（restart=$RESTART force=$FORCE）"
# 让看门狗 20 秒内别插手，给成对启动留时间
date +%s > "$APP_DIR/.last-stop"

# ---- 停看门狗 / 服务 / 策略服务：只认本安装目录记录的 pid ----
node - "$APP_DIR" <<'JS'
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const targets = [
  ['watchdog.pid', 'watchdog.sh'],
  ['server.pid', 'node_modules/@agegr/pi-web/bin/pi-web.js'],
  ['policy.pid', 'policy-server.js'],
];
for (const [file, script] of targets) {
  const pidFile = path.join(root, file);
  if (!fs.existsSync(pidFile)) continue;
  const pid = Number(fs.readFileSync(pidFile, 'utf8').trim());
  if (!Number.isInteger(pid) || pid < 2) throw new Error(`Invalid PID file: ${file}`);
  let args;
  try {
    args = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0');
  } catch (e) {
    if (e.code === 'ENOENT') { fs.rmSync(pidFile, { force: true }); continue; }
    throw e;
  }
  const wanted = path.join(root, script);
  const owned = args.some(a => a === wanted || a.endsWith('/' + script) && a.includes(root));
  if (!owned) throw new Error(`PID ${pid} (${file}) does not belong to this installation; refusing to stop`);
  try { process.kill(pid, 'SIGTERM'); } catch (e) { if (e.code !== 'ESRCH') throw e; }
  fs.rmSync(pidFile, { force: true });
}
JS

if [ "$RELEASE_LOCK" = 1 ]; then
  command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock || true
fi
exit 0
