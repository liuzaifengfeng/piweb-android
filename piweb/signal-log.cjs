"use strict";
// 通过 `node --require` 预加载：只记录"pi-web 启动器收到了什么信号、什么时候"。
// 不改变 pi-web 自己的处理逻辑（Node 会调用所有监听器）。
//
// 目的：下次服务再被停掉时，能区分
//   - 整组进程同时被杀（系统清理）→ signals.log 里 policy-server 的日志也几乎同一秒
//   - 只有 pi-web 被杀（某处执行了 stop.sh / pi-web stop）
//
// 另外故意拦住 SIGHUP：Termux 会话收尾时会往进程组发 SIGHUP，
// 我们让服务忽略它，而不是被顺手带走。

const fs = require("node:fs");
const path = require("node:path");

const FILE = path.join(__dirname, "signals.log");

function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function note(what) {
  try {
    fs.appendFileSync(FILE, `${stamp()} pid=${process.pid} ppid=${process.ppid} ${what}\n`);
  } catch {
    /* 记录失败不影响服务 */
  }
}

for (const sig of ["SIGTERM", "SIGINT", "SIGHUP", "SIGQUIT"]) {
  try {
    process.on(sig, () => note(`${sig} received`));
  } catch {
    /* 平台不支持就跳过 */
  }
}
note("launcher started");
