# Pi Web (agegr/pi-web) — Termux 本机安装说明

安装位置：`~/piweb`（本地 prefix，全局 npm / Pi 本体均未改动）
版本：`@agegr/pi-web@0.12.2`，Next.js 16.3.8，node-pty 1.2.0-beta.15（本机源码编译）
数据：直接复用 `~/.pi/agent`（会话、模型、密钥与 CLI 完全共享）

## 启动 / 停止

```bash
~/piweb/start.sh                                  # 前台启动，仅 127.0.0.1:30141
nohup ~/piweb/start.sh > ~/piweb/server.log 2>&1 & # 后台启动
node ~/piweb/node_modules/@agegr/pi-web/bin/pi-web.js status
node ~/piweb/node_modules/@agegr/pi-web/bin/pi-web.js stop
node ~/piweb/node_modules/@agegr/pi-web/bin/pi-web.js logs   # 若版本支持
```

## 后台守护与日志（2026-10-30 加固）

起因：10-30 13:39 服务被「重启服务」连点打断，界面掉到 `Pi Web is offline`（pi-web 自带
`public/offline.html` 离线页），同时日志里堆了 48 条 `EADDRINUSE`。加固后链路：

| 文件 | 作用 |
|---|---|
| `launch.sh` | **单实例锁**（`flock .launch.lock`）+ **setsid 独立会话** + 启动就绪后写 `.last-start` |
| `run-server.sh` | 被 setsid 调用：真正启动 pi-web，并把输出接给 `log-ts.js` |
| `log-ts.js` | 给每行日志加本地时间戳（管道挂了也不退出，避免连累服务） |
| `signal-log.cjs` | `node --require` 预加载：把收到的 SIGTERM/SIGINT/SIGHUP 记到 `signals.log` |
| `ensure-policy.sh` | 确保 30142 权限策略服务在线（它掉了会让侧栏显示“策略服务已离线”） |
| `watchdog.sh` | 每 5s 探活，连续 2 次失败自动拉起；平时顺带检查策略服务 |
| `stop.sh` | **连点保护**：10s 内刚启动过就跳过停止；`--force` 跳过保护；写入 `.last-stop` 让看门狗 20s 内不插手 |

日志文件：`server.log`（带时间戳）/ `launch.log` / `watchdog.log` / `stop.log` / `policy.log` / `signals.log`。

排查“服务又被谁停了”时看两个时间：`signals.log` 里 pi-web 收到 SIGTERM 的时刻，和
`policy.log` 里 `SIGTERM received` 的时刻。两者几乎同一秒 = 整组进程被一起杀（系统清理）；
只有 pi-web 有记录 = 某处执行了 `stop.sh` / `pi-web stop`。

判断服务是否“在跑”不要只看 HTTP：`launch.sh` 里 `is_up()` 会退回 TCP 探测，
因为服务忙时 HTTP 可能几秒不响应，但端口有人监听就说明它活着，**不能**再起一个。

## 升级

```bash
node ~/piweb/node_modules/@agegr/pi-web/bin/pi-web.js stop
cd ~/piweb
npm install @agegr/pi-web@latest --ignore-scripts --no-audit --no-fund
~/piweb/rebuild-node-pty.sh        # 必须：重新编译 Android 版 node-pty
~/piweb/start.sh
```

## 为什么必须 --ignore-scripts + 手动 rebuild

1. `process.platform` 在 Termux 上是 `"android"`，node-pty 只发布
   `prebuilds/{linux,darwin,win32}-*`，没有 `android-arm64`，其 install
   脚本回退到 `node-gyp rebuild` 并失败，导致整个 npm 安装中止。
   → `--ignore-scripts` 跳过该脚本，其余包正常安装。
2. 编译时 node 的 `common.gypi` 在 `OS=="android"` 下引用
   `android_ndk_path` 变量，必须通过环境变量注入：
   `GYP_DEFINES="android_ndk_path=$PREFIX"`（脚本内已处理）。
