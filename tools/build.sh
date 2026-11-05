#!/usr/bin/env bash
# 本机构建脚本。不依赖 Android Studio，也不依赖 PATH 里有没有 java。
set -e

export JAVA_HOME="/c/Users/Kun/toolchain/jdk-17.0.2"
export ANDROID_HOME="/c/Users/Kun/android-sdk"
GRADLE="/c/Users/Kun/toolchain/gradle-8.9/bin/gradle"

cd "$(dirname "$0")/.."

TASK="${1:-:app:assembleDebug}"

# 第一次跑先铺 gradle wrapper，之后可以直接 ./gradlew
if [ ! -f gradlew ]; then
  "$GRADLE" wrapper --gradle-version 8.9 --no-daemon >/dev/null 2>&1 || true
fi

"$GRADLE" "$TASK" --no-daemon --console=plain
