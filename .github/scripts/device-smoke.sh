#!/usr/bin/env bash
# Runtime smoke test, executed by .github/workflows/device-smoke.yml inside a
# booted emulator (`adb` is on PATH there and adb is already connected).
#
# What it proves, and what it deliberately does not:
#   * proves the app launches, App.onCreate finishes (container, notification
#     channels, WorkManager configuration, cache warm-up), MainActivity is the
#     resumed activity, a second launch carrying the episode watcher's
#     notification extras is handled, and no process crashed either time;
#   * it also captures the screenshots the README points at, from the running
#     app, so nothing in screenshots/ is a mock-up;
#   * it does NOT prove that a stream plays or that a download completes — that
#     depends on third-party sites and a real user's choices. Those stay marked
#     unverified rather than being implied by this job.
set -uo pipefail

APP_ID="com.jcversa.canta"
APK=$(ls app/build/outputs/apk/debug/*.apk | head -n1)
SHOTS="screenshots"
FAILURES=0

log() { echo "smoke: $*"; }
fail() { echo "::error::smoke: $*"; FAILURES=$((FAILURES + 1)); }

mkdir -p "$SHOTS"

log "installing $APK"
if ! adb install -r "$APK" >/dev/null 2>&1; then
  # Some images are picky about replacing a package; a fresh install is the
  # honest fallback rather than treating an install error as a test failure.
  adb uninstall "$APP_ID" >/dev/null 2>&1 || true
  adb install "$APK" >/dev/null 2>&1 || fail "APK could not be installed"
fi

# Pre-grant the notification permission (API 33+) so the run is deterministic and
# the screenshots are not covered by a system dialog. The app asks for it itself
# on first launch; granting it here tests exactly the same code path afterwards.
adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

adb logcat -c || true

log "cold start"
adb shell am start -W -n "$APP_ID/.MainActivity" | tee -a smoke.log
sleep 20

RESUMED=$(adb shell dumpsys activity activities | grep -m1 -E "ResumedActivity|mResumedActivity")
log "resumed activity: ${RESUMED:-<none>}"
case "$RESUMED" in
  *"$APP_ID"*) : ;;
  *) fail "MainActivity is not the resumed activity after launch" ;;
esac

# The catalogue is read from a third-party site: an empty or error state here is
# a legitimate screen (the app prints the cause), so nothing about the network
# result fails this test. What must not happen is a crash.
log "capturing screenshot 01"
adb exec-out screencap -p > "$SHOTS/01-catalogue-amoled.png" || fail "screencap 01 failed"
if [ ! -s "$SHOTS/01-catalogue-amoled.png" ]; then
  fail "screenshot 01 is empty"
fi

log "second launch with the episode watcher's notification extras"
adb shell am start -n "$APP_ID/.MainActivity" \
  --es open_series_id "voir-anime:one-piece-vf" \
  --es open_series_title "One Piece" | tee -a smoke.log
sleep 15

adb exec-out screencap -p > "$SHOTS/02-notification-deep-link.png" || fail "screencap 02 failed"
if [ ! -s "$SHOTS/02-notification-deep-link.png" ]; then
  fail "screenshot 02 is empty"
fi

# Best effort: the Settings tab sits in the last quarter of the bottom bar. If
# the tap misses, the screenshot is simply another catalogue view — this must not
# fail the run, it only makes the shot set richer when the layout is as expected.
WIDTH=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | head -n1 | cut -dx -f1)
HEIGHT=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | head -n1 | cut -dx -f2)
if [ -n "${WIDTH:-}" ] && [ -n "${HEIGHT:-}" ]; then
  adb shell input tap $((WIDTH * 7 / 8)) $((HEIGHT - 80)) >/dev/null 2>&1 || true
  sleep 6
  adb exec-out screencap -p > "$SHOTS/03-settings.png" || true
  log "tapped the last tab at $((WIDTH * 7 / 8)),$((HEIGHT - 80))"
fi

log "checking the crash buffer"
adb logcat -d -b crash > crashes.txt 2>/dev/null || true
if grep -q "FATAL EXCEPTION" crashes.txt; then
  echo "--- crash log ---"
  cat crashes.txt
  fail "the app crashed during the smoke test"
else
  log "no fatal exceptions in the crash buffer"
fi

# A process that died and was never restarted is a crash the crash buffer may not
# show (e.g. a native abort); checking the process is still alive is cheap.
if pidof "$APP_ID" >/dev/null 2>&1 || adb shell pidof "$APP_ID" >/dev/null 2>&1; then
  log "process is alive"
else
  fail "the app process is not running at the end of the test"
fi

adb shell dumpsys package "$APP_ID" | grep -E "versionName|versionCode" | head -n2 | tee -a smoke.log
log "captured screenshots:"
ls -la "$SHOTS"

if [ "$FAILURES" -gt 0 ]; then
  log "FAIL ($FAILURES problem(s))"
  exit 1
fi
log "PASS"
exit 0
