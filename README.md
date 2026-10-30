# Pi Web on Android（个人自用）

在 **Android + Termux** 上把 [pi-web](https://github.com/agegr/pi-web)（pi 编码助手的 Web 界面）
跑成本机服务，并提供一个原生启动器 APK：点图标 → 自检服务 → 没跑就拉起 → WebView 打开界面。

> 这是一个**个人自用**的集成项目，不是通用产品：所有脚本按"单机、仅回环地址、Termux UID"设计。
> 不内置任何模型密钥，不开放公网端口。

## 文档导航

| 文件 | 内容 |
|---|---|
| `HANDOFF.md` | 当前进度：已完成 / 已验证 vs 未验证 / 待办 |
| `CHANGELOG.md` | 变更记录（APK 各版本 + 运行脚本改动） |
| `KNOWN-ISSUES.md` | 已知异常：现象、证据、处理状态、现场信息怎么取 |
| `apkbuild/DEPLOYMENT-B.md` | 方案 B（APK 内置 pi-web 资源）的范围、边界、故障恢复 |
| `piweb/NOTES-TERMUX.md` | 本机安装、权限三件套、启动器变更、故障排查 |

## 仓库结构

```
apkbuild/            启动器 APK 的源码与构建脚本
  src/com/piweb/launcher/   Java 源码（Activity / WebView / RUN_COMMAND / 安装向导）
  res/, AndroidManifest.xml 资源与清单
  provision/         方案 B：把 pi-web 及其依赖打包进 APK 的安装器（Python）
  build.py           构建 + 签名（口令从环境变量 PIWEB_KEYSTORE_PASS 读取）
piweb/               本机运行环境（服务脚本 + 文档）
  launch.sh          单实例锁 + setsid 启动 + 拉起策略服务/看门狗
  run-server.sh      被 setsid 调用，接 log-ts.js 输出带时间戳日志
  stop.sh            停止服务（10 秒连点保护；--force 跳过）
  watchdog.sh        每 5 秒探活，连续 2 次失败自动拉起
  ensure-policy.sh   确保 30142 权限策略服务在线
  policy-server.js   权限策略读写接口（仅 127.0.0.1:30142）
  log-ts.js          给日志加本地时间戳的管道
  signal-log.cjs     记录服务收到的 SIGTERM/SIGINT 等信号
shizuku/             通过 Shizuku(ADB 级) 部署 APK 的辅助脚本
```

## 下载与依赖

### 本项目的产物

| 产物 | 位置 |
|---|---|
| 启动器 APK `3.0-b1`（约 87 MiB） | [Release v3.0-b1](https://github.com/liuzaifengfeng/piweb-android/releases/tag/v3.0-b1) · [直接下载](https://github.com/liuzaifengfeng/piweb-android/releases/download/v3.0-b1/PiWebLauncher-3.0-b1.apk) · [SHA256](https://github.com/liuzaifengfeng/piweb-android/releases/download/v3.0-b1/PiWebLauncher-3.0-b1.apk.sha256)；本地构建产物在 `~/apkbuild/PiWebLauncher.apk` |
| SHA-256 | `30ee2301fba9cc035a676c1bf89be6a016aba45e8f0ab92db2774ab587f7bdfb` |

APK 不入库（体积原因），只作为 Release 附件发布。
已实测：从 Release 重新下载后的 SHA-256 与上表一致。

### 必须自己安装的依赖

| 组件 | 下载 | 说明 |
|---|---|---|
| **Termux 本体** | [F-Droid](https://f-droid.org/packages/com.termux/) 或 [GitHub Releases](https://github.com/termux/termux-app/releases) | **两处签名不同，不能互相覆盖安装**，二选一。本项目在 Termux 0.118.x 验证，要求 Android 7+ |
| Node.js ≥ 22.19 | Termux 内 `pkg install nodejs` | 用官方源，不要混第三方源 |
| curl / python | Termux 内 `pkg install curl python` | 安装向导与运行脚本依赖 |
| clang / make / python | `pkg install clang make python` | 仅在需要重编 Android 版 node-pty 时 |
| Termux:Widget（可选） | [F-Droid](https://f-droid.org/packages/com.termux.widget/) | 桌面快捷方式；**签名必须与 Termux 一致**才可用 |
| Termux:API（可选） | [F-Droid](https://f-droid.org/packages/com.termux.api/) | 本项目只用 `termux-tools` 自带的 `termux-open-url` / `termux-wake-lock`，通常**不需要**额外安装 |

### 新设备安装顺序

1. 装 Termux（上面两个来源二选一），打开并等基础初始化完成
2. 装本项目 APK，进入安装向导
3. 点「查看并复制初始化命令」，到 Termux 里执行（只备份并修改 `allow-external-apps`，不换源、不全量升级）
4. 回 App 授权「在 Termux 中运行命令」
5. 点「检查目录」：已有安装可接管（只存启动参数，不改原文件）；新安装需选一个不存在的目录和两个空闲端口
6. 成功后打开界面，在模型设置里填自己的供应商与凭据（APK 不含任何密钥）
7. 想后台常驻/开机自启，需自行给 Termux 与本品开启自启动、关闭电池限制

## 使用（已部署好的机器）

```bash
~/piweb/launch.sh          # 确保服务在 127.0.0.1:30141，会顺带拉起看门狗
~/piweb/stop.sh            # 停止（保留 wakelock）
~/piweb/stop.sh --force    # 强制停止，跳过连点保护
tail -f ~/piweb/server.log # 日志（每行带时间戳）
```

或直接点桌面上的「Pi Web」图标（启动器 APK）。

## 从源码构建 APK

```bash
cd ~/apkbuild
export PIWEB_KEYSTORE_PASS='<你的签名口令>'
python build.py            # 产出 PiWebLauncher.apk（签名 + 资源哈希校验）
```

需自行准备：`android.jar`（Android SDK platform）、`aapt` / `javac` / `d8` / `apksigner`
（Termux 里可通过 `pkg` 安装对应工具）。`assets/` 下的资源包由
`provision/pack-resources.py` 生成，不入库。

## 设计要点（踩过的坑）

1. **服务必须单实例**：早期没有锁，连点"重启服务"会并发启动多个实例，端口抢占刷一屏 `EADDRINUSE`。
2. **服务要脱离终端会话**：用 `setsid` 启动，避免随 Termux 命令行会话一起被清理。
3. **存活判定不能只看 HTTP**：服务忙时 HTTP 可能几秒不响应，但端口活着就不能再起一个
   （`launch.sh` 里会退回 TCP 探测）。
4. **日志必须带时间**：没有时间戳时，崩溃只能靠会话文件反推时间。
5. **`Pi Web is offline` 是 Service Worker 的离线页**，说明那一刻服务真的不在，不是模型问题。

## 不入库的东西

`piweb.jks`（签名密钥）、`auth.json`（模型凭据）、`.apk` 构建产物、`node_modules`、
`assets/` 资源包、`android.jar`、运行日志与锁文件。见各级 `.gitignore`。

## 许可

个人项目，未附许可协议。第三方组件（pi-web、Next.js、Termux 等）遵循其各自许可。
