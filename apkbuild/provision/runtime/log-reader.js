'use strict';
const fs = require('node:fs');
const path = require('node:path');

// Fixed files only: never accept a client-supplied path. Bound reads even for huge logs.
function redact(text, token = '') {
  if (token) text = text.split(token).join('[REDACTED]');
  return text
    .replace(/\b(Bearer\s+)[^\s"',}]+/gi, '$1[REDACTED]')
    .replace(/((?:["']?)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|policy[_-]?token|password|secret|authorization)(?:["']?)\s*[:=]\s*)(?:"[^"\n]*"|'[^'\n]*'|[^\s,;}]+)/gi, '$1[REDACTED]')
    .replace(/\bsk-[A-Za-z0-9_-]{8,}/g, '[REDACTED]')
    .replace(/(https?:\/\/)[^\s/@]+:[^\s/@]+@/gi, '$1[REDACTED]@');
}
function recentLogs(dir, token) {
  return ['server.log', 'policy.log', 'signals.log'].map(name => {
    let fd;
    try {
      fd = fs.openSync(path.join(dir, name), fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
      const stat = fs.fstatSync(fd);
      if (!stat.isFile()) throw new Error('Not a regular file');
      const size = Math.min(stat.size, 32768);
      const buffer = Buffer.alloc(size);
      const count = fs.readSync(fd, buffer, 0, size, Math.max(0, stat.size - size));
      let text = buffer.subarray(0, count).toString('utf8');
      if (stat.size > size) text = text.includes('\n') ? text.slice(text.indexOf('\n') + 1) : '';
      return `=== ${name} ===\n${redact(text, token).split('\n').slice(-120).join('\n')}`;
    } catch (e) {
      return `=== ${name} ===\n${e.code === 'ENOENT' ? '暂无日志' : '无法读取（' + (e.code || e.message) + '）'}`;
    } finally {
      if (fd !== undefined) fs.closeSync(fd);
    }
  }).join('\n\n');
}
module.exports = { redact, recentLogs };
