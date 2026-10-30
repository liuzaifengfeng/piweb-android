#!/data/data/com.termux/files/usr/bin/sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
read_config() { node "$APP_DIR/runtime-config.js" "$1"; }
PORT=$(read_config web_port)
POLICY_PORT=$(read_config policy_port)
export PI_CODING_AGENT_DIR
PI_CODING_AGENT_DIR=$(read_config agent_dir)
export PIWEB_CONFIG="$APP_DIR/deployment.json"
export PI_PERM_PORT="$POLICY_PORT"
export PIWEB_POLICY_PATH="$PI_CODING_AGENT_DIR/permission-policy.json"
export PI_WEB_NO_OPEN=1 PI_WEB_SKIP_VERSION_CHECK=1
cd "$APP_DIR"
health() { curl -fsS --max-time 2 "http://127.0.0.1:$1/$2" >/dev/null 2>&1; }
if ! health "$POLICY_PORT" health; then
  nohup node "$APP_DIR/policy-server.js" >> "$APP_DIR/policy.log" 2>&1 &
  echo "$!" > "$APP_DIR/policy.pid"
fi
if ! health "$PORT" api/home; then
  if [ "${PIWEB_NO_WAKELOCK:-0}" != 1 ] && command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock || true
  fi
  nohup node "$APP_DIR/node_modules/@agegr/pi-web/bin/pi-web.js" --no-open -H 127.0.0.1 -p "$PORT" >> "$APP_DIR/server.log" 2>&1 &
  echo "$!" > "$APP_DIR/server.pid"
fi
# PID ownership is verified again before stopping; no pkill or broad process matching.
i=0
while [ "$i" -lt 90 ]; do
  if health "$PORT" api/home && health "$POLICY_PORT" health; then
    printf 'Pi Web ready: http://127.0.0.1:%s\n' "$PORT"
    exit 0
  fi
  sleep 1
  i=$((i+1))
done
printf '启动超时，请检查 %s/server.log 和 policy.log\n' "$APP_DIR" >&2
exit 1
