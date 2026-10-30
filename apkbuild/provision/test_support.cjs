'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { recentLogs, redact } = require('./runtime/log-reader');

test('log reads are bounded, fixed-path, symlink-safe and redacted', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'piweb-log-test-'));
  try {
    fs.writeFileSync(path.join(dir, 'server.log'), 'old\n'.repeat(20000) +
      'api_key="private-value"\nAuthorization: Bearer bearer-value\nsk-1234567890abcdefgh\nlocal-token\n');
    fs.writeFileSync(path.join(dir, 'private'), 'MUST NOT BE READ');
    fs.symlinkSync(path.join(dir, 'private'), path.join(dir, 'policy.log'));
    const text = recentLogs(dir, 'local-token');
    assert(text.length < 33000);
    for (const secret of ['private-value', 'bearer-value', 'sk-1234567890abcdefgh', 'local-token', 'MUST NOT BE READ']) assert(!text.includes(secret));
    assert(text.includes('暂无日志'));
    assert(text.includes('无法读取'));
    assert(!redact('https://user:password@example.com').includes('password'));
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('logs endpoint requires token and does not accept arbitrary paths or writes', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'piweb-policy-test-'));
  const token = 'a'.repeat(64);
  fs.writeFileSync(path.join(dir, 'deployment.json'), JSON.stringify({
    agent_dir: dir, install_dir: dir, policy_token: token, policy_port: 0
  }));
  fs.writeFileSync(path.join(dir, 'server.log'), 'api_key=private-value\nhello\n');
  // Use port 0 and obtain the actual bound port from Node's server object, without
  // touching the live service or relying on a fixed test port.
  const script = `const http = require('node:http');
    const listen = http.Server.prototype.listen;
    http.Server.prototype.listen = function(...args) {
      this.on('listening', () => console.log('TEST_PORT=' + this.address().port));
      return listen.apply(this, args);
    };
    require(${JSON.stringify(path.join(__dirname, 'runtime/policy-server.js'))});`;
  const child = spawn(process.execPath, ['-e', script], {
    env: { ...process.env, PIWEB_CONFIG: path.join(dir, 'deployment.json'), PIWEB_PIDFILE: '', PIWEB_SIGNAL_LOG: path.join(dir, 'signals.log') },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  try {
    const port = await new Promise((resolve, reject) => {
      let output = '';
      const timer = setTimeout(() => reject(new Error('Startup timed out')), 5000);
      child.stdout.on('data', chunk => {
        output += chunk;
        const match = output.match(/TEST_PORT=(\d+)/);
        if (match) { clearTimeout(timer); resolve(match[1]); }
      });
      child.once('exit', () => { clearTimeout(timer); reject(new Error('Server exited')); });
    });
    const url = `http://127.0.0.1:${port}`;
    assert.equal((await fetch(url + '/health')).status, 200);
    assert.equal((await fetch(url + '/logs')).status, 401);
    assert.equal((await fetch(url + '/logs', { headers: { Authorization: 'Bearer wrong' } })).status, 401);
    const headers = { Authorization: 'Bearer ' + token };
    const response = await fetch(url + '/logs', { headers });
    assert.equal(response.status, 200);
    const { text } = await response.json();
    assert(text.includes('hello'));
    assert(!text.includes('private-value'));
    assert.equal((await fetch(url + '/logs?file=/etc/passwd', { headers })).status, 404);
    assert.equal((await fetch(url + '/logs', { headers, method: 'POST' })).status, 405);
    assert.equal((await fetch(url + '/policy', { headers })).status, 200);
  } finally {
    if (child.exitCode === null) { child.kill('SIGTERM'); await once(child, 'exit'); }
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