3. 系统依赖（已安装）：`clang`、`make`、`python`、`ncurses-ui-libs`。
   Termux 软件源已从 mirrors.sau.edu.cn 切换到官方
   `packages-cf.termux.dev`（旧源备份：`$PREFIX/etc/apt/sources.list.bak.20261026-225842`）。

## 桌面一键启动图标

已准备好的文件：

- `~/piweb/launch.sh` —— 确保服务器在跑（没跑就后台启动并等待端口就绪），然后调用
  `termux-open-url` 打开 `http://127.0.0.1:30141`；重复点击不会产生第二个实例。
- `~/.shortcuts/pi-web.sh` —— 桌面图标入口（Termux:Widget 快捷方式，调用 launch.sh）。
- `~/.shortcuts/pi-web-stop.sh` —— 可选：一键停止服务。

### 方式 A：Termux:Widget（真正「一键启动 + 打开」）

1. 从与 Termux 相同的来源安装 **Termux:Widget**（F-Droid / GitHub Releases），
   签名需与 Termux 本体一致。
2. Termux 设置里允许外部应用执行命令：
   `echo 'allow-external-apps = true' >> ~/.termux/termux.properties`
   （安全含义：持有 `com.termux.permission.RUN_COMMAND` 的应用可以执行命令；
   撤销：删除该行并 `termux-reload-settings`。）
3. 桌面长按 → 小部件/Widgets → Termux:Widget → 选择 `pi-web`。
4. HyperOS 需给 Termux 开启「自启动」并把电池策略设为「无限制」，否则后台服务会被杀。

### 方式 B：PWA（无需安装任何插件）

浏览器打开 `http://127.0.0.1:30141` → 菜单 → 「安装应用 / 添加到主屏幕」。
Pi Web 自带 manifest 与 service worker，图标体验接近原生 App；
但服务器需已在运行（可配合方式 A 的第一个脚本或手动 `~/piweb/start.sh`）。

## 自建启动器 APK（推荐，已在设备上安装）

`com.piweb.launcher` v1.0，源码与构建产物在 `~/apkbuild/`：

- 点图标 → 先 ping `127.0.0.1:30141`；未运行则通过 Termux 的 RUN_COMMAND
  执行 `~/piweb/launch.sh`（不弹外部浏览器）→ 轮询就绪 → WebView 打开界面。
- `BootReceiver`：开机后请求 Termux 后台拉起服务（HyperOS 需给「Pi Web」应用开启自启动）。
- 声明并已获授 `com.termux.permission.RUN_COMMAND`（安装时 `pm install -g` 预授权），
  因此**不依赖** Termux 插件签名。

重新构建：

```bash
cd ~/apkbuild
aapt package -f -M AndroidManifest.xml -S res -I android.jar -F base.apk
javac -source 8 -target 8 -nowarn -bootclasspath android.jar -d obj $(find src -name '*.java')
d8 --lib android.jar --min-api 24 --output dexdir $(find obj -name '*.class')
(cd dexdir && zip -q -j ../base.apk classes.dex)
apksigner sign --ks piweb.jks --ks-pass pass:$PIWEB_KEYSTORE_PASS \
  --key-pass pass:$PIWEB_KEYSTORE_PASS --min-sdk-version 24 --out PiWebLauncher.apk base.apk
```

卸载：`pm uninstall com.piweb.launcher`（或长按图标卸载）。
若提示超时/无法调用 Termux：重连 Shizuku 后执行
`pm grant com.piweb.launcher com.termux.permission.RUN_COMMAND`。

## 故障排查

- 终端标签报 `Cannot load the node-pty native terminal module for android-arm64`
  → 执行 `~/piweb/rebuild-node-pty.sh`，然后重启 pi-web。
- 端口被占用：`~/piweb/start.sh --port 30142`
- 截图/日志：`~/piweb/server.log`（2026-10-30 起带时间戳）
- 界面显示 `Pi Web is offline`：这是 Service Worker 的离线页，说明**那一刻服务不在**，
  先 `curl -s -o /dev/null -w '%{http_code}' 127.0.0.1:30141`，再决定要不要“重载”。
  **不要连点「重启服务」**——它等于“先杀再起”，连点会互相踩（已有连点保护，但别依赖它）。
