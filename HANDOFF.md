# HANDOFF — 换对话/换设备时先看这个

最后更新：2026-10-30 14:10（本机 Xiaomi Pad 7 Pro / HyperOS / Termux）

配套文档：`README.md`（结构与构建）、`CHANGELOG.md`（变更记录）、`KNOWN-ISSUES.md`（已知异常）。

## 一句话现状

启动器 APK **3.0-b1 已构建并装在本机**（约 87 MiB，签名与资源哈希校验通过，
7 项隔离安装测试通过）；本机已在用 pi-web（`~/piweb`，端口 30141/30142）。
**未在第二台设备完整验证**（K70 Pro 那台卡在"等待 Termux 安装器回调"）。

## 已完成

- [x] 启动器 APK 1.0 → 1.8（WebView + RUN_COMMAND + 启动动画 + 视口修复）
- [x] 3.0-b1：方案 B（APK 内置 pi-web 资源 + 安装向导 + 接管已有安装）
- [x] 权限三件套：`permission-gate.ts` 扩展 + `policy-server.js` + `permission-policy.json`
- [x] 运行环境加固（2026-10-30）：单实例锁、setsid 独立会话、日志时间戳、看门狗、策略服务自愈

## 已验证 / 未验证

| 项 | 状态 | 依据 |
|---|---|---|
| 本机一键启动 / 接管旧安装 | 已验证 | 装后启动正常 |
| APK 资源包完整性（SHA-256） | 已验证 | 构建时校验 |
| 隔离安装测试（7 项） | 已验证 | `provision/test_provision.py` |
| 新设备全流程 | **未验证** | 第二台设备向导卡在等待回调 |
| 非 ARM64 / 非标准 prefix | **未支持** | 首版边界 |
| 升级路径（新目录部署后接管） | **未验证** | 文档已写明做法 |

## 已知问题（按优先级）

1. **侧栏「重启服务」会被反复点击**：它等于 `stop.sh && launch.sh`，每点一次就打断正在进行的对话。
   2026-10-30 14:01:40 / 14:07:50 各发生一次（`~/piweb/stop.log` 的 `force=0` 记录）。
   现在有 10 秒连点保护，但挡不住隔几分钟再点。**根因是"界面看起来卡住"时用户没别的抓手。**
   待办：给按钮加二次确认（需重编 APK）；或让页面提供"重连/重载"的显眼入口。
2. **`stop.sh` 偶尔耗时 90 秒以上**（14:01:40 → 14:03:17 才返回），期间 `pi-web stop` 在等服务退出。
   待办：给 `pi-web stop` 加超时兜底（超时后直接 `SIGKILL`）。
3. **服务仍会被外部终止**：`~/piweb/signals.log` 记录 SIGTERM 时间，`stop.log` 记录是否是自己点的。
   两者对照即可判定"系统清理"还是"人点的"。
4. **两处代码版本分叉**：`apkbuild/provision/runtime/` 里打包用的 `launch.sh`/`stop.sh`/`policy-server.js`
   仍是加固前的版本；`~/piweb` 是最新的。待办：同步后重新打包 APK（新装才生效）。
5. **没有 CI**：构建/测试都是手工脚本。

## 常用命令

```bash
# 运行
~/piweb/launch.sh ; ~/piweb/stop.sh [--force]
tail -f ~/piweb/server.log          # 服务日志（带时间戳）
tail -5 ~/piweb/signals.log         # 服务收到的信号
tail -5 ~/piweb/watchdog.log        # 看门狗动作

# 构建 APK
cd ~/apkbuild && export PIWEB_KEYSTORE_PASS='<口令>' && python build.py

# 部署（需 Shizuku 在线）
~/apkbuild/deploy.sh
```

## 文件地图

- 服务脚本与文档：`piweb/`（见 `piweb/NOTES-TERMUX.md`）
- APK 源码：`apkbuild/src/com/piweb/launcher/`
- 方案 B 说明：`apkbuild/DEPLOYMENT-B.md`
- pi 的扩展（权限门）：`~/.pi/agent/extensions/permission-gate.ts`（**不在本仓库**，属于 pi 配置）
- 会话与凭据：`~/.pi/agent/`（**绝不可入库**）

## 敏感物清单（永远不要提交）

`apkbuild/piweb.jks`、`~/.pi/agent/auth.json`、`~/.ssh/`、任何 `.apk` 构建产物与日志。
签名口令只通过环境变量 `PIWEB_KEYSTORE_PASS` 传递，不写进文件。
