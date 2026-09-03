#!/usr/bin/env node

import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];

const requiredFiles = [
  "settings.gradle.kts",
  "build.gradle.kts",
  "gradle.properties",
  "gradlew",
  "gradlew.bat",
  "gradle/wrapper/gradle-wrapper.jar",
  "gradle/wrapper/gradle-wrapper.properties",
  "local.properties.example",
  "android-env.example.properties",
  "keystore.properties.example",
  "app/build.gradle.kts",
  "app/src/main/AndroidManifest.xml",
  "app/src/main/java/com/ninepointnine/desktop/MainActivity.kt",
  "app/src/main/java/com/ninepointnine/desktop/debug/NavigationDemoAccessibilityService.kt",
  "app/src/main/java/com/ninepointnine/desktop/install/ApkInstallActivity.kt",
  "app/src/main/java/com/ninepointnine/desktop/install/ApkScanner.kt",
  "app/src/main/java/com/ninepointnine/desktop/system/FileManagerContract.kt",
  "app/src/main/res/xml/global_back_accessibility_service.xml",
  "docs/plans/03desktop包名与签名迁移方案.md",
  "docs/plans/V1架构施工方案.md",
  "docs/operations/开发环境.md",
  "docs/testing/车机能力验证记录.md",
  "vendor/fossify-file-manager-car/0001-car-integration.patch",
  "vendor/fossify-file-manager-car/LICENSE-GPL-3.0.txt",
  "vendor/fossify-file-manager-car/README.md",
  "vendor/fossify-file-manager-car/build-core-debug.sh",
  "vendor/fossify-file-manager-car/build-core-release.sh",
  "vendor/fossify-file-manager-car/generate-release-signing.mjs",
];

for (const file of requiredFiles) {
  if (!existsSync(join(root, file))) failures.push(`缺少文件：${file}`);
}

function read(relativePath) {
  const target = join(root, relativePath);
  return existsSync(target) ? readFileSync(target, "utf8") : "";
}

const build = read("app/build.gradle.kts");
const manifest = read("app/src/main/AndroidManifest.xml");
const debugManifest = read("app/src/debug/AndroidManifest.xml");
const systemActionLauncher = read(
  "app/src/main/java/com/ninepointnine/desktop/system/SystemActionLauncher.kt",
);
const mainActivity = read("app/src/main/java/com/ninepointnine/desktop/MainActivity.kt");
const appEntry = read("app/src/main/java/com/ninepointnine/desktop/model/AppEntry.kt");
const appCatalogRepository = read(
  "app/src/main/java/com/ninepointnine/desktop/apps/AppCatalogRepository.kt",
);
const fileManagerContract = read(
  "app/src/main/java/com/ninepointnine/desktop/system/FileManagerContract.kt",
);
const apkInstallActivity = read(
  "app/src/main/java/com/ninepointnine/desktop/install/ApkInstallActivity.kt",
);
const apkInstallLayout = read("app/src/main/res/layout/activity_apk_install.xml");
const debugInstallScript = read("scripts/install-debug-to-device.sh");
const globalBackService = read(
  "app/src/main/java/com/ninepointnine/desktop/debug/NavigationDemoAccessibilityService.kt",
);
const globalBackConfig = read("app/src/main/res/xml/global_back_accessibility_service.xml");
const fossifyPatch = read("vendor/fossify-file-manager-car/0001-car-integration.patch");
const fossifyReleaseBuild = read("vendor/fossify-file-manager-car/build-core-release.sh");
const fossifyReleaseSigning = read("vendor/fossify-file-manager-car/generate-release-signing.mjs");
const stateText = read(".new-project-template/state.json");

for (const expected of [
  'namespace = "com.ninepointnine.desktop"',
  'applicationId = "com.ninepointnine.desktop"',
  'applicationIdSuffix = ".test"',
  'versionNameSuffix = "-test"',
  "minSdk = 28",
  "targetSdk = 28",
  "compileSdk = 34",
  'jvmTarget = "17"',
  'disable += "ExpiredTargetSdkVersion"',
]) {
  if (!build.includes(expected)) failures.push(`Android 配置缺少：${expected}`);
}

