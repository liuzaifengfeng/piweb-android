#!/data/data/com.termux/files/usr/bin/sh
# 由 launch.sh 通过 setsid 调用：真正启动 pi-web，并把输出接给 log-ts.js 加时间戳。
# 端口与 agent 目录来自 deployment.json；server.pid 由 node 自己写（signal-log.cjs）。
set -u

APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PORT=$(node "$APP_DIR/runtime-config.js" web_port)
export PI_CODING_AGENT_DIR
PI_CODING_AGENT_DIR=$(node "$APP_DIR/runtime-config.js" agent_dir)
export PIWEB_CONFIG="$APP_DIR/deployment.json"
export PI_WEB_NO_OPEN=1 PI_WEB_SKIP_VERSION_CHECK=1

# 9 号 fd 是 launch.sh 的单实例锁，别让服务一直占着
exec 9>&-

node --require "$APP_DIR/signal-log.cjs" \
  "$APP_DIR/node_modules/@agegr/pi-web/bin/pi-web.js" \
  --no-open -H 127.0.0.1 -p "$PORT" 2>&1 \
  | node "$APP_DIR/log-ts.js" >> "$APP_DIR/server.log"