- 侧栏一直显示“策略服务已离线”：只是 30142 没起来，不影响对话；
  执行 `~/piweb/ensure-policy.sh` 即可，看门狗每 30s 也会补一次。
- 服务又无故停了：看 `~/piweb/watchdog.log`、`signals.log`、`policy.log` 的时间线。

## 安全

- 默认仅监听 `127.0.0.1`，不对外开放；如需局域网访问必须先设
  `PI_WEB_PASSWORD` 强密码并使用 `-H 0.0.0.0`（自行承担风险）。

## 变更记录（启动器 APK）

| 版本 | 改动 | 原因 |
|---|---|---|
| 1.0 | 初版：WebView + RUN_COMMAND | — |
| 1.1 | 失败原因立即显示；startServer 移到 onResume | onCreate 时 Activity 未前台，后台起服务被拒 |
| 1.2 | 运行时申请 `com.termux.permission.RUN_COMMAND`；加 `<queries>` | 该权限 prot=dangerous，系统只在主动申请时弹窗 |
| 1.3 | 服务 5.6s 未响应则唤醒 Termux 并重发命令 | Termux 被杀后 RUN_COMMAND 被自启动限制拦截 |
| 1.4 | 唤醒后 moveTaskToFront 抢回前台 | 唤醒 Termux 会把界面顶掉 |
| 1.5 | 每 350ms 检测被压后台并抢回，持续 ~16s | Termux 冷启动约 4s，早期抢回无效 |
| 1.6 | 判定阈值 5.6s→2.5s，轮询 700ms→350ms，重发 1.2/3/5/8s | 冷启动 12s→6s |
| 1.7 | 全新启动页（Canvas 绘制标志 + 流动渐变进度条 + 淡出） | 原文字倒数计时观感差 |
| 1.8 | **关闭 `setUseWideViewPort` / `setLoadWithOverviewMode`**，WebView 获取焦点，启动页淡出后从布局移除 | 平板桌面视口导致页面滚动容器失效（手指滑不动） |

部署（ADB 级 Shizuku，无需 root/无需手动点安装器）：

```bash
~/apkbuild/deploy.sh
```

注意：降权到 shell(2000) 后 `pm` 需要可访问的 cwd，否则报
`filesystem error: in current_path: call to pathconf failed`；`~/shizuku/r.sh` 已统一先 `cd /data/local/tmp`。

## 权限控制（权限等级 + 批准模式）

组件三件套：

| 文件 | 作用 |
|---|---|
| `~/.pi/agent/extensions/permission-gate.ts` | pi 扩展：拦截 `tool_call`，按策略阻断/弹确认；注册 `/perm` 命令 |
| `~/piweb/policy-server.js` | 仅监听 `127.0.0.1:30142`，读写策略文件供启动器 App 使用 |
| `~/.pi/agent/permission-policy.json` | 策略本体：`{ "level": "app\|adb\|root", "mode": "ask\|auto\|full" }` |

启动器 APK 右侧原生栏：权限（普通/ADB/root）× 批准（请求/自动/完全）。**切到 root 必须通过指纹验证**。

判定规则：

- `level=app`：`rish`、`pm`、`logcat` 等 ADB 级调用一律阻断
- `level=adb`：允许 ADB 级，root 级（`r-root.sh`）仍阻断
- `level=root`：允许全部，需指纹解锁后才会写入策略
- `mode=ask`：所有写操作弹确认；`auto`：仅危险命令（`rm -rf`、`dd`、`mkfs`、`/system`、`/data/adb`）与提权调用弹确认；`full`：不弹确认（等级限制仍生效）

命令行等价操作：

```bash
curl -s -X POST http://127.0.0.1:30142/policy \
  -H 'content-type: application/json' -d '{"level":"adb","mode":"ask"}'
curl -s http://127.0.0.1:30142/policy
```

在 Pi Web 对话框里也可用：`/perm`、`/perm adb auto`、`/perm root ask`。
