# Fossify File Manager 车机增强版

本目录保存官网下载版文件管理器的可复现改造，不把文件管理器源码或 APK 合并进 `03桌面`。

## 产品边界

1. `03桌面` 自己负责扫描下载目录和发起系统 APK 安装；文件管理器不是安装 APK 的前置条件。
2. 文件管理器只提供 U 盘浏览增强能力。增强版可用时，`03桌面` 安装页显示“打开 U 盘”并直达当前挂载的可移动卷。
3. 增强版删除所有 `MAIN + LAUNCHER` 入口，只由 `03桌面` 通过公开的显式 Activity 展示和启动。
4. 两个应用保持独立包、独立权限和独立数据；文件管理器仍是可正常卸载的普通第三方应用。

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

脚本会在系统临时目录克隆固定提交、校验并应用补丁、构建 `coreDebug`，然后仅把 APK 复制到指定路径。Debug 包使用执行机器现有的 Android Debug 证书；官网正式发布前仍需单独建立并备份专用 Release 签名，不能把 Debug 包当正式发布包。
