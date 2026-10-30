#!/data/data/com.termux/files/usr/bin/sh
# Shizuku 通道 —— 已降级为 ADB 级别：uid 2000(shell) / SELinux u:r:shell:s0
# 用法：r.sh -c '命令'     或     r.sh <脚本路径>
# 说明：命令以 base64 内联传递，因为 shell(2000) 读不到 Termux 私有目录。
set -u
RISH="$HOME/shizuku/rish"

if [ "${1:-}" = "-c" ]; then
  shift
  CMD="$*"
elif [ $# -gt 0 ] && [ -f "$1" ]; then
  CMD="$(cat "$1")"
else
  echo "用法: r.sh -c '命令'  或  r.sh <脚本路径>" >&2
  exit 1
fi

# 绝不能以 root 运行：显式降权到 shell 域
if [ -z "$CMD" ]; then
  echo "空命令" >&2
  exit 1
fi

CMD="cd /data/local/tmp 2>/dev/null || cd /; "$CMD
B64=$(printf '%s' "$CMD" | base64 | tr -d '\n')
exec env RISH_APPLICATION_ID=com.termux sh "$RISH" -c \
  "exec su 2000 -c 'exec /system/bin/runcon u:r:shell:s0 /system/bin/sh -c \"echo $B64 | base64 -d | /system/bin/sh\"'"
