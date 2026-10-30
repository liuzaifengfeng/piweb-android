"use strict";
// 通过 `node --require` 预加载：
//   1. 记录"本进程收到了什么信号、什么时候收到"（用于判断是系统清理还是有人 stop）
//   2. 若设置了 PIWEB_PIDFILE，则把自己的 pid 写进该文件，退出时删除
//      —— stop.sh 靠这个 pid 做"只停自己这套安装"的归属校验
//
// 不改变宿主程序自己的处理逻辑（Node 会调用所有监听器）。
// 另外故意拦住 SIGHUP：Termux 会话收尾时会往进程组发 SIGHUP，服务不应被顺手带走。

const fs = require("node:fs");
const path = require("node:path");

const SIGNAL_LOG = process.env.PIWEB_SIGNAL_LOG || path.join(__dirname, "signals.log");
const PID_FILE = process.env.PIWEB_PIDFILE || "";

function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function note(what) {
  try {
    fs.appendFileSync(SIGNAL_LOG, `${stamp()} pid=${process.pid} ppid=${process.ppid} ${what}\n`);
  } catch {
    /* 记录失败不影响服务 */
  }
}

if (PID_FILE) {
  try {
    fs.writeFileSync(PID_FILE, String(process.pid), { mode: 0o600 });
    const cleanup = () => {
      try {
        // 只删自己写的那一份，避免覆盖别人
        if (fs.readFileSync(PID_FILE, "utf8").trim() === String(process.pid)) fs.unlinkSync(PID_FILE);
      } catch {
        /* 已经没了就算了 */
      }
    };
    process.on("exit", cleanup);
  } catch {
    /* pid 写不进去不影响服务 */
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