for (const expected of [
  "desktopSigningEnvironment",
  "desktopStagingSigningPropertiesFile",
  "desktopProductionSigningPropertiesFile",
  'create("staging")',
  'create("release")',
  'name == "preReleaseBuild"',
]) {
  if (!build.includes(expected)) failures.push(`Android 签名配置缺少：${expected}`);
}
if (build.includes('rootProject.file("keystore.properties")')) {
  failures.push("Android 签名仍读取仓库根 keystore.properties");
}

const allowedPermissions = new Set([
  "android.permission.FOREGROUND_SERVICE",
  "android.permission.RECEIVE_BOOT_COMPLETED",
  "android.permission.READ_EXTERNAL_STORAGE",
  "android.permission.REQUEST_INSTALL_PACKAGES",
  "android.permission.SYSTEM_ALERT_WINDOW",
]);

for (const match of manifest.matchAll(/<uses-permission\s+android:name="([^"]+)"/g)) {
  if (!allowedPermissions.has(match[1])) failures.push(`Manifest 出现未批准权限：${match[1]}`);
}

if (!manifest.includes("com.tcrrry.icar.surface.action.ACQUIRE_OCCUPANCY_LEASE")) {
  failures.push("Manifest 缺少桌面表面占用协议的 Service 查询声明");
}

if (!manifest.includes('<package android:name="org.fossify.filemanager.debug"')) {
  failures.push("Manifest 缺少增强文件管理器的包可见性声明");
}
if (!manifest.includes('android:theme="@android:style/Theme.NoDisplay"')) {
  failures.push("MainActivity 未使用无界面启动主题");
}
if (mainActivity.includes("ACTION_MANAGE_OVERLAY_PERMISSION")) {
  failures.push("MainActivity 仍会主动打开悬浮权限页面");
}
if (!manifest.includes("ApkInstallActivity") || !manifest.includes("FileProvider")) {
  failures.push("Manifest 缺少内置 Download APK 安装链");
}
if (!apkInstallActivity.includes("READ_EXTERNAL_STORAGE") ||
    !apkInstallActivity.includes("canRequestPackageInstalls()") ||
    !apkInstallActivity.includes("launchExternalStorage")) {
  failures.push("内置 APK 页面未保持 Download、系统安装器与可选 U 盘入口边界");
}
if (apkInstallActivity.includes("requestPermissions(") ||
    apkInstallActivity.includes("ACTION_MANAGE_UNKNOWN_APP_SOURCES") ||
    apkInstallLayout.includes("request_download_permission") ||
    !debugInstallScript.includes('install -r -g "$DEBUG_APK"') ||
    !debugInstallScript.includes("appops set \"$EXPECTED_PACKAGE\" REQUEST_INSTALL_PACKAGES allow")) {
  failures.push("APK 所需权限未统一由安装流程授予，页面仍保留重复授权入口");
}
if (!manifest.includes('android:name=".debug.NavigationDemoAccessibilityService"') ||
    !manifest.includes('android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"') ||
    !manifest.includes('android:resource="@xml/global_back_accessibility_service"')) {
  failures.push("正式 Manifest 未完整声明全局返回无障碍服务");
}
if (debugManifest.includes("NavigationDemoAccessibilityService")) {
  failures.push("全局返回服务仍只由 Debug Manifest 声明");
}
if (!globalBackService.includes("performGlobalAction(GLOBAL_ACTION_BACK)")) {
  failures.push("全局返回服务未绑定系统返回动作");
}
for (const forbidden of ["GLOBAL_ACTION_HOME", "GLOBAL_ACTION_RECENTS", "TYPE_ACCESSIBILITY_OVERLAY"]) {
  if (globalBackService.includes(forbidden)) {
    failures.push(`全局返回服务越过单一返回边界：${forbidden}`);
  }
}
for (const required of [
  'android:canPerformGestures="false"',
  'android:canRequestFilterKeyEvents="false"',
  'android:canRetrieveWindowContent="false"',
]) {
  if (!globalBackConfig.includes(required)) failures.push(`全局返回隐私配置缺少：${required}`);
}
if (!fileManagerContract.includes('PACKAGE_NAME = "org.fossify.filemanager.debug"') ||
    !fileManagerContract.includes("CAR_INTEGRATION_VERSION_METADATA") ||
    !fileManagerContract.includes("OPEN_REMOVABLE_STORAGE_ACTION") ||
    !fileManagerContract.includes("FLAG_ACTIVITY_CLEAR_TASK")) {
  failures.push("缺少增强文件管理器显式契约");
}
if (appEntry.includes("FILE_MANAGER_PACKAGE_NAME")) {
  failures.push("应用卸载策略仍把可选文件管理器设为保护项");
}
if (!appCatalogRepository.includes("fileManagerCandidate(packageManager, deviceProfile)") ||
    !appCatalogRepository.includes("FileManagerContract.mainComponent")) {
  failures.push("应用目录未显式合成无 Launcher 的增强文件管理器入口");
}
if (!fossifyPatch.includes("org.fossify.filemanager.CAR_INTEGRATION_VERSION") ||
    !fossifyPatch.includes("org.fossify.filemanager.action.OPEN_REMOVABLE_STORAGE") ||
    !fossifyPatch.includes("StorageManager") ||
    !fossifyPatch.includes('release {\n+            applicationIdSuffix = ".debug"') ||
    !fossifyPatch.includes('-                <category android:name="android.intent.category.LAUNCHER" />') ||
    /^\+.*android\.intent\.category\.LAUNCHER/m.test(fossifyPatch)) {
  failures.push("Fossify 补丁未保持显式 U 盘契约或无系统 Launcher 入口边界");
}
if (!fossifyReleaseBuild.includes("6879b7871a10057df197b73508835c8772d98e47") ||
    !fossifyReleaseBuild.includes(":app:assembleCoreRelease") ||
    !fossifyReleaseBuild.includes("SIGNING_STORE_FILE") ||
    !fossifyReleaseBuild.includes("git -C \"$SOURCE_DIR\" apply --check")) {
  failures.push("Fossify Release 构建未固定上游、补丁或仓库外签名注入");
}
if (!fossifyReleaseSigning.includes("Signing material output must stay outside the repository.") ||
    !fossifyReleaseSigning.includes('"-keyalg", "RSA"') ||
    !fossifyReleaseSigning.includes('"-keysize", "4096"') ||
    !fossifyReleaseSigning.includes("Third-Party Open Source Adaptation")) {
  failures.push("Fossify Release 签名生成器未保持仓库外第三方适配身份边界");
}

