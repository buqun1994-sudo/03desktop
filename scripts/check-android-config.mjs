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
  "app/src/main/java/com/tcrrry/desktop/MainActivity.kt",
  "docs/plans/V1架构施工方案.md",
  "docs/operations/开发环境.md",
  "docs/testing/车机能力验证记录.md",
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
const systemActionLauncher = read(
  "app/src/main/java/com/tcrrry/desktop/system/SystemActionLauncher.kt",
);
const stateText = read(".new-project-template/state.json");

for (const expected of [
  'namespace = "com.tcrrry.desktop"',
  'applicationId = "com.tcrrry.desktop"',
  "minSdk = 28",
  "targetSdk = 28",
  "compileSdk = 34",
  'jvmTarget = "17"',
  'disable += "ExpiredTargetSdkVersion"',
]) {
  if (!build.includes(expected)) failures.push(`Android 配置缺少：${expected}`);
}

const allowedPermissions = new Set([
  "android.permission.FOREGROUND_SERVICE",
  "android.permission.READ_EXTERNAL_STORAGE",
  "android.permission.RECEIVE_BOOT_COMPLETED",
  "android.permission.REQUEST_INSTALL_PACKAGES",
  "android.permission.SYSTEM_ALERT_WINDOW",
]);

for (const match of manifest.matchAll(/<uses-permission\s+android:name="([^"]+)"/g)) {
  if (!allowedPermissions.has(match[1])) failures.push(`Manifest 出现未批准权限：${match[1]}`);
}

if (!manifest.includes("com.tcrrry.icar.surface.action.ACQUIRE_OCCUPANCY_LEASE")) {
  failures.push("Manifest 缺少桌面表面占用协议的 Service 查询声明");
}

const standardWindowMetadataCount = (
  manifest.match(/com\.tcrrry\.icar\.window\.STANDARD_FLOATING_WINDOW/g) ?? []
).length;
if (standardWindowMetadataCount < 2) {
  failures.push("Manifest 未完整声明 03桌面自身标准浮窗 Activity");
}

if (!systemActionLauncher.includes("standardFloatingWindowCoordinator.launchExclusive")) {
  failures.push("抽屉顶层系统窗口未接入标准浮窗互斥协调器");
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
  if (source.includes("com.tcrrry.desktoplyrics")) {
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

for (const localFile of ["local.properties", "android-env.local.properties", "keystore.properties"]) {
  const ignored = spawnSync("git", ["check-ignore", "-q", localFile], { cwd: root });
  if (ignored.status !== 0) failures.push(`本机文件未被 Git 忽略：${localFile}`);
}

if (failures.length > 0) {
  console.error("Android 项目配置检查失败：");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Android 项目配置检查通过。");
