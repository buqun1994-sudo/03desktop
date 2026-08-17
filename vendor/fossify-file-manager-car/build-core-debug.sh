#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 /absolute/path/output.apk" >&2
    exit 2
fi

OUTPUT_APK="$1"
if [[ "$OUTPUT_APK" != /* ]]; then
    echo "Output path must be absolute." >&2
    exit 2
fi

OUTPUT_DIR="$(dirname "$OUTPUT_APK")"
if [[ ! -d "$OUTPUT_DIR" ]]; then
    echo "Output directory does not exist: $OUTPUT_DIR" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOCAL_ANDROID_ENV="$PROJECT_ROOT/android-env.local.properties"
LOCAL_SDK_PROPERTIES="$PROJECT_ROOT/local.properties"
CAR_JAVA_HOME="${JAVA_HOME:-}"
CAR_ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [[ -z "$CAR_JAVA_HOME" && -f "$LOCAL_ANDROID_ENV" ]]; then
    CAR_JAVA_HOME="$(sed -n 's/^java.home=//p' "$LOCAL_ANDROID_ENV" | tail -n 1)"
fi

if [[ -z "$CAR_JAVA_HOME" || ! -x "$CAR_JAVA_HOME/bin/java" ]]; then
    echo "JDK 17 was not found. Set JAVA_HOME or java.home in android-env.local.properties." >&2
    exit 1
fi

if [[ -z "$CAR_ANDROID_SDK" && -f "$LOCAL_SDK_PROPERTIES" ]]; then
    CAR_ANDROID_SDK="$(sed -n 's/^sdk.dir=//p' "$LOCAL_SDK_PROPERTIES" | tail -n 1)"
fi

if [[ -z "$CAR_ANDROID_SDK" || ! -d "$CAR_ANDROID_SDK" ]]; then
    echo "Android SDK was not found. Set ANDROID_SDK_ROOT, ANDROID_HOME, or sdk.dir in local.properties." >&2
    exit 1
fi

BUILD_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/fossify-car.XXXXXX")"
SOURCE_DIR="$BUILD_ROOT/File-Manager"
cleanup() {
    rm -rf -- "$BUILD_ROOT"
}
trap cleanup EXIT

git clone --quiet https://github.com/FossifyOrg/File-Manager.git "$SOURCE_DIR"
git -C "$SOURCE_DIR" checkout --quiet --detach 6879b7871a10057df197b73508835c8772d98e47
git -C "$SOURCE_DIR" apply --check "$SCRIPT_DIR/0001-car-integration.patch"
git -C "$SOURCE_DIR" apply "$SCRIPT_DIR/0001-car-integration.patch"

env JAVA_HOME="$CAR_JAVA_HOME" \
    ANDROID_HOME="$CAR_ANDROID_SDK" \
    ANDROID_SDK_ROOT="$CAR_ANDROID_SDK" \
    "$SOURCE_DIR/gradlew" --project-dir "$SOURCE_DIR" :app:assembleCoreDebug

SOURCE_APK="$SOURCE_DIR/app/build/outputs/apk/core/debug/file-manager-14-core-debug.apk"
if [[ ! -f "$SOURCE_APK" ]]; then
    echo "Expected APK was not produced: $SOURCE_APK" >&2
    exit 1
fi

install -m 0644 "$SOURCE_APK" "$OUTPUT_APK"
shasum -a 256 "$OUTPUT_APK"
