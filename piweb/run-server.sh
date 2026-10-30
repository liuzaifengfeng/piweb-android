#!/data/data/com.termux/files/usr/bin/sh
# 由 launch.sh 通过 setsid 调用：让 Pi Web 脱离 Termux 的会话/进程组独立运行。
#
# 这里只做三件事：
#   1. 用独立会话启动（setsid 由调用方负责）
#   2. 把服务输出接到 log-ts.js，落成带时间戳的日志
#   3. 放开 launch.sh 的单实例锁（服务本身不该一直占着锁）
#
# 可用环境变量覆盖（便于隔离测试）：
#   PI_WEB_PORT   端口，默认 30141
#   PIWEB_HOST    绑定地址，默认 127.0.0.1
#   PIWEB_LOG     日志文件，默认 ~/piweb/server.log
set -u

APP_DIR="$HOME/piweb"
HOST="${PIWEB_HOST:-127.0.0.1}"
PORT="${PI_WEB_PORT:-30141}"
LOG="${PIWEB_LOG:-$APP_DIR/server.log}"
PI_WEB_BIN="$APP_DIR/node_modules/@agegr/pi-web/bin/pi-web.js"

# 9 号 fd 是 launch.sh 的单实例锁，别传给服务，否则锁永远不释放
exec 9>&-

PI_WEB_NO_OPEN=1 PI_WEB_SKIP_VERSION_CHECK=1 \
  node --require "$APP_DIR/signal-log.cjs" "$PI_WEB_BIN" --no-open -H "$HOST" -p "$PORT" 2>&1 \
  | node "$APP_DIR/log-ts.js" >> "$LOG"
