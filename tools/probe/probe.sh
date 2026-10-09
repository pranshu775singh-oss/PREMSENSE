#!/usr/bin/env bash
# Drives the QuickCash probe app and PermSense over adb, so a test run needs no taps.
#
#   tools/probe/probe.sh <command>
#
# Works in Git Bash on Windows, and on Linux/macOS. Needs adb (set ADB=... if it is not on PATH) and
# one device or emulator attached. Build the APKs first:
#   .\tools\gradle-log.cmd :app:assembleDebug :probe:assembleDebug :verifier:installDist
#
# Commands
#   install-app      install PermSense and give it the one-time permissions behind T1 and T1+
#   install-v1       fresh install of QuickCash v1 with its permissions granted
#   upgrade-v2       update QuickCash to v2 in place, exactly as an app store would
#   visible          run the on-screen steps (ACTIVITY)
#   service          run the service steps (SERVICE)
#   job              run the background job now (JOB)
#   background N     N background contacts reads, 2 s apart, from a job (screen state as it is)
#   screen-off       put the phone to sleep
#   screen-on        wake it
#   pull-log [file]  copy the probe's signed intended-access log off the phone
#   verify-log file  check that log with the independent verifier
#   status           what is installed and which permissions are held
#   demo             the whole demonstration, in order (needs a phone you can watch)
set -euo pipefail

# Git Bash would otherwise rewrite "/data/..." style arguments into Windows paths.
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ -z "${ADB:-}" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB=adb
    elif [ -x "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]; then
        ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    else
        echo "adb not found. Install platform-tools or set ADB=/path/to/adb." >&2
        exit 64
    fi
fi

APP=com.ojas.permsense
PROBE=com.ojas.quickcash
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
V1_APK="$ROOT/probe/build/outputs/apk/v1/debug/probe-v1-debug.apk"
V2_APK="$ROOT/probe/build/outputs/apk/v2/debug/probe-v2-debug.apk"
OUT="${OUT:-$ROOT/build-logs/probe}"
mkdir -p "$OUT"

sh() { "$ADB" shell "$@"; }

# adb.exe on Windows does not understand /c/Users/... paths; hand it C:/Users/... instead.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

sdk() { sh getprop ro.build.version.sdk | tr -d '\r'; }

need() { [ -f "$1" ] || { echo "Missing $1. Build it first (see the header of this script)." >&2; exit 66; }; }

grant_app() {
    for p in android.permission.GET_APP_OPS_STATS android.permission.DUMP android.permission.PACKAGE_USAGE_STATS; do
        sh pm grant "$APP" "$p" || echo "  could not grant $p" >&2
    done
    if [ "$(sdk)" -ge 33 ]; then sh pm grant "$APP" android.permission.POST_NOTIFICATIONS || true; fi
}

grant_probe() {
    for p in READ_CONTACTS READ_CALL_LOG CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION; do
        sh pm grant "$PROBE" "android.permission.$p" || echo "  could not grant $p" >&2
    done
    if [ "$(sdk)" -ge 33 ]; then
        sh pm grant "$PROBE" android.permission.READ_MEDIA_IMAGES || true
        sh pm grant "$PROBE" android.permission.POST_NOTIFICATIONS || true
    else
        sh pm grant "$PROBE" android.permission.READ_EXTERNAL_STORAGE || true
    fi
}

cmd="${1:-}"
shift || true

case "$cmd" in

    install-app)
        need "$APP_APK"
        "$ADB" install -r "$(native "$APP_APK")"
        grant_app
        ;;

    install-v1)
        need "$V1_APK"
        "$ADB" uninstall "$PROBE" >/dev/null 2>&1 || true
        "$ADB" install "$(native "$V1_APK")"
        grant_probe
        ;;

    upgrade-v2)
        need "$V2_APK"
        "$ADB" install -r "$(native "$V2_APK")"
        ;;

    visible)
        sh am start -n "$PROBE/com.ojas.probe.MainActivity" --es autorun activity
        sleep 8
        ;;

    service)
        sh am start -n "$PROBE/com.ojas.probe.MainActivity" --es autorun service
        sleep 6
        ;;

    job)
        sh cmd jobscheduler run -f "$PROBE" 1001
        ;;

    background)
        n="${1:-10}"
        sh am broadcast -n "$PROBE/com.ojas.probe.ProbeReceiver" -a com.ojas.quickcash.RUN \
            --es mode job --es op CONTACTS --ei times "$n" --el spacingMs 2000 >/dev/null
        sleep 1
        sh cmd jobscheduler run -f "$PROBE" 1002
        ;;

    screen-off)
        sh input keyevent KEYCODE_SLEEP
        ;;

    screen-on)
        sh input keyevent KEYCODE_WAKEUP
        sh wm dismiss-keyguard || true
        ;;

    pull-log)
        dest="${1:-$OUT/intended_log.jsonl}"
        "$ADB" exec-out run-as "$PROBE" cat files/intended_log.jsonl > "$dest"
        echo "wrote $dest ($(wc -l < "$dest") lines)"
        ;;

    verify-log)
        file="${1:?give the log file}"
        bin="$ROOT/verifier/build/install/permsense-verify/bin/permsense-verify"
        [ -f "$bin.bat" ] && [ ! -f "$bin" ] && bin="$bin.bat"
        need "$bin"
        "$bin" intended-log "$file"
        ;;

    status)
        echo "PermSense: $(sh pm list packages "$APP" | tr -d '\r')"
        echo "QuickCash: $(sh pm list packages "$PROBE" | tr -d '\r')"
        sh dumpsys package "$PROBE" | grep -E "versionName|versionCode" | head -2 || true
        sh dumpsys package "$APP" | grep -E "GET_APP_OPS_STATS|DUMP|PACKAGE_USAGE_STATS" | head -6 || true
        ;;

    demo)
        echo "1/6  installing QuickCash v1 (it asks for contacts; PermSense should warn straight away)"
        "$0" install-v1
        echo "     Open PermSense now: expect a lending warning for contacts."
        read -r -p "     Press Enter to continue... " _
        echo "2/6  using v1 normally: only the on-screen camera"
        "$0" visible
        echo "3/6  updating to v2 over adb"
        "$0" upgrade-v2
        echo "4/6  v2 reads contacts from a background job, screen off"
        "$0" screen-off
        sleep 2
        "$0" job
        sleep 25
        "$0" screen-on
        echo "     Open PermSense: expect RBI-01, INV-01 and NEW-01."
        read -r -p "     Press Enter to continue... " _
        echo "5/6  tap the fix in PermSense, check the Settings app, then undo"
        read -r -p "     Press Enter to continue... " _
        echo "6/6  export the report, then verify it (and edit one byte to watch it fail)"
        ;;

    ""|-h|--help)
        sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
        ;;

    *)
        echo "unknown command: $cmd" >&2
        exit 64
        ;;
esac
