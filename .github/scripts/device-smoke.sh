#!/usr/bin/env bash
# Runtime smoke test, executed by .github/workflows/device-smoke.yml inside a
# booted emulator (`adb` is on PATH there and adb is already connected).
#
# What it proves, and what it deliberately does not:
#   * the app launches and reaches its first frame (not just "an activity was
#     resumed": the first version of this script asserted only that, and its
#     screenshot turned out to be the splash screen);
#   * the UI it reaches is a real screen — the visible text is captured and put
#     in the committed report, so "it rendered" is inspectable rather than
#     assumed;
#   * the foreground-service/notification-permission path survives a launch
#     carrying the episode watcher's extras, and no fatal exception occurs;
#   * an ANR or a system "not responding" dialog is reported as a failure with
#     its logcat trace, instead of being ignored while the checks pass;
#   * screenshots come from the running app (never mock-ups), captured only
#     after the UI is actually up, with the screen text that accompanied them.
#
# It does NOT claim to prove stream playback or a completed download: both
# depend on third-party sites, and they stay marked unverified in the README
# until a run demonstrates them.
set -uo pipefail

APP_ID="com.jcversa.canta"
APK=$(ls app/build/outputs/apk/debug/*.apk | head -n1)
SHOTS="screenshots"
REPORT="$SHOTS/smoke-report.txt"
FAILURES=0
FIRST_DISPLAY_MS="unknown"

log() { echo "smoke: $*"; }
fail() { echo "::error::smoke: $*"; FAILURES=$((FAILURES + 1)); }
note() { echo "$*" >> "$REPORT"; }

ui_text() {
  # The visible text of the current screen. `uiautomator dump` is the supported
  # way to ask; the XML is stripped down to its text attributes so the report
  # reads like a transcript of the screen.
  local dump=/sdcard/canta-ui.xml
  adb shell uiautomator dump "$dump" >/dev/null 2>&1 || return 1
  adb shell cat "$dump" 2>/dev/null \
    | tr '>' '\n' \
    | grep -o 'text="[^"]*"' \
    | sed 's/^text="//; s/"$//' \
    | grep -v '^$' \
    | paste -sd' | ' -
}

wait_for_ui() {
  # Poll until the app has drawn something other than the splash: a bounded wait,
  # because the previous run showed a fixed 20 s sleep was not enough on a
  # software-rendered emulator.
  local deadline=$((SECONDS + 90))
  while [ "$SECONDS" -lt "$deadline" ]; do
    local text
    text=$(ui_text) || true
    if [ -n "${text:-}" ]; then
      echo "$text"
      return 0
    fi
    sleep 5
  done
  return 1
}

anr_lines() {
  # Both the platform's own ANR record and the dialog's wording, because the
  # dialog text is what a user sees and it is what the screenshot showed.
  adb logcat -d 2>/dev/null | grep -E "ANR in |Input dispatching timed out|isn't responding|not responding" | head -n 12
}

mkdir -p "$SHOTS"
rm -f "$SHOTS"/*.png

log "installing $APK"
if ! adb install -r "$APK" >/dev/null 2>&1; then
  adb uninstall "$APP_ID" >/dev/null 2>&1 || true
  adb install "$APK" >/dev/null 2>&1 || fail "APK could not be installed"
fi

adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
# The screenshots are named for the theme they show, so the theme is set rather
# than inherited from the emulator's default (the first captures came out light
# while the file was named "amoled").
adb shell cmd uimode night yes >/dev/null 2>&1 || true
adb logcat -c || true

log "cold start"
START_OUTPUT=$(adb shell am start -W -n "$APP_ID/.MainActivity" 2>&1)
echo "$START_OUTPUT"
TOTAL_TIME=$(echo "$START_OUTPUT" | grep -iE "^TotalTime:" | tr -d '\r' | awk '{print $2}')
[ -n "${TOTAL_TIME:-}" ] && FIRST_DISPLAY_MS="$TOTAL_TIME"

RESUMED=$(adb shell dumpsys activity activities | grep -m1 -E "ResumedActivity|mResumedActivity")
case "$RESUMED" in
  *"$APP_ID"*) : ;;
  *) fail "MainActivity is not the resumed activity after launch" ;;
esac

log "waiting for the first non-splash frame"
SCREEN_TEXT=$(wait_for_ui) || SCREEN_TEXT=""
if [ -z "$SCREEN_TEXT" ]; then
  fail "no UI text appeared within 90 s (the app may still be on the splash screen)"
else
  log "screen text: $SCREEN_TEXT"
fi

{
  echo "Canta device smoke report"
  echo "date: $(date -u)"
  echo "sha: ${GITHUB_SHA:-local}"
  echo "run: ${GITHUB_RUN_NUMBER:-local}"
  echo "emulator api: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "emulator abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "package: $(adb shell dumpsys package "$APP_ID" | grep -m1 versionName | tr -d '\r ')"
  echo "am start -W TotalTime (ms): ${FIRST_DISPLAY_MS}"
  echo "resumed: ${RESUMED:-<none>}"
  echo
  echo "screen text after cold start:"
  echo "  ${SCREEN_TEXT:-<none>}"
} > "$REPORT"

log "capturing catalogue screenshot"
adb exec-out screencap -p > "$SHOTS/01-catalogue-amoled.png" || fail "screencap 01 failed"
[ -s "$SHOTS/01-catalogue-amoled.png" ] || fail "screenshot 01 is empty"

log "launch with the episode watcher's notification extras"
adb shell am start -n "$APP_ID/.MainActivity" \
  --es open_series_id "voir-anime:one-piece-vf" \
  --es open_series_title "One Piece" >/dev/null 2>&1
sleep 20
DEEPLINK_TEXT=$(ui_text) || DEEPLINK_TEXT=""
{
  echo
  echo "screen text after the notification-extras launch:"
  echo "  ${DEEPLINK_TEXT:-<none>}"
} >> "$REPORT"
adb exec-out screencap -p > "$SHOTS/02-notification-deep-link.png" || fail "screencap 02 failed"
[ -s "$SHOTS/02-notification-deep-link.png" ] || fail "screenshot 02 is empty"

# The Settings tab is the rightmost of four. Its own body carries strings that
# exist nowhere else in the app ("Effacer l'historique"), so the tab tap is
# verified by text rather than assumed — the first run tapped and silently
# captured the catalogue again.
WIDTH=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | head -n1 | cut -dx -f1)
HEIGHT=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | head -n1 | cut -dx -f2)
SETTINGS_TEXT=""
if [ -n "${WIDTH:-}" ] && [ -n "${HEIGHT:-}" ]; then
  adb shell input tap $((WIDTH * 7 / 8)) $((HEIGHT - 80)) >/dev/null 2>&1 || true
  sleep 8
  SETTINGS_TEXT=$(ui_text) || SETTINGS_TEXT=""
  log "screen text after tapping the last tab: $SETTINGS_TEXT"
  case "$SETTINGS_TEXT" in
    *"Effacer l'historique"*|*Réglages*)
      adb exec-out screencap -p > "$SHOTS/03-settings.png" || true
      ;;
    *)
      # Not the settings screen: no misleading file is kept.
      log "the settings tab was not reached - not saving 03-settings.png"
      rm -f "$SHOTS/03-settings.png"
      ;;
  esac
fi
{
  echo
  echo "settings tab reached (verified by its own text): $([ -n "$SETTINGS_TEXT" ] && echo "yes/no -> ${SETTINGS_TEXT}" || echo "not attempted")"
} >> "$REPORT"

log "checking for ANRs and crashes"
ANR=$(anr_lines)
CRASHES=$(adb logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION")
adb logcat -d -b crash > crashes.txt 2>/dev/null || true
{
  echo
  echo "ANR / not-responding lines in logcat:"
  if [ -n "${ANR:-}" ]; then echo "$ANR" | sed 's/^/  /'; else echo "  <none>"; fi
  echo "FATAL EXCEPTION entries in the crash buffer: ${CRASHES:-0}"
} >> "$REPORT"

[ -n "${ANR:-}" ] && fail "the system reported an ANR / not-responding dialog during the run"
[ "${CRASHES:-0}" -gt 0 ] && { echo "--- crash log ---"; cat crashes.txt; fail "the app crashed during the smoke test"; }

if adb shell pidof "$APP_ID" >/dev/null 2>&1; then
  log "process is alive"
else
  fail "the app process is not running at the end of the test"
fi

{
  echo
  echo "screenshots:"
  ls -la "$SHOTS"/*.png 2>/dev/null | awk '{print "  " $9 " (" $5 " bytes)"}'
} >> "$REPORT"
cat "$REPORT"

if [ "$FAILURES" -gt 0 ]; then
  log "FAIL ($FAILURES problem(s))"
  exit 1
fi
log "PASS"
exit 0
