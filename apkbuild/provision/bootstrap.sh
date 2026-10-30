#!/data/data/com.termux/files/usr/bin/sh
# Reviewed first-stage installer. Only executed after the user confirms in the APK.
set -eu
umask 077
BASE=$1
PY_HASH=$2
ACTION=$3
NAME=$4
WEB_PORT=$5
POLICY_PORT=$6
MANIFEST_HASH=$7
for cmd in curl python sha256sum mktemp; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "缺少 $cmd。请先在 Termux 执行：pkg install curl python" >&2; exit 20; }
done
case "$BASE" in http://127.0.0.1:*/[a-f0-9]*) ;; *) echo '非法安装资源地址' >&2; exit 21;; esac
JOB=$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/piweb-install.XXXXXXXX")
# Leave this explicitly named work directory on failure for diagnosis; never delete unknown directories.
curl --fail --silent --show-error --connect-timeout 5 --max-time 90 "$BASE/provision.py" -o "$JOB/provision.py"
printf '%s  %s\n' "$PY_HASH" "$JOB/provision.py" | sha256sum -c - >/dev/null
exec python "$JOB/provision.py" --base "$BASE" --manifest-sha "$MANIFEST_HASH" --action "$ACTION" --name "$NAME" --web-port "$WEB_PORT" --policy-port "$POLICY_PORT"
