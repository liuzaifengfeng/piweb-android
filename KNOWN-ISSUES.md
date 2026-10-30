# 已知异常与问题

记录"发生过的异常 + 证据 + 处理状态"，避免重复踩坑。新的排在最上面。

状态标记：**已修复** / **已缓解** / **未修复** / **待查**

---

## 1. 对话被「重启服务」打断，页面变成 `Pi Web is offline`（已缓解）

**现象**：服务突然没了，对话那一轮没有任何回复；页面显示 `Pi Web is offline`；`server.log` 出现成片
`EADDRINUSE`。

**原因**：侧栏「重启服务」= `stop.sh && launch.sh`，等于"先杀再起"。连点或隔几分钟再点，都会打断
正在进行的对话；重复启动还会抢端口。

**证据**（2026-10-30）：`~/piweb/stop.log` 记录 `14:01:40`、`14:07:50` 两次 `force=0`
（`force=0` 只来自 App 侧栏按钮，桌面"停止"图标已改成 `--force`）。

**已做**：`stop.sh` 加 10 秒连点保护；`launch.sh` 加单实例锁；日志加时间戳。
**未做**：按钮二次确认（需重编 APK）；页面显眼的"重载/重连"入口。

**临时对策**：卡住时先按「重载」（只刷页面），别点「重启服务」。

---

## 2. `stop.sh` 偶发卡住 90 秒以上（未修复）

**证据**：`stop.log` 里 `14:01:40 开始停止` → `14:03:17 停止完成`，共 97 秒。

**原因推测**：`pi-web stop` 在等服务进程退出，但对方关闭较慢（SSE/agent 会话收尾），没有超时兜底。

**计划**：给 `stop.sh` 的 `pi-web stop` 加超时（如 15s），超时后对 launcher/next 进程直接 `SIGKILL`。

---

## 3. 服务被外部 SIGTERM 终止（已加归因手段）

**现象**：2026-10-30 13:39 服务整组消失，界面掉到离线页。

**判定方法**（现已可用）：

```bash
tail -5 ~/piweb/signals.log   # pi-web 收到 SIGTERM 的时刻
tail -5 ~/piweb/stop.log      # 是不是自己点了"停止/重启"
tail -5 ~/piweb/policy.log    # 策略服务收到的 SIGTERM 时刻
```

- 两个时间几乎同秒 → 整组进程被一起杀（系统清理）
- 只有 pi-web 有记录，且 `stop.log` 有空 → 外部脚本/未知来源

---

## 4. 新设备安装向导卡在"等待 Termux 安装器回调"（待查）

**设备**：Redmi K70 Pro，HyperOS 3.0.306.0，Termux 0.118.3。

**现象**：点"检查目录"后一直等待，超 20 秒无返回（正常应几秒内返回）。

**已排查**：架构（ARM64）与 Termux 版本都在支持范围内；不是 root/省电设置问题。
**待查**：Termux `RUN_COMMAND` 是否真的执行（`allow-external-apps`）、PendingIntent 回调是否被系统丢弃、
HyperOS 对后台服务启动的限制。

---

## 5. 新设备 `curl` 已损坏导致初始化失败（已定位，非本项目 bug）

**现象**：向导的第一条命令链在 `pkg install` 处整体失败，`python` 装不上。

**结论**：那台设备的 `curl` 在运行我们的命令**之前**就已经坏了（`libcurl 8.23.0` 与
`libngtcp2 1.25.0` 版本错配，符号缺失）。不是向导脚本写错。

**处理**：在新设备上先 `pkg upgrade` 或重装 `libcurl`，再执行向导命令。

---

## 6. `apkbuild/provision/runtime/` 与 `~/piweb/` 版本分叉（未修复）

`provision/runtime/` 里打包用的 `launch.sh` / `stop.sh` / `policy-server.js` 仍是加固前的版本。
`~/piweb/` 是最新的。

**影响**：新装设备拿到的是旧脚本（没有单实例锁、没有看门狗）。
**计划**：同步这 3 个文件 + 补 `run-server.sh`/`watchdog.sh`/`log-ts.js`/`signal-log.cjs`/`ensure-policy.sh`，
然后 `python build.py` 重新打包并跑隔离测试。

---

## 7. Codex（openai-codex / gpt-6.1-sol）请求在传输层失败（外部原因）

**现象**（2026-10-30）：`WebSocket error`、`Codex SSE response headers timed out after 300000ms`、
`fetch failed`。每次失败都会中断当前回合（`stopReason=error`，内容为空）。

**判断依据**：会话 jsonl 里 `stopReason=error` + `errorMessage`；pi 的自动重试（默认 3 次）已用掉。
`chatgpt.com` 的 TLS 握手在本机网络下抖动明显（0.22–3.38s）。

**缓解**（改 `~/.pi/agent/settings.json`，未默认启用）：

```json
{
  "transport": "sse",
  "websocketConnectTimeoutMs": 0,
  "httpIdleTimeoutMs": 120000,
  "retry": { "enabled": true, "maxRetries": 5, "baseDelayMs": 2000 }
}
```

---

## 8. 大会话渲染吃内存（未修复）

**现象**：单个会话 13.5 MB，内嵌 12 张 2000×1335 PNG（base64 共 10.5 MB，解码后每张约 10 MB）。
本机可用内存曾低至 1.7 GB、swap 用掉 2.9 GB。

**风险**：WebView 渲染大会话时内存吃紧，可能被系统回收（页面白屏/重载）。

**对策**：长任务用新会话；截图先缩小再让模型读；不要在同一会话里塞十几张全屏截图。

---

## 9. 本机 22 端口到 GitHub 不通（已绕过）

**现象**：`git@github.com:22` 超时（DNS 解析到 `198.18.0.4`，代理 TUN 的 fake-ip）。

**已做**：`~/.ssh/config` 里把 `github.com` 指到 `ssh.github.com:443`，实测可用。

---

## 现场信息怎么取

```bash
tail -50 ~/piweb/server.log      # 带时间戳的服务日志
tail -10 ~/piweb/watchdog.log    # 看门狗动作
tail -10 ~/piweb/launch.log      # 启动/跳过记录
tail -10 ~/piweb/stop.log        # 停止记录（force=0 表示 App 按钮触发）
tail -10 ~/piweb/signals.log     # 服务收到的信号
curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:30141   # 200 = 活着
curl -s 127.0.0.1:30142/health                             # 策略服务
```
