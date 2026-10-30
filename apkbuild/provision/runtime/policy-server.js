'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const config = JSON.parse(fs.readFileSync(process.env.PIWEB_CONFIG || path.join(__dirname, 'deployment.json'), 'utf8'));
const policy = path.join(config.agent_dir, 'permission-policy.json');
const token = Buffer.from(config.policy_token || '');
if (token.length < 32) throw new Error('Policy token missing');
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
server.on('error', e => { console.error(e.message); process.exit(1); });
server.listen(config.policy_port, '127.0.0.1');
for (const sig of ['SIGINT','SIGTERM']) process.on(sig, () => server.close(() => process.exit(0)));
