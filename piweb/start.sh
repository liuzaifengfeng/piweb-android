#!/data/data/com.termux/files/usr/bin/sh
# Pi Web (agegr/pi-web) local launcher — loopback only.
# Usage: ~/piweb/start.sh [--port N] [--hostname H] [--no-open]
set -e
APP_DIR="$HOME/piweb"
exec node "$APP_DIR/node_modules/@agegr/pi-web/bin/pi-web.js" --no-open "$@"
