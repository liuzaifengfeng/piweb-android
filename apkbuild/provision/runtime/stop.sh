#!/data/data/com.termux/files/usr/bin/sh
# Only stop this installation's recorded, verified Node processes.
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
node - "$APP_DIR" <<'JS'
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
for (const [file, script] of [['server.pid', 'node_modules/@agegr/pi-web/bin/pi-web.js'], ['policy.pid', 'policy-server.js']]) {
  const pidFile = path.join(root, file);
  if (!fs.existsSync(pidFile)) continue;
  const pid = Number(fs.readFileSync(pidFile, 'utf8').trim());
  if (!Number.isInteger(pid) || pid < 2) throw new Error('Invalid PID file');
  let args;
  try { args = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0'); }
  catch (e) { if (e.code === 'ENOENT') { fs.unlinkSync(pidFile); continue; } throw e; }
  if (!args.includes(path.join(root, script))) throw new Error(`PID ${pid} does not belong to this installation; refusing to stop`);
  try { process.kill(pid, 'SIGTERM'); } catch (e) { if (e.code !== 'ESRCH') throw e; }
  fs.unlinkSync(pidFile);
}
JS
if [ "${1:-}" = --release-lock ]; then termux-wake-unlock; fi
