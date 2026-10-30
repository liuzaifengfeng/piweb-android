#!/usr/bin/env node
// 给 stdin 的每一行加本地时间戳，写到 stdout。
// 用途：launch.sh 用它把 pi-web / Next.js 的输出转成带时间的日志，
// 以后出事能直接看出"几点几分谁先死"。
//
// 设计要点：这个进程是服务的"下游管道"，它挂了会让上游写 stdout 时吃到 EPIPE。
// 所以这里吞掉管道错误，永不主动退出。
"use strict";

const out = process.stdout;

function stamp(d = new Date()) {
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

out.on("error", () => { /* 下游关了就别崩 */ });
process.stdin.on("error", () => { /* 上游关了也安静退出 */ });
for (const sig of ["SIGHUP", "SIGPIPE"]) {
  try { process.on(sig, () => { /* 忽略：被终端会话清理误伤时不要跟着死 */ }); } catch { /* 平台不支持就算了 */ }
}

let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buf += chunk;
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i);
    buf = buf.slice(i + 1);
    writeLine(line);
  }
});
process.stdin.on("end", () => {
  if (buf) writeLine(buf);
  buf = "";
});

function writeLine(line) {
  try {
    out.write(`${stamp()} ${line}\n`);
  } catch {
    // 写不出去就丢弃，绝不抛
  }
}
