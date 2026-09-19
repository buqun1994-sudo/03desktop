# 运维规则

## 1. 默认规则

1. local、test、staging、production 必须明确区分，不能用测试通过冒充生产可用。
2. 部署和上线必须支持可追溯入口、可观测输出和回滚说明。
3. 能 dry-run 的操作不直接真实写入。
4. production 操作必须人工确认。

## 2. 03桌面项目规则

1. 触发条件：进入仓库开发。动作：先运行 `./scripts/check-android-env.sh`，需要车机时追加 `--device`。验证：JDK 17、SDK 34、Build Tools 34.0.0、ADB 和设备基线通过。边界：SDK/JDK/ADB 与缓存不复制进仓库。
2. 触发条件：任何需要连接目标车机的电脑端 ADB 操作。动作：无条件先运行唯一入口 `node scripts/find-vehicle-adb.mjs --serial-only`；入口先复用已连接目标，否则排除 VPN / 虚拟网卡后，对物理私有 IPv4 网段执行 TCP `5555` 并发探测（每网段最多 `/24`、总候选最多 `512`、并发上限 `254`、单地址 `350ms`），只对端口命中项执行 ADB 握手。验证：入口返回且核对为 `device / S56_HQX / SDK 28` 的唯一 serial。边界：历史地址不是在线真值；入口失败前禁止人工看旧 IP、路由、ARP、逐地址连接或报告离线，失败后才允许诊断；禁止并行 ADB Server 和大于 `/24` 的无界扫描。
3. 触发条件：完成可在目标车机手测的应用行为改动。动作：构建通过后执行 `./scripts/install-debug-to-device.sh`，脚本只允许向型号、SDK 和分辨率均匹配的车机覆盖安装仓库固定 Debug APK，再非阻塞启动 `com.ninepointnine.desktop.test/com.ninepointnine.desktop.MainActivity`，并在有限时间内轮询进程与悬浮服务。验证：安装成功、目标包更新时间更新、进程和前台悬浮服务存在，脚本能明确成功或超时退出。边界：测试应用 ID 与源码类命名空间必须分开解析；入口 Activity 可能立即结束，不得用无界界面启动等待代替运行状态检查；不清数据、不授予权限、不重启设备，不接受自定义包名或任意 APK 路径。
4. 触发条件：安装其它 APK、重启、清数据、授权或改车机设置。动作：作为运行级验证显式记录目标、动作和恢复方式，并取得本轮授权。验证：操作前后状态对比。边界：环境检查脚本永远只读；本项目自身 Debug 覆盖安装不重复请求授权。
5. 触发条件：准备 release。动作：先确认独立签名、版本递增、构建、升级安装和回滚 APK。验证：真机覆盖升级与签名核对。边界：当前没有部署/上线自动化，未经指令不生成或分发正式版本。
6. 触发条件：准备测试或 Release 版本。动作：所有变体读取仓库根 `release-version.properties`；未指定版本时运行 `node scripts/bump-release-version.mjs` 递增 patch 并同步递增 `releaseVersionCode`，有明确版本时传入 `--version`。Debug/staging 只追加 `applicationIdSuffix=".test"` 和 `versionNameSuffix="-test"`，不创建第二套版本文件。验证：`node scripts/bump-release-version.mjs --check`、按目标变体核对 APK 包名、版本和签名。边界：不把版本递增等同于部署、上线或车机安装。
