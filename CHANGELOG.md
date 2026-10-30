# 变更记录

两套版本号：
- **启动器 APK**：`versionCode` / `versionName`（见 `apkbuild/AndroidManifest.xml`）
- **运行脚本**：`piweb/` 下的 shell/node 脚本，用日期标记

---

## 启动器 APK

### 3.0-b1（versionCode 18，2026-10-30）

方案 B：把 pi-web 及其依赖打进 APK，安装向导负责落地。

- 新增安装向导（`SetupActivity`）：检查 Termux、复制初始化命令、检查目录、全新安装 / 接管已有安装
- 新增 `provision/`：`bootstrap.sh`、`provision.py`、`pack-resources.py`、`runtime/`（要落地的脚本与扩展）
- 资源包（约 87 MiB）含 pi-web 0.12.2、依赖、Android ARM64 node-pty、启动脚本、pi 扩展
- 大资源通过临时 HTTP 服务（回环 + 随机端口 + 256-bit 一次性令牌 + 白名单 + 30 分钟有效期）流式传给 Termux
- 校验：包内每个资源记 SHA-256，不匹配不执行/不解压
- 接管旧安装只保存启动参数，不改动原目录与 `settings.json`/`auth.json`
- 7 项隔离安装测试通过（`provision/test_provision.py`）
- 首次安装仍需人工：装 Termux、在 Termux 里允许外部命令（`allow-external-apps`）

### 1.8（2026-10-27）

- 关闭 `setUseWideViewPort` / `setLoadWithOverviewMode`，WebView 获取焦点，启动页淡出后从布局移除
- 修复：平板桌面视口下页面滚动容器失效（手指滑不动）

### 1.7（2026-10-27）

- 全新启动页：Canvas 绘制标志 + 流动渐变进度条 + 淡出

### 1.6（2026-10-27）

- 启动判定阈值 5.6s → 2.5s，轮询 700ms → 350ms，重发改为 1.2/3/5/8s
- 冷启动耗时 12s → 6s

### 1.5（2026-10-27）

- 每 350ms 检测被压后台并抢回前台，持续约 16s

### 1.4（2026-10-27）

- 唤醒 Termux 后 `moveTaskToFront` 抢回前台

### 1.3（2026-10-27）

- 服务 5.6s 未响应则唤醒 Termux 并重发启动命令

### 1.2（2026-10-27）

- 运行时申请 `com.termux.permission.RUN_COMMAND`，清单加 `<queries>`

### 1.1（2026-10-26）

- 失败原因立即显示；`startServer` 移到 `onResume`（`onCreate` 时 Activity 未前台，后台起服务会被拒）

### 1.0（2026-10-26）

- 初版：WebView + Termux RUN_COMMAND 拉起服务

---

## 运行脚本（`piweb/`）

### 2026-10-30 运行环境加固

起因：13:39 服务被「重启服务」连点打断，页面掉进 `Pi Web is offline`，日志堆了 48 条 `EADDRINUSE`。

- `launch.sh`：新增 `flock` 单实例锁；`setsid` 独立会话；就绪后写 `.last-start`
- `run-server.sh`（新）：被 setsid 调用，负责真正启动并把输出接给 `log-ts.js`
- `log-ts.js`（新）：给每行日志加本地时间戳；管道断开不退出
- `signal-log.cjs`（新）：`node --require` 预加载，记录 SIGTERM/SIGINT/SIGHUP 的时间与 pid/ppid
- `ensure-policy.sh`（新）：确保 30142 权限策略服务在线
- `watchdog.sh`（新）：每 5s 探活，连续 2 次失败自动拉起；每 30s 顺带检查策略服务
- `stop.sh`：新增 10 秒连点保护（`--force` 跳过）、写 `.last-stop` 让看门狗 20s 内不插手
- `policy-server.js`：日志加时间戳；收到 SIGTERM/SIGINT 时记录
- `launch.sh` 的存活判定改为 HTTP + TCP 双重探测：服务忙时不再误判成"没启动"

### 2026-10-27 权限控制

- 三件套：`permission-gate.ts`（pi 扩展）+ `policy-server.js` + `permission-policy.json`
- 策略：等级 `app|adb|root` × 批准 `ask|auto|full`；切 root 需指纹验证

### 2026-10-26 首次可用

- pi-web 0.12.2 + Next.js 16.3.8 落地到 `~/piweb`，node-pty 源码编译（Android ARM64）
- `launch.sh` / `stop.sh` / `start.sh` / `rebuild-node-pty.sh` 建立
