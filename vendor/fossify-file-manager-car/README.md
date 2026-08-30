# Fossify File Manager 车机增强版

本目录保存官网下载版文件管理器的可复现改造，不把文件管理器源码或 APK 合并进 `03桌面`。

## 产品边界

1. `03桌面` 自己负责扫描下载目录和发起系统 APK 安装；文件管理器不是安装 APK 的前置条件。
2. 文件管理器只提供 U 盘浏览增强能力。增强版可用时，`03桌面` 安装页显示“打开 U 盘”并直达当前挂载的可移动卷。
3. 增强版删除所有 `MAIN + LAUNCHER` 入口，只由 `03桌面` 通过公开的显式 Activity 展示和启动。
4. 两个应用保持独立包、独立权限和独立数据；文件管理器仍是可正常卸载的普通第三方应用。
5. 车机适配版保持 `org.fossify.filemanager.debug`，不创建 `03filemanager`，不进入 9.9 Studio 的 commerce、license 或 `com.ninepointnine` 产品身份；Release 证书只代表 9.9 Studio 对该 GPLv3 修改版的分发签名，不是 Fossify 官方证书或官方发布身份。

## 上游与许可

- 上游仓库：`https://github.com/FossifyOrg/File-Manager.git`
- 上游标签：`1.6.1`
- 上游提交：`6879b7871a10057df197b73508835c8772d98e47`
- 上游许可：GNU GPL v3，完整文本见 `LICENSE-GPL-3.0.txt`
- 本地改造：`0001-car-integration.patch`

对外提供修改版 APK 时，官网必须同时提供上述源码版本、补丁和许可证入口，不得只分发二进制文件。

## 车机契约

- Debug 包名：`org.fossify.filemanager.debug`
- 公开 Activity：`org.fossify.filemanager.activities.MainActivity`
- 契约 metadata：`org.fossify.filemanager.CAR_INTEGRATION_VERSION=1`
- U 盘动作：`org.fossify.filemanager.action.OPEN_REMOVABLE_STORAGE`
- 标准浮窗 metadata：`com.tcrrry.icar.window.STANDARD_FLOATING_WINDOW=true`
- 版本：`versionCode=14`，`versionName=1.6.1-car175.1`

U 盘动作由文件管理器自身通过 Android 公开 `StorageManager` 查询已挂载、非主存储、可移动的卷；找不到时提示并回到文件管理器主页。

## 构建

准备好 Android SDK、JDK 17 和网络后执行。脚本优先读取 `ANDROID_SDK_ROOT` / `ANDROID_HOME` 与 `JAVA_HOME`，未设置时复用本项目未提交的 `local.properties` 和 `android-env.local.properties`：

```bash
bash vendor/fossify-file-manager-car/build-core-debug.sh /absolute/path/Fossify-File-Manager-1.6.1-Car-175.1.apk
```

脚本会在系统临时目录克隆固定提交、校验并应用补丁、构建 `coreDebug`，然后仅把 APK 复制到指定路径。Debug 包使用执行机器现有的 Android Debug 证书，只用于开发验证。

正式车机适配版使用仓库外独立长期证书。首次建立新签名材料时执行以下命令，输出目录必须不存在且位于仓库外；现有 production 身份不得重复生成或替换：

```bash
node vendor/fossify-file-manager-car/generate-release-signing.mjs /absolute/path/new-signing-directory
```

使用既有 `signing.properties` 构建 Release：

```bash
bash vendor/fossify-file-manager-car/build-core-release.sh \
  /absolute/path/fossify-file-manager-car-release.apk \
  /absolute/path/signing.properties
```

Release 脚本仍在系统临时目录克隆固定提交、校验补丁并构建 `coreRelease`，不会复制 JKS、口令或 properties 到仓库。补丁显式保留上游 Debug 变体使用的 `.debug` 包后缀，因此 production 车机适配版包名仍为 `org.fossify.filemanager.debug`；Release 构建本身为 `debuggable=false`。

当前已核验 production 产物为 `1.6.1-car175.1 (14)`，证书 SHA-256 为 `be75daa9799eaa4bbe0592a59ce66d3aaef9931d7f7f76ef732907665408a10f`，单 signer、APK Signature Scheme v2 有效且无系统 Launcher 入口。公开提供 APK 时必须同时提供本目录的固定上游提交说明、`0001-car-integration.patch` 和 `LICENSE-GPL-3.0.txt`。
