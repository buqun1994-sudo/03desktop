# 决策 0001：Android 技术栈与应用身份

## 1. 状态

1. 日期：2026-08-13。
2. 状态：已采纳；2026-08-20 按用户确认将 Android 身份迁移到 9.9 Studio 命名空间并建立双环境签名。

## 2. 决策

1. 使用单 `app` 模块、Kotlin、Android XML Views，不使用 Compose。
2. 使用 Android Gradle Plugin `8.2.2`、Kotlin `1.9.22`、Gradle `8.2`、Java 17。
3. 使用 `compileSdk=34`、`minSdk=28`、`targetSdk=28`，唯一目标设备为 Android 9 `S56_HQX`。
4. `applicationId` 与 `namespace` 固定为 `com.ninepointnine.desktop`。
5. staging 与 production 分别使用 `03桌面` 独立证书；证书不进入仓库。升级版本保持包名与对应环境证书不变，`versionCode` 单调递增。
6. Android Lint 只禁用 `ExpiredTargetSdkVersion`：该规则检查 Google Play 当前上架目标，而本项目是固定 Android 9 车机的侧载客户端；其它 Lint 错误和警告继续参与构建。

## 3. 理由

1. V1 是窗口、触摸、PackageManager、存储与系统 Intent 密集型本地客户端，Android 原生 API 是最短且可审计的主链。
2. XML Views 对 Android 9、轻量内存预算、拖拽网格和 WindowManager 宿主更直接，不需要为单一固定分辨率引入 Compose 运行时和兼容面。
3. `compileSdk=34` 复用已安装平台；`targetSdk=28` 保持 Android 9 上包可见性、存储和前台服务行为与唯一交付系统一致。
4. 独立包名和签名保证它不是 `03歌词` 的模块，也避免安装、升级和卸载身份相互影响。

## 4. 取舍与升级门禁

1. `targetSdk=28` 只适用于当前封闭设备范围；如果未来面向 Android 10+ 或应用商店分发，必须先升级目标 SDK，再验证包可见性、分区存储、通知、前台服务和后台启动限制。
2. 当前不引入依赖注入、数据库、图片框架或多模块；只有当重复边界真实出现且能减少复杂度时才新增抽象。
3. staging 与 production 证书已经创建；后续替换、吊销、导出、分发或写入云端密钥系统仍属于人工门禁。
4. 如果项目未来进入 Google Play、其它现代 Android 设备或 target SDK 升级范围，必须删除 `ExpiredTargetSdkVersion` 抑制并完成对应平台回归，不能把它当作永久通用豁免。

## 5. 小规模网页侧载签名策略

1. 当前倾向通过自有网页向少量固定用户提供 APK，不进入传统应用商店；分发渠道不改变 Android 的签名规则，每个 APK 仍必须签名，后续覆盖更新必须保持包名与签名证书完全一致。
2. Debug APK 使用开发机 Debug 证书，适合当前开发验证，但证书可能随机器或环境更换，且包保持 debuggable；因此对外网页下载仍采用独立 Release 证书，不因用户量少而继续把 Debug 身份当长期发布身份。
3. 历史包 `com.tcrrry.desktop` 不能被新包覆盖；新包需要独立安装并重新授权。进入 production 身份后，所有网页更新只使用同一 production 证书并递增 `versionCode`。
4. 已生成并核验 staging / production 签名 APK；尚未安装、分发或上线。当前两份备份仍位于本机待转移目录，正式分发前必须分别转移到独立离线介质。
