#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK_DEVICE=false

if [[ "${1:-}" == "--device" ]]; then
    CHECK_DEVICE=true
elif [[ -n "${1:-}" ]]; then
    echo "用法：./scripts/check-android-env.sh [--device]" >&2
    exit 2
fi

read_property() {
    local file_path="$1"
    local property_name="$2"
    sed -n "s/^${property_name}=//p" "$file_path" | tail -n 1
}

LOCAL_ANDROID_ENV="$PROJECT_ROOT/android-env.local.properties"
LOCAL_ANDROID_PROPERTIES="$PROJECT_ROOT/local.properties"

ANDROID_JAVA_HOME="${JAVA_HOME:-}"
if [[ -z "$ANDROID_JAVA_HOME" && -f "$LOCAL_ANDROID_ENV" ]]; then
    ANDROID_JAVA_HOME="$(read_property "$LOCAL_ANDROID_ENV" "java.home")"
fi

if [[ -z "$ANDROID_JAVA_HOME" || ! -x "$ANDROID_JAVA_HOME/bin/java" ]]; then
    echo "未找到 JDK 17。请设置 JAVA_HOME 或 android-env.local.properties 的 java.home。" >&2
    exit 1
fi

JAVA_VERSION="$($ANDROID_JAVA_HOME/bin/java -version 2>&1 | head -n 1)"
if [[ "$JAVA_VERSION" != *'17.'* ]]; then
    echo "需要 JDK 17，当前为：$JAVA_VERSION" >&2
    exit 1
fi

ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_DIR" && -f "$LOCAL_ANDROID_PROPERTIES" ]]; then
    ANDROID_SDK_DIR="$(read_property "$LOCAL_ANDROID_PROPERTIES" "sdk.dir")"
fi

if [[ -z "$ANDROID_SDK_DIR" || ! -d "$ANDROID_SDK_DIR" ]]; then
    echo "未找到 Android SDK。请设置 ANDROID_SDK_ROOT 或 local.properties 的 sdk.dir。" >&2
    exit 1
fi

REQUIRED_PATHS=(
    "$ANDROID_SDK_DIR/platforms/android-34/android.jar"
    "$ANDROID_SDK_DIR/build-tools/34.0.0/aapt2"
    "$ANDROID_SDK_DIR/platform-tools/adb"
)

for required_path in "${REQUIRED_PATHS[@]}"; do
    if [[ ! -e "$required_path" ]]; then
        echo "缺少 Android 工具链文件：$required_path" >&2
        exit 1
    fi
done

echo "JDK：$JAVA_VERSION"
echo "Android SDK：android-34 / Build Tools 34.0.0"
echo "ADB：$($ANDROID_SDK_DIR/platform-tools/adb version | head -n 1)"

if [[ "$CHECK_DEVICE" == true ]]; then
    ANDROID_ADB_BIN="${ANDROID_ADB:-$ANDROID_SDK_DIR/platform-tools/adb}"
    if [[ ! -x "$ANDROID_ADB_BIN" ]]; then
        echo "ADB 不可执行：$ANDROID_ADB_BIN" >&2
        exit 1
    fi

    DEVICE_STATE="$($ANDROID_ADB_BIN get-state 2>/dev/null || true)"
    if [[ "$DEVICE_STATE" != "device" ]]; then
        echo "车机未处于可用连接状态。" >&2
        exit 1
    fi

    DEVICE_MODEL="$($ANDROID_ADB_BIN shell getprop ro.product.model | tr -d '\r')"
    DEVICE_SDK="$($ANDROID_ADB_BIN shell getprop ro.build.version.sdk | tr -d '\r')"
    DEVICE_SIZE="$($ANDROID_ADB_BIN shell wm size | tr -d '\r')"

    if [[ "$DEVICE_MODEL" != "S56_HQX" || "$DEVICE_SDK" != "28" || "$DEVICE_SIZE" != *"1920x1080"* ]]; then
        echo "设备基线不匹配：model=$DEVICE_MODEL sdk=$DEVICE_SDK size=$DEVICE_SIZE" >&2
        exit 1
    fi

    echo "车机：$DEVICE_MODEL / Android SDK $DEVICE_SDK / 1920x1080"
fi

echo "Android 开发环境检查通过。"
