#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_ANDROID_PROPERTIES="$PROJECT_ROOT/local.properties"
DEBUG_APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
EXPECTED_PACKAGE="com.ninepointnine.desktop.test"
EXPECTED_COMPONENT="$EXPECTED_PACKAGE/com.ninepointnine.desktop.MainActivity"
EXPECTED_SERVICE="$EXPECTED_PACKAGE/com.ninepointnine.desktop.overlay.OverlayService"
INSTALL_ONLY=false
SERIAL_ARG=""

usage() {
    cat >&2 <<'EOF'
用法：ANDROID_SERIAL=<serial> ./scripts/install-debug-to-device.sh [--install-only]
      ./scripts/install-debug-to-device.sh --serial <serial> [--install-only]
--install-only 仅覆盖安装、授予白名单 AppOp 并只读核对包身份、Launcher 和更新时间，不启动应用。
未指定 --serial 或 ANDROID_SERIAL 时拒绝执行，避免误操作默认 ADB 设备。
EOF
}

while (($# > 0)); do
    case "$1" in
        --install-only)
            INSTALL_ONLY=true
            ;;
        --serial)
            if [[ -z "${2:-}" || "${2:-}" == --* ]]; then
                echo "--serial 需要非空设备 serial。" >&2
                usage
                exit 2
            fi
            SERIAL_ARG="$2"
            shift
            ;;
        --serial=*)
            SERIAL_ARG="${1#--serial=}"
            if [[ -z "$SERIAL_ARG" ]]; then
                echo "--serial= 需要非空设备 serial。" >&2
                usage
                exit 2
            fi
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "未知参数：$1" >&2
            usage
            exit 2
            ;;
    esac
    shift
done

ENV_SERIAL="${ANDROID_SERIAL:-}"
if [[ -n "$SERIAL_ARG" && -n "$ENV_SERIAL" && "$SERIAL_ARG" != "$ENV_SERIAL" ]]; then
    echo "--serial 与 ANDROID_SERIAL 不一致，拒绝执行。" >&2
    exit 2
fi
ADB_SERIAL="${SERIAL_ARG:-$ENV_SERIAL}"
if [[ -z "$ADB_SERIAL" ]]; then
    echo "必须显式提供 --serial <serial> 或 ANDROID_SERIAL，拒绝使用默认 ADB 设备。" >&2
    usage
    exit 2
fi

read_property() {
    local file_path="$1"
    local property_name="$2"
    sed -n "s/^${property_name}=//p" "$file_path" | tail -n 1
}

ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_DIR" && -f "$LOCAL_ANDROID_PROPERTIES" ]]; then
    ANDROID_SDK_DIR="$(read_property "$LOCAL_ANDROID_PROPERTIES" "sdk.dir")"
fi

ANDROID_ADB_BIN="${ANDROID_ADB:-$ANDROID_SDK_DIR/platform-tools/adb}"
ANDROID_AAPT_BIN="$ANDROID_SDK_DIR/build-tools/34.0.0/aapt"
ADB=("$ANDROID_ADB_BIN" -s "$ADB_SERIAL")

if [[ ! -x "$ANDROID_ADB_BIN" || ! -x "$ANDROID_AAPT_BIN" ]]; then
    echo "未找到可执行 Android Platform Tools 或 Build Tools。" >&2
    exit 1
fi

if [[ ! -f "$DEBUG_APK" ]]; then
    echo "未找到 Debug APK；请先完成 assembleDebug。" >&2
    exit 1
fi

APK_PACKAGE="$("$ANDROID_AAPT_BIN" dump badging "$DEBUG_APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
if [[ "$APK_PACKAGE" != "$EXPECTED_PACKAGE" ]]; then
    echo "Debug APK 包名不匹配：$APK_PACKAGE" >&2
    exit 1
fi

if [[ "$("${ADB[@]}" get-state 2>/dev/null || true)" != "device" ]]; then
    echo "目标 serial 未处于可用连接状态：$ADB_SERIAL" >&2
    exit 1
fi

DEVICE_MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
DEVICE_SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
DEVICE_SIZE="$("${ADB[@]}" shell wm size | tr -d '\r')"

