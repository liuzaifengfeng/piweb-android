# Pi Web 方案 B（3.0-b1）

## 范围

APK 内置 Pi Web 0.12.2、依赖、Android ARM64 node-pty、启动脚本和扩展。仍需单独安装官方 com.termux 并首次打开完成基础初始化；不是内置 Termux 或全离线安装器，缺失的 Node 等基础依赖需从 Termux 官方源联网安装。

首版仅支持 ARM64、标准 Termux 前缀，APK minSdk 为 24。Android 16 本机验证不能替代干净设备和其他 Android 版本的验证。

## 新设备操作

1. 从 Termux 官方 GitHub 或 F-Droid 安装 Termux，首次打开并等待基础初始化完成。
2. 安装 PiWebLauncher.apk，进入安装向导。
3. 点击「查看并复制初始化命令」，检查后在 Termux 执行。它备份 termux.properties，仅调整 allow-external-apps，并安装 curl/python；不换源或全量升级。
4. 返回 APK，授权「在 Termux 中执行命令」。命令使用 Termux 应用 UID，不是 ADB/root。
5. 点击检查目录。已有安装可接管：只保存 App 启动参数，不改原文件。旧版仅支持 ~/piweb 和默认端口 30141/30142。
6. 新安装选择一个不存在的家目录子目录和两个未占用端口，确认联网安装缺失依赖。进度在向导中显示，后台传输使用用户主动启动的前台服务。
7. 成功后打开 Pi Web，在网页模型设置中填写自己的供应商和凭据。APK 不携带个人模型密钥。
8. 如系统回收后台进程，可自行允许两款应用后台运行；安装器不设置白名单、自启动或提权通道。

首次手动配置不能省略：APK 无权直接写 Termux 私有文件，外部命令接口也必须先由用户启用。

## 资源与传输

assets/provision/ 下包含 manifest.json、bootstrap.sh、provision.py、piweb.tar.gz。清单记录版本、架构、解压体积、各资源 SHA-256。

资源包只从锁文件对应的依赖目录、审核后的脚本和扩展快照构建；排除私人目录、会话、认证文件、日志、Next 运行缓存及无关编译产物，保留包内许可证。

Java 临时 HTTP 服务仅监听 127.0.0.1，使用随机端口、256-bit 一次性令牌、固定文件白名单、请求大小限制和 30 分钟有效期；不提供任意文件读取或命令执行 API。任务结束后关闭，无开机启动。

大资源通过 HTTP 流式传输，不塞进 Binder 或命令参数。APK 固定安装脚本和清单哈希，验证通过才执行或解压。本机其他应用也可能访问回环端口，所以必须有令牌；哈希不能代替可信 APK 签名。

## 现有安装和故障恢复

- 目标目录存在就拒绝安装，不递归删除、不覆盖原环境。
- 已有 settings.json、auth.json、同名扩展和权限策略文件均保留。策略不存在时才创建 app/ask。
- deployment.json 是新托管安装的 Termux 侧配置；App 从经鉴权的安装结果保存副本，接管时重新读取。不能只改 App 端口就假装服务已修改。
- 首版不原地替换版本。升级采用新目录、新端口部署，验证后接管；旧目录保留。
- 安装工作目录位于 HOME/.piweb-provision-*，包含下载与安装日志。失败时保留，便于诊断。成功后目前也保留资源缓存，不自动批量清理。
- 日志明确给出的具体工作目录，确认无用后再手动处理，不要通配删除。
- 提交目录后启动失败，目录保留并提示自检未通过；检查该目录 server.log/policy.log 后可接管重试启动，或选择新目录。
- 取消在阶段检查点生效，不强杀正在运行的包管理器，也不回滚已安装系统依赖。
- 系统回收安装服务后不自动重启安装命令，向导会提示检查中断状态。
- 只自动接受 packages.termux.dev / packages-cf.termux.dev 官方源；其他源需手动审查和安装依赖后重试，不擅自换源。
- 已有 Node 太旧时只报错，不擅自升级。

## 原生模块兼容

内置 PTY 产物来自当前 ARM64 Termux 环境，不能承诺所有 Node/Android 版本通用。安装时真实启动 Shell 测试；失败就停止提交，不默认安装编译器。

需要本机编译时，先明确授权安装编译工具，再参照现有 rebuild-node-pty.sh。首版没有向导内一键编译恢复，不能把这个分支描述为已自动实现。

## 权限与隐私

新托管安装的策略 API 使用随机 bearer token，存于 deployment.json 和 App 私有配置；不要公开部署配置。health 端点仅供探活。

权限插件仍是同 UID 工作流限制，不是系统安全沙箱。Termux UID 可以修改策略文件，命令正则也不能阻断所有间接执行。新安装不携带提权执行通道，侧栏不直接启用 ADB/root。接管旧版不会改变其权限机制，也不会自动给旧接口补鉴权。

不内置私人指令、模型认证、提权工具、签名私钥或密码。开机启动默认关闭。Pi Web 只绑定回环地址，但不等于与其他本机应用完全隔离。

## 构建与测试

已有 aapt、JDK、d8、apksigner、Python 和可信的固定版本依赖时：

```sh
cd ~/apkbuild
# 在 Bash 中交互输入签名密码，不写入命令历史
read -r -s PIWEB_KEYSTORE_PASS
export PIWEB_KEYSTORE_PASS
python build.py
unset PIWEB_KEYSTORE_PASS
```

pack-resources.py 校验包版本与锁文件一致，但不重新从上游验证每个已安装文件的完整性。重新发布前必须使用可信依赖，并验证 Android 原生模块。

构建会复用校验通过的资源包，避免每次修改 Java 都重新压缩约 400 MB 依赖。修改锁文件、安装脚本或运行脚本时自动失效；依赖文件本身有意变更时使用 python build.py --refresh-payload 强制刷新。重新打包采用快速压缩并输出分段进度。

构建使用独立暂存目录，任一步失败立即退出。关键 class、DEX、签名和 APK 内资源哈希都通过后才替换最终产物。资源由 ZIP 白名单写入，保留压缩包的精确文件名与内容，避免旧版 AAPT 对 gz 的特殊处理；中断留下的 tmp 文件不会装进 APK。

```sh
python provision/test_provision.py
PIWEB_FULL_TEST=1 python provision/test_provision.py
```

完整测试使用独立临时 HOME 和随机端口，真实运行测试服务，仅停止测试 PID；不使用个人模型凭据、不调用模型、不停止当前 Pi Web。

跨设备待验收：干净 Termux 首装、缺依赖联网安装、拒绝授权、安装中断恢复、断网/空间不足、后台及旋转、冷/热启动、模型手动配置。未测试的项目不得标记为通过。
