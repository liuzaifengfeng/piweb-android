import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

/**
 * 权限闸门扩展
 * - 策略文件：~/.pi/agent/permission-policy.json（由启动器 App / policy-server.js 写入）
 * - 命令：/perm 查看；/perm adb auto 设置
 * - 拦截：tool_call 事件里按策略阻断或请求批准
 */

type Level = "app" | "adb" | "root";
type Mode = "ask" | "auto" | "full";

const POLICY_PATH = join(homedir(), ".pi", "agent", "permission-policy.json");
const LEVELS: Level[] = ["app", "adb", "root"];
const MODES: Mode[] = ["ask", "auto", "full"];

const READ_ONLY_TOOLS = new Set([
  "read",
  "grep",
  "glob",
  "list",
  "find",
  "ls",
  "webfetch",
  "websearch",
  "todo",
  "todo_write",
  "todo_read",
]);

const ROOT_PATTERNS: RegExp[] = [
  /\br-root\.sh\b/,
  /shizuku\/r-root/,
  /(^|[\s;&|])su\s+(-|0|root|shell)/,
  /\/system\/bin\/su\b/,
];

const ADB_PATTERNS: RegExp[] = [
  /shizuku\/r\.sh/,
  /\brish\b/,
  /\bpm\s+(install|uninstall|grant|revoke|disable|enable)\b/,
  /\bcmd\s+(appops|deviceidle|package|activity)\b/,
  /\blogcat\b/,
];

const DESTRUCTIVE_PATTERNS: RegExp[] = [
  /\brm\s+-[a-zA-Z]*[rf][a-zA-Z]*\b/,
  /\bmkfs\b/,
  /\bdd\s+.*of=/,
  /\bchmod\s+-R\s+777\s+\//,
  /\b(shutdown|reboot|poweroff)\b/,
  /\/data\/adb\//,
  /\/system\//,
];

function readPolicy(): { level: Level; mode: Mode } {
  try {
    const raw = JSON.parse(readFileSync(POLICY_PATH, "utf8"));
    return {
      level: LEVELS.includes(raw?.level) ? raw.level : "app",
      mode: MODES.includes(raw?.mode) ? raw.mode : "ask",
    };
  } catch {
    return { level: "app", mode: "ask" };
  }
}

function writePolicy(next: { level: Level; mode: Mode }): void {
  mkdirSync(dirname(POLICY_PATH), { recursive: true });
  writeFileSync(
    POLICY_PATH,
    JSON.stringify({ ...next, updatedAt: new Date().toISOString() }, null, 2),
  );
}

function commandText(event: any): string {
  const input = event?.input;
  if (!input || typeof input !== "object") return "";
  if (typeof input.command === "string") return input.command;
  if (typeof input.script === "string") return input.script;
  if (typeof input.path === "string") return input.path + " " + JSON.stringify(input.content ?? "");
  return JSON.stringify(input).slice(0, 2000);
}

function matches(patterns: RegExp[], text: string): boolean {
  return patterns.some((re) => re.test(text));
}

const LEVEL_ZH: Record<Level, string> = { app: "普通", adb: "ADB", root: "root" };
const MODE_ZH: Record<Mode, string> = { ask: "请示", auto: "半自动", full: "放行" };

function label(p: { level: Level; mode: Mode }): string {
  return `权限 ${LEVEL_ZH[p.level]} · 批准 ${MODE_ZH[p.mode]}`;
}

export default function (pi: ExtensionAPI) {
  // 不再向 Pi Web 状态栏写权限信息：网页端无法保证与策略文件实时同步，
  // 以启动器侧栏（每 3 秒同步）为唯一显示入口。
  const clearStatus = (ctx: any) => {
    try {
      ctx.ui.setStatus("permission-gate", undefined);
    } catch {
      /* 某些模式没有状态栏 */
    }
  };

  pi.on("session_start", async (_event, ctx) => {
    clearStatus(ctx);
  });

  pi.registerCommand("perm", {
    description: "查看/设置权限等级：/perm [app|adb|root] [ask|auto|full]",
    handler: async (args: string, ctx: any) => {
      const parts = String(args || "")
        .trim()
        .split(/\s+/)
        .filter(Boolean);
      const current = readPolicy();

      if (parts.length === 0) {
        ctx.ui.notify(`${label(current)}（策略文件 ${POLICY_PATH}）`, "info");
        return;
      }

      const next = { ...current };
      for (const part of parts) {
        if (LEVELS.includes(part as Level)) next.level = part as Level;
        else if (MODES.includes(part as Mode)) next.mode = part as Mode;
        else {
          ctx.ui.notify(`无法识别的参数：${part}`, "warning");
          return;
        }
      }

      if (next.level === "root" && current.level !== "root") {
        const ok = await ctx.ui.confirm(
          "启用 root 权限？",
          "Agent 将可以通过 Shizuku root 执行任意命令（含修改系统）。确定继续？",
        );
        if (!ok) {
          ctx.ui.notify("已取消", "info");
          return;
        }
      }

      writePolicy(next);
      ctx.ui.notify(`已更新：${label(next)}`, "info");
    },
  });

  pi.on("tool_call", async (event: any, ctx: any) => {
    const tool = String(event?.toolName || "");
    if (READ_ONLY_TOOLS.has(tool)) return;

    const policy = readPolicy();
    const text = commandText(event);
    const needsRoot = matches(ROOT_PATTERNS, text);
    const needsAdb = matches(ADB_PATTERNS, text);

    // 等级不足：直接阻断，并告诉用户怎么放开
    if (needsRoot && policy.level !== "root") {
      return {
        block: true,
        reason:
          `该调用需要 root 权限，当前等级为 ${policy.level}。` +
          `如需允许，请把权限切到 root（/perm root，或启动器侧栏切换并验证指纹）。`,
      };
    }
    if (needsAdb && policy.level === "app") {
      return {
        block: true,
        reason:
          `该调用需要 ADB 级权限，当前为 app 级。` +
          `请切换到 adb 或 root（/perm adb，或启动器侧栏切换）。`,
      };
    }

    if (policy.mode === "full") return;

    const destructive = matches(DESTRUCTIVE_PATTERNS, text);
    const needConfirm =
      policy.mode === "ask" ? true : destructive || needsAdb || needsRoot;

    if (!needConfirm) return;

    const preview = text.length > 400 ? text.slice(0, 400) + " …" : text;
    const ok = await ctx.ui.confirm(
      `允许执行 ${tool}？`,
      `${preview}\n\n当前策略：${label(policy)}`,
    );
    if (!ok) {
      return { block: true, reason: "用户拒绝了该工具调用" };
    }
  });
}
