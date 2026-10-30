#!/usr/bin/env node
// 权限策略服务：给启动器 App 读写权限策略，仅监听 127.0.0.1:30142。
// 策略文件：~/.pi/agent/permission-policy.json（pi 扩展直接读取）
"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PI_PERM_PORT || 30142);
const HOME = process.env.HOME || "/data/data/com.termux/files/home";
const POLICY = path.join(HOME, ".pi", "agent", "permission-policy.json");
const LEVELS = new Set(["app", "adb", "root"]);
const MODES = new Set(["ask", "auto", "full"]);
const DEFAULT = { level: "app", mode: "ask" };

// 日志统一带本地时间戳，便于和 server.log / signals.log 对齐时间线
function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
function log(msg) {
  console.log(`${stamp()} [policy] ${msg}`);
}

function normalize(raw) {
  const o = raw && typeof raw === "object" ? raw : {};
  return {
    level: LEVELS.has(o.level) ? o.level : DEFAULT.level,
    mode: MODES.has(o.mode) ? o.mode : DEFAULT.mode,
  };
}

function readPolicy() {
  try {
    return normalize(JSON.parse(fs.readFileSync(POLICY, "utf8")));
  } catch {
    return { ...DEFAULT };
  }
}

function writePolicy(payload) {
  const next = normalize(payload);
  fs.mkdirSync(path.dirname(POLICY), { recursive: true });
  const data = { ...next, updatedAt: new Date().toISOString() };
  const tmp = POLICY + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, POLICY);
  return next;
}

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
  });
  res.end(body);
}

const server = http.createServer((req, res) => {
  const url = (req.url || "").split("?")[0];

  if (url === "/health") return send(res, 200, { ok: true, pid: process.pid });

  if (url === "/policy") {
    if (req.method === "GET") return send(res, 200, readPolicy());
    if (req.method === "POST") {
      if (!/^application\/json/i.test(req.headers["content-type"] || "")) {
        return send(res, 415, { error: "content-type must be application/json" });
      }
      let body = "";
      let tooBig = false;
      req.on("data", (c) => {
        body += c;
        if (body.length > 4096 && !tooBig) {
          tooBig = true;
          req.destroy();
        }
      });
      req.on("end", () => {
        if (tooBig) return;
        try {
          send(res, 200, writePolicy(JSON.parse(body)));
        } catch (e) {
          send(res, 400, { error: String((e && e.message) || e) });
        }
      });
      return;
    }
    return send(res, 405, { error: "method not allowed" });
  }

  send(res, 404, { error: "not found" });
});

server.on("error", (e) => {
  log(e.message);
  process.exit(1);
});

server.listen(PORT, "127.0.0.1", () => {
  log(`listening on 127.0.0.1:${PORT} (${POLICY})`);
});

for (const sig of ["SIGTERM", "SIGINT"]) {
  process.on(sig, () => {
    // 和 signals.log 对照：如果两个文件里同一次停止的时间几乎相同，
    // 说明是整组进程被一起杀掉（系统清理）；只有 pi-web 有记录则多半是 stop.sh。
    log(`${sig} received, closing`);
    server.close(() => process.exit(0));
  });
}
