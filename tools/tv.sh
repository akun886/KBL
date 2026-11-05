#!/usr/bin/env bash
# 真机操作的小工具：发按键、截图、捞日志。
#
# 用法：
#   ./tools/tv.sh key 22 20 66        依次发按键（keycode）
#   ./tools/tv.sh shot 01_home        截图存到 .shots/01_home.png
#   ./tools/tv.sh log                 拉诊断日志
#   ./tools/tv.sh crash               拉崩溃记录
set -e
export PATH="/c/Users/Kun/android-sdk/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1
TV=192.168.2.185:5555
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

connect() { adb connect "$TV" >/dev/null 2>&1 || true; sleep 0.6; }

case "$1" in
  key)
    connect
    for k in "${@:2}"; do
      adb shell input keyevent "$k"
      sleep 0.5
    done
    ;;
  shot)
    connect
    name="${2:-shot}"
    adb shell "screencap -p /sdcard/kp.png"
    adb pull /sdcard/kp.png ".shots/${name}.png" >/dev/null
    echo ".shots/${name}.png"
    ;;
  log)
    connect
    adb exec-out run-as com.kunplayer.tv cat files/diag.log
    ;;
  crash)
    connect
    adb exec-out run-as com.kunplayer.tv sh -c 'cat files/crashes/*.log' 2>/dev/null || echo "(没有崩溃记录)"
    ;;
  start)
    connect
    adb shell am force-stop com.kunplayer.tv
    adb shell am start -n com.kunplayer.tv/.MainActivity >/dev/null
    sleep 3
    ;;
  *)
    echo "用法：tv.sh {key|shot|log|crash|start}"
    exit 1
    ;;
esac
