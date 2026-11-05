#!/usr/bin/env bash
# 真机上的一步一验：发按键，然后立刻报当前焦点落在谁身上。
#
# 用法：
#   ./tools/focus.sh 20 20 23        依次发方向键/确认键，每步都报焦点
#   ./tools/focus.sh -q 20 20        安静模式，只发键不查焦点
#   ./tools/focus.sh -s 01_home      顺手截一张图存到 .shots/01_home.png
#
# 焦点用 uiautomator dump 查，不靠截图目测——截图分不清「白底」和「选中态」，
# 而 focused="true" 是系统给的唯一确凿答案。
set -e
export PATH="/c/Users/Kun/android-sdk/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1
TV=192.168.2.185:5555
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

QUIET=0
SHOT=""
while [ $# -gt 0 ]; do
  case "$1" in
    -q) QUIET=1; shift ;;
    -s) SHOT="$2"; shift 2 ;;
    *) break ;;
  esac
done

connect() {
  if ! adb devices | grep -q "device$"; then
    adb connect "$TV" >/dev/null 2>&1 || true
    sleep 0.8
  fi
}
connect

focus_of() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || { echo "(dump 失败)"; return; }
  # 焦点节点要同时报出 id / 文本 / 坐标三样：
  # 光有文本分不清「列表第几行」和「底栏歌名」（两边可能显示同一首），
  # 坐标才能看出光标到底落在屏幕哪一块。
  adb shell cat /sdcard/ui.xml 2>/dev/null \
    | sed 's/<node/\n<node/g' \
    | grep 'focused="true"' | head -1 \
    | grep -o 'text="[^"]*"\|resource-id="[^"]*"\|bounds="[^"]*"' \
    | sed 's/com.kunplayer.tv:id\///' | tr '\n' ' '
  echo
}

for k in "$@"; do
  connect
  adb shell input keyevent "$k"
  sleep 0.45
  if [ "$QUIET" = "0" ]; then
    printf '%-4s → %s\n' "$k" "$(focus_of)"
  fi
done

if [ -n "$SHOT" ]; then
  connect
  adb shell "screencap -p /sdcard/kp.png"
  adb pull /sdcard/kp.png ".shots/${SHOT}.png" >/dev/null
  echo "截图 → .shots/${SHOT}.png"
fi