if [[ "$DEVICE_SDK" != "28" ]] || {
    [[ "$DEVICE_SIZE" != *"1920x1080"* && "$DEVICE_SIZE" != *"2560x1440"* ]]
}; then
    echo "设备基线不匹配：model=$DEVICE_MODEL sdk=$DEVICE_SDK size=$DEVICE_SIZE；支持 Android 9 与 1920x1080/2560x1440 显示画布" >&2
    exit 1
fi

echo "目标车机：$DEVICE_MODEL / Android SDK $DEVICE_SDK / $DEVICE_SIZE / serial $ADB_SERIAL"
echo "安装包：$DEBUG_APK"
"${ADB[@]}" install -r -g "$DEBUG_APK"
"${ADB[@]}" shell appops set "$EXPECTED_PACKAGE" REQUEST_INSTALL_PACKAGES allow
INSTALL_APP_OP="$("${ADB[@]}" shell appops get "$EXPECTED_PACKAGE" REQUEST_INSTALL_PACKAGES | tr -d '\r')"
if [[ "$INSTALL_APP_OP" != *"REQUEST_INSTALL_PACKAGES: allow"* ]]; then
    echo "Debug APK 已安装，但未知来源安装授权未生效。" >&2
    exit 1
fi

if [[ "$INSTALL_ONLY" == true ]]; then
    if ! "${ADB[@]}" shell pm list packages "$EXPECTED_PACKAGE" | tr -d '\r' | grep -Fxq "package:$EXPECTED_PACKAGE"; then
        echo "pm 未确认目标包身份：$EXPECTED_PACKAGE" >&2
        exit 1
    fi
    PACKAGE_PATH="$("${ADB[@]}" shell pm path "$EXPECTED_PACKAGE" | tr -d '\r' | head -n 1)"
    if [[ "$PACKAGE_PATH" != package:* ]]; then
        echo "pm 未返回目标包 APK 路径：$PACKAGE_PATH" >&2
        exit 1
    fi
    LAUNCHER_COMPONENT="$("${ADB[@]}" shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$EXPECTED_PACKAGE" |
        tr -d '\r' | tail -n 1)"
    if [[ "$LAUNCHER_COMPONENT" != "$EXPECTED_COMPONENT" ]]; then
        echo "Launcher 身份不匹配：$LAUNCHER_COMPONENT" >&2
        exit 1
    fi
    LAST_UPDATE_TIME="$("${ADB[@]}" shell dumpsys package "$EXPECTED_PACKAGE" |
        tr -d '\r' | sed -n 's/^[[:space:]]*lastUpdateTime=//p' | head -n 1)"
    if [[ -z "$LAST_UPDATE_TIME" ]]; then
        echo "pm 未返回目标包 lastUpdateTime。" >&2
        exit 1
    fi
    echo "pm 包路径：$PACKAGE_PATH"
    echo "Launcher：$LAUNCHER_COMPONENT"
    echo "覆盖时间：$LAST_UPDATE_TIME"
    echo "Debug APK 已覆盖安装；install-only 未启动应用。"
    exit 0
fi

"${ADB[@]}" shell am start -n "$EXPECTED_COMPONENT"

PROCESS_ID=""
SERVICE_STATE=""
for _ in {1..30}; do
    PROCESS_ID="$("${ADB[@]}" shell pidof "$EXPECTED_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
    SERVICE_STATE="$("${ADB[@]}" shell dumpsys activity services "$EXPECTED_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$PROCESS_ID" && "$SERVICE_STATE" == *"$EXPECTED_SERVICE"* ]]; then
        break
    fi
    sleep 0.5
done

if [[ -z "$PROCESS_ID" ]]; then
    echo "应用启动后未检测到进程：$EXPECTED_PACKAGE" >&2
    exit 1
fi

if [[ "$SERVICE_STATE" != *"$EXPECTED_SERVICE"* ]]; then
    echo "应用已安装并启动，但悬浮服务未运行；请检查悬浮权限。" >&2
    exit 1
fi

LAST_UPDATE_TIME="$("${ADB[@]}" shell dumpsys package "$EXPECTED_PACKAGE" | tr -d '\r' | sed -n 's/^[[:space:]]*lastUpdateTime=//p' | head -n 1)"
echo "应用进程：$PROCESS_ID"
echo "悬浮服务：运行中"
echo "覆盖时间：$LAST_UPDATE_TIME"
echo "Debug APK 已覆盖安装并启动。"