if (!systemActionLauncher.includes("standardFloatingWindowCoordinator.launchExclusive")) {
  failures.push("抽屉顶层系统窗口未接入标准浮窗互斥协调器");
}
if (!systemActionLauncher.includes("fun launchApkInstaller()") ||
    !systemActionLauncher.includes("fun launchExternalStorage(") ||
    !systemActionLauncher.includes("FileManagerContract.createMainIntent()")) {
  failures.push("SystemActionLauncher 缺少安装页或增强 U 盘启动入口");
}
if (systemActionLauncher.includes("context.startActivity(")) {
  failures.push("SystemActionLauncher 绕过了标准浮窗互斥协调器");
}

function collectFiles(directory) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const target = join(directory, entry.name);
    return entry.isDirectory() ? collectFiles(target) : [target];
  });
}

for (const file of collectFiles(join(root, "app/src/main"))) {
  if (!/\.(kt|kts|xml|pro)$/.test(file)) continue;
  const source = readFileSync(file, "utf8");
  if (source.includes("com.tcrrry.desktoplyrics") ||
      source.includes("com.ninepointnine.desktoplyrics")) {
    failures.push(`产品源码引用了 03歌词 身份：${file.slice(root.length + 1)}`);
  }
  if (/Settings\.Secure\.put[A-Za-z]*\s*\(/.test(source)) {
    failures.push(`产品源码写入 Settings.Secure：${file.slice(root.length + 1)}`);
  }
}

try {
  const state = JSON.parse(stateText);
  if (state.state !== "initialized" || state.projectName !== "03桌面") {
    failures.push("项目初始化状态与 03桌面 身份不一致");
  }
} catch {
  failures.push("项目初始化状态不是合法 JSON");
}

for (const localFile of [
  "local.properties",
  "android-env.local.properties",
  "keystore.properties",
  "signing.properties",
]) {
  const ignored = spawnSync("git", ["check-ignore", "-q", localFile], { cwd: root });
  if (ignored.status !== 0) failures.push(`本机文件未被 Git 忽略：${localFile}`);
}

if (failures.length > 0) {
  console.error("Android 项目配置检查失败：");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Android 项目配置检查通过。");
