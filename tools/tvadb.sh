#!/bin/sh
# 每次调用都先连一次：本机的 adb server 会在命令之间被回收，
# 重启后的 server 里没有这台网络设备。
#
# MSYS_NO_PATHCONV=1 是必须的：Git Bash 会把 `/sdcard/shot.png` 当成 Windows 路径
# 改写成 `C:/Program Files/.../sdcard/shot.png`，screencap 和 pull 都会失败。
ADB=/c/Users/Kun/android-sdk/platform-tools/adb.exe
export MSYS_NO_PATHCONV=1
$ADB connect 192.168.2.185:5555 >/dev/null 2>&1
case "$1" in
  shot)
    $ADB shell screencap -p /sdcard/shot.png >/dev/null 2>&1
    $ADB pull /sdcard/shot.png "$2" >/dev/null 2>&1
    ls -l "$2"
    ;;
  key)
    shift
    for k in "$@"; do $ADB shell input keyevent "$k"; sleep 0.5; done
    ;;
  *)
    $ADB "$@"
    ;;
esac
