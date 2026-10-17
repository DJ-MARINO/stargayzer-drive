#!/bin/bash
# Keeps StarGayzer Drive's helper running on the ATOTO X10. Runs on the Pi as a systemd service
# (x10-helper.service). Every 20 s: find the X10's Wireless debugging port over mDNS, connect, and if the
# helper is not running, start it from the installed Drive APK (as the shell user, detached so it keeps
# running after adb disconnects). Drive turns Wireless debugging on after each boot (BootReceiver).
#
# One-time setup on the Pi:  sudo apt install adb avahi-utils
#   then pair with the code shown under Settings > Developer options > Wireless debugging > Pair device:
#   adb pair X10_IP:PAIR_PORT CODE
#
# Usage: x10-helper.sh            loop forever (service)
#        x10-helper.sh once       one pass, then exit
#        x10-helper.sh stop       ask the helper to quit
set -u
PKG=com.djmarino.stargayzer.drive
MAIN=com.djmarino.stargayzer.drive.helper.HelperMain
SERIAL_HINT=${X10_SERIAL_HINT:-adb-916afbf0}   # mDNS instance name prefix of the X10

log() { echo "x10-helper: $*"; }

find_x10() {
  # adb's own mDNS discovery sees nothing on the Pi (avahi-daemon owns 5353), so ask Avahi (avahi-utils):
  # "=;wlan0;IPv4;adb-916afbf0-eyj0DI;_adb-tls-connect._tcp;local;Android.local;192.168.1.232;37107;..."
  timeout 8 avahi-browse -rpt _adb-tls-connect._tcp 2>/dev/null |
    awk -F';' -v h="$SERIAL_HINT" '$1=="=" && $3=="IPv4" && index($4,h)==1 {print $8":"$9; exit}'
}

connect() {
  local addr
  addr=$(find_x10)
  [ -n "$addr" ] || return 1
  if ! adb devices | grep -q "^$addr[[:space:]]*device"; then
    adb connect "$addr" >/dev/null 2>&1 || return 1
    sleep 1
  fi
  adb devices | grep -q "^$addr[[:space:]]*device" || return 1
  echo "$addr"
}

running() {
  adb -s "$1" shell 'ps -A -o ARGS' 2>/dev/null | grep -q '^stargayzer-helper'
}

start_helper() {
  local s=$1
  # shellcheck disable=SC2016
  adb -s "$s" shell 'APK=$(pm path '"$PKG"' | head -1 | cut -d: -f2); [ -n "$APK" ] || exit 3;
    CLASSPATH=$APK setsid nohup app_process /system/bin '"$MAIN"' >/data/local/tmp/stargayzer-helper.log 2>&1 &'
  local rc=$?
  [ $rc -eq 3 ] && log "Drive is not installed on the X10"
  sleep 2
  running "$s" && log "helper started on $s" || log "helper did not start (see /data/local/tmp/stargayzer-helper.log on the X10)"
}

pass() {
  local s
  s=$(connect) || return 0
  running "$s" || start_helper "$s"
}

case "${1:-loop}" in
  once) pass ;;
  stop)
    # not pkill -f: the pattern would match this adb shell command itself
    s=$(connect) && adb -s "$s" shell 'ps -A -o PID,ARGS | awk '\''$2=="stargayzer-helper"{print $1}'\'' | xargs -r kill'
    log "stopped" ;;
  *)
    log "watching for the X10"
    while true; do pass; sleep 20; done ;;
esac
