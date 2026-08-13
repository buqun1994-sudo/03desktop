#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROBE_PACKAGE=""

if [[ "${1:-}" == "--package" && -n "${2:-}" && -z "${3:-}" ]]; then
    PROBE_PACKAGE="$2"
else
    echo "用法：./scripts/check-device-capabilities.sh --package <普通第三方应用包名>" >&2
    exit 2
fi

read_property() {
    local file_path="$1"
    local property_name="$2"
    sed -n "s/^${property_name}=//p" "$file_path" | tail -n 1
}

ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_DIR" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_SDK_DIR="$(read_property "$PROJECT_ROOT/local.properties" "sdk.dir")"
fi

ANDROID_ADB_BIN="${ANDROID_ADB:-$ANDROID_SDK_DIR/platform-tools/adb}"
if [[ ! -x "$ANDROID_ADB_BIN" ]]; then
    echo "未找到可执行 ADB。" >&2
    exit 1
fi

if [[ "$($ANDROID_ADB_BIN get-state 2>/dev/null || true)" != "device" ]]; then
    echo "车机未处于可用连接状态。" >&2
    exit 1
fi

resolve_activity() {
    "$ANDROID_ADB_BIN" shell cmd package resolve-activity --brief "$@" | tr -d '\r' | tail -n 1
}

if ! "$ANDROID_ADB_BIN" shell pm list packages -3 | tr -d '\r' | grep -Fxq "package:$PROBE_PACKAGE"; then
    echo "探测包不是当前已安装的普通第三方应用：$PROBE_PACKAGE" >&2
    exit 1
fi

LAUNCH_COMPONENT="$(resolve_activity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PROBE_PACKAGE")"
UNINSTALL_COMPONENT="$(resolve_activity -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PROBE_PACKAGE")"
INSTALL_COMPONENT="$(resolve_activity -a android.intent.action.VIEW -t application/vnd.android.package-archive -d file:///sdcard/Download/capability-probe.apk)"
UNKNOWN_SOURCE_COMPONENT="$(resolve_activity -a android.settings.MANAGE_UNKNOWN_APP_SOURCES -d package:com.tcrrry.desktop)"
VOLUMES="$($ANDROID_ADB_BIN shell sm list-volumes all | tr -d '\r')"

for component in "$LAUNCH_COMPONENT" "$UNINSTALL_COMPONENT" "$INSTALL_COMPONENT" "$UNKNOWN_SOURCE_COMPONENT"; do
    if [[ "$component" != */* ]]; then
        echo "系统入口解析失败：$component" >&2
        exit 1
    fi
done

if [[ "$VOLUMES" != *"public:"*" mounted "* ]]; then
    echo "未检测到已挂载的公共外部存储卷。" >&2
    exit 1
fi

echo "第三方应用启动入口：$LAUNCH_COMPONENT"
echo "系统应用信息入口：$UNINSTALL_COMPONENT"
echo "系统 APK 安装入口：$INSTALL_COMPONENT"
echo "未知来源授权入口：$UNKNOWN_SOURCE_COMPONENT"
echo "外部存储：检测到已挂载公共卷"
echo "车机能力只读检查通过；未启动、安装、卸载、授权或修改设置。"
