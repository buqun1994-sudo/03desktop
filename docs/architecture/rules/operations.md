# 运维规则

## 1. 默认规则

1. local、test、staging、production 必须明确区分，不能用测试通过冒充生产可用。
2. 部署和上线必须支持可追溯入口、可观测输出和回滚说明。
3. 能 dry-run 的操作不直接真实写入。
4. production 操作必须人工确认。

## 2. 03桌面项目规则

1. 触发条件：进入仓库开发。动作：先运行 `./scripts/check-android-env.sh`，需要车机时追加 `--device`。验证：JDK 17、SDK 34、Build Tools 34.0.0、ADB 和设备基线通过。边界：SDK/JDK/ADB 与缓存不复制进仓库。
2. 触发条件：任何需要连接目标车机的 ADB 操作。动作：每次先用首选 ADB 快速枚举现有设备；未发现已验证车机时，立即对当前局域网做一次有界、短超时的 TCP 候选枚举，只有 IP 的候选默认补端口 `5555`，历史 serial 只作提示而非在线真值。候选连接后核对 `device` 状态、`S56_HQX` 和 SDK 28。验证：本轮选定的显式 serial 身份匹配。边界：只有现状枚举与候选连接均失败才报告离线；禁止高频或无界扫描，也禁止同时反复启动多套 ADB Server。
3. 触发条件：完成可在目标车机手测的应用行为改动。动作：构建通过后执行 `./scripts/install-debug-to-device.sh`，脚本只允许向型号、SDK 和分辨率均匹配的车机覆盖安装仓库固定 Debug APK，再非阻塞启动 `com.ninepointnine.desktop.test/com.ninepointnine.desktop.MainActivity`，并在有限时间内轮询进程与悬浮服务。验证：安装成功、目标包更新时间更新、进程和前台悬浮服务存在，脚本能明确成功或超时退出。边界：测试应用 ID 与源码类命名空间必须分开解析；入口 Activity 可能立即结束，不得用无界界面启动等待代替运行状态检查；不清数据、不授予权限、不重启设备，不接受自定义包名或任意 APK 路径。
4. 触发条件：安装其它 APK、重启、清数据、授权或改车机设置。动作：作为运行级验证显式记录目标、动作和恢复方式，并取得本轮授权。验证：操作前后状态对比。边界：环境检查脚本永远只读；本项目自身 Debug 覆盖安装不重复请求授权。
5. 触发条件：准备 release。动作：先确认独立签名、版本递增、构建、升级安装和回滚 APK。验证：真机覆盖升级与签名核对。边界：当前没有部署/上线自动化，未经指令不生成或分发正式版本。
6. 触发条件：准备测试或 Release 版本。动作：所有变体读取仓库根 `release-version.properties`；未指定版本时运行 `node scripts/bump-release-version.mjs` 递增 patch 并同步递增 `releaseVersionCode`，有明确版本时传入 `--version`。Debug/staging 只追加 `applicationIdSuffix=".test"` 和 `versionNameSuffix="-test"`，不创建第二套版本文件。验证：`node scripts/bump-release-version.mjs --check`、按目标变体核对 APK 包名、版本和签名。边界：不把版本递增等同于部署、上线或车机安装。
