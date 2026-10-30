# Pi Web on Android（个人自用）

在 **Android + Termux** 上把 [pi-web](https://github.com/agegr/pi-web)（pi 编码助手的 Web 界面）
跑成本机服务，并提供一个原生启动器 APK：点图标 → 自检服务 → 没跑就拉起 → WebView 打开界面。

> 这是一个**个人自用**的集成项目，不是通用产品：所有脚本按"单机、仅回环地址、Termux UID"设计。
> 不内置任何模型密钥，不开放公网端口。

## 仓库结构

```
apkbuild/            启动器 APK 的源码与构建脚本
  src/com/piweb/launcher/   Java 源码（Activity / WebView / RUN_COMMAND / 安装向导）
  res/, AndroidManifest.xml 资源与清单
  provision/         方案 B：把 pi-web 及其依赖打包进 APK 的安装器（Python）
  build.py           构建 + 签名（口令从环境变量 PIWEB_KEYSTORE_PASS 读取）
  DEPLOYMENT-B.md    方案 B 的范围、边界与故障恢复说明
piweb/               本机运行环境（服务脚本 + 文档）
  launch.sh          单实例锁 + setsid 启动 + 拉起策略服务/看门狗
  run-server.sh      被 setsid 调用，接 log-ts.js 输出带时间戳日志
  stop.sh            停止服务（10 秒连点保护；--force 跳过）
  watchdog.sh        每 5 秒探活，连续 2 次失败自动拉起
  ensure-policy.sh   确保 30142 权限策略服务在线
  policy-server.js   权限策略读写接口（仅 127.0.0.1:30142）
  log-ts.js          给日志加本地时间戳的管道
  signal-log.cjs     记录服务收到的 SIGTERM/SIGINT 等信号
  NOTES-TERMUX.md    安装、权限、启动器变更记录、故障排查
shizuku/             通过 Shizuku(ADB 级) 部署 APK 的辅助脚本
```

## 运行环境

- Android 12+（本机 HyperOS），ARM64，Termux（F-Droid / GitHub 版）
- Node.js ≥ 22.19（Termux 官方源）
- 依赖：`curl`、`python`、`clang/make`（仅在需要重编 node-pty 时）

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

需要自行准备：`android.jar`（Android SDK platform）、`aapt/javac/d8/apksigner`（Termux: `pkg install aapt` 等）。
`assets/` 下的资源包由 `provision/pack-resources.py` 生成，不入库。

## 设计要点（踩过的坑）

1. **服务必须单实例**：早期没有锁，连点"重启服务"会并发启动多个实例，端口抢占刷一屏 `EADDRINUSE`。
2. **服务要脱离终端会话**：用 `setsid` 启动，避免随 Termux 命令行会话一起被清理。
3. **存活判定不能只看 HTTP**：服务忙时 HTTP 可能几秒不响应，但端口活着就不能再起一个（`launch.sh` 里退回 TCP 探测）。
4. **日志必须带时间**：没有时间戳时，崩溃只能靠会话文件反推时间。
5. **"Pi Web is offline" 是 Service Worker 的离线页**，说明那一刻服务真不在，不是模型问题。

## 不入库的东西

`piweb.jks`（签名密钥）、`auth.json`（模型凭据）、`.apk` 构建产物、`node_modules`、
`assets/` 资源包、运行日志与锁文件。见各级 `.gitignore`。

## 许可

个人项目，未附许可协议。第三方组件（pi-web、Next.js 等）遵循其各自许可。
