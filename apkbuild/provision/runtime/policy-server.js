'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { recentLogs } = require('./log-reader');
const config = JSON.parse(fs.readFileSync(process.env.PIWEB_CONFIG || path.join(__dirname, 'deployment.json'), 'utf8'));
const policy = path.join(config.agent_dir, 'permission-policy.json');
const token = Buffer.from(config.policy_token || '');
if (token.length < 32) throw new Error('Policy token missing');

const SIGNAL_LOG = process.env.PIWEB_SIGNAL_LOG || path.join(__dirname, 'signals.log');
const PID_FILE = process.env.PIWEB_PIDFILE || '';

// 日志带本地时间戳，便于和 server.log / signals.log 对齐时间线
function stamp() {
  const d = new Date();
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
function log(msg) { console.log(`${stamp()} [policy] ${msg}`); }
function noteSignal(what) {
  try { fs.appendFileSync(SIGNAL_LOG, `${stamp()} pid=${process.pid} ppid=${process.ppid} ${what}\n`); } catch { /* 记录失败不影响服务 */ }
}

if (PID_FILE) {
  try {
    fs.writeFileSync(PID_FILE, String(process.pid), { mode: 0o600 });
    process.on('exit', () => {
      try {
        if (fs.readFileSync(PID_FILE, 'utf8').trim() === String(process.pid)) fs.unlinkSync(PID_FILE);
      } catch { /* 已经没了就算了 */ }
    });
  } catch { /* pid 写不进去不影响服务 */ }
}

function read() {
  try {
    const p = JSON.parse(fs.readFileSync(policy, 'utf8'));
    return { level: ['app','adb','root'].includes(p.level) ? p.level : 'app', mode: ['ask','auto','full'].includes(p.mode) ? p.mode : 'ask' };
  } catch { return { level: 'app', mode: 'ask' }; }
}
const server = http.createServer((req, res) => {
  const send = (code, obj) => { res.writeHead(code, {'content-type':'application/json', 'cache-control':'no-store'}); res.end(JSON.stringify(obj)); };
  if (req.method === 'GET' && req.url === '/health') return send(200, {ok:true, install_dir:config.install_dir});
  const supplied = Buffer.from((req.headers.authorization || '').replace(/^Bearer /, ''));
  if (supplied.length !== token.length || !crypto.timingSafeEqual(supplied, token)) return send(401, {error:'Unauthorized'});
  if (req.url === '/logs') {
    if (req.method !== 'GET') return send(405, {error:'Method not allowed'});
    return send(200, {text:recentLogs(config.install_dir, config.policy_token)});
  }
  if (req.url !== '/policy') return send(404, {error:'Not found'});
  if (req.method === 'GET') return send(200, read());
  if (req.method !== 'POST') return send(405, {error:'Method not allowed'});
  if (!/^application\/json\b/i.test(req.headers['content-type'] || '')) return send(415, {error:'JSON required'});
  let body = '';
  req.on('data', chunk => { body += chunk; if (body.length > 4096) req.destroy(); });
  req.on('end', () => {
    let p;
    try { p = JSON.parse(body); } catch { return send(400, {error:'Invalid JSON'}); }
    if (!['app','adb','root'].includes(p.level) || !['ask','auto','full'].includes(p.mode)) return send(400, {error:'Invalid policy'});
    try {
      const next = {level:p.level, mode:p.mode, updatedAt:new Date().toISOString()};
      const tmp = policy + '.tmp-' + crypto.randomBytes(8).toString('hex');
      fs.writeFileSync(tmp, JSON.stringify(next, null, 2), {mode:0o600});
      fs.renameSync(tmp, policy);
      send(200, next);
    } catch { send(500, {error:'Cannot save policy'}); }
  });
});
server.requestTimeout = 10000;
server.headersTimeout = 10000;
server.on('error', e => { log(e.message); process.exit(1); });
server.listen(config.policy_port, '127.0.0.1', () => {
  log(`listening on 127.0.0.1:${config.policy_port} (install_dir=${config.install_dir})`);
});
for (const sig of ['SIGINT','SIGTERM']) {
  process.on(sig, () => {
    // 与 signals.log 对照：同秒被杀多半是整组被系统清理，只有它有记录多为 stop.sh
    log(`${sig} received, closing`);
    noteSignal(`${sig} received`);
    server.close(() => process.exit(0));
  });
}
