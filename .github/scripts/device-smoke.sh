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
#   * an ANR *in Canta* fails the run with its logcat trace. The emulator's own
#     system processes ANR on software-rendered CI runners whatever is installed
#     (run 3 logged nine, none of them in this app), so those are reported in the
#     committed report instead of being blamed on the app: a false finding is
#     worse than no finding;
#   * screenshots come from the running app (never mock-ups): the app window must
#     be the focused one at the moment of the screencap, and a capture taken while
#     the emulator's own "not responding" dialog is up is named
#     "...-with-system-dialog.png", never the canonical name. A run that cannot
#     produce one verified capture fails, because a green run whose artefact is
#     the launcher (run 5) is worse than a red one.
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

ui_text() {
  # Best-effort transcription of the visible text. On some emulator images
  # `uiautomator dump` simply never produces a file (run 4: it returned nothing
  # for the whole session while the screen was demonstrably drawn), so nothing
  # here is allowed to *depend* on it: callers treat an empty result as "text
  # unavailable", never as "the screen is empty".
  local dump=/sdcard/canta-ui.xml
  adb shell uiautomator dump "$dump" >/dev/null 2>&1 || return 1
  local text
  text=$(adb shell cat "$dump" 2>/dev/null \
    | tr '>' '\n' \
    | grep -o 'text="[^"]*"' \
    | sed 's/^text="//; s/"$//' \
    | grep -v '^$' \
    | paste -sd' | ' -)
  [ -n "${text:-}" ] || return 1
  echo "$text"
}

focused_window() {
  # The focused window, from the window manager rather than the accessibility
  # layer: this is what tells the script whether the app is on top or whether a
  # system dialog is (an ANR dialog takes focus and announces itself here).
  adb shell dumpsys window 2>/dev/null | grep -m1 -E "mCurrentFocus|mFocusedWindow"
}

dialog_is_up() {
  # Matches the emulator's own ANR / "isn't responding" / crash dialogs. It does
  # not match the app: the app is a normal activity window.
  focused_window | grep -qiE "not responding|application error|application not responding|\banr\b"
}

app_is_focused() {
  # Is the app's own window the focused one? This is the check that keeps "a
  # capture of the running app" honest, and run 5 is why it exists: that run
  # passed while its 01 screenshot was the launcher, because the dialog dismissal
  # had tapped the system navigation bar and nothing verified what was in front.
  focused_window | grep -q "$APP_ID"
}

bring_app_to_front() {
  local deadline=$((SECONDS + 45))
  adb shell am start -n "$APP_ID/.MainActivity" >/dev/null 2>&1 || true
  while [ "$SECONDS" -lt "$deadline" ]; do
    app_is_focused && return 0
    sleep 3
  done
  return 1
}

capture_app_screen() {
  # $1: canonical basename, e.g. 01-app-amoled.png. Sets LAST_CAPTURE to the
  # basename actually written, or leaves it empty when nothing was verifiable.
  # The state is sampled immediately before the capture, not earlier: run 5
  # reported "system dialog: yes" from a sample taken before its dismissal, then
  # captured a screen the dialog had already left.
  local base="${1%.png}"
  LAST_CAPTURE=""
  if ! dialog_is_up; then
    app_is_focused || { log "the app is not in front - bringing it back"; bring_app_to_front || true; }
  fi
  if ! app_is_focused; then
    log "the app window is not focused - no capture (a picture of something else proves nothing)"
    return 1
  fi
  local name="$base"
  dialog_is_up && name="${base}-with-system-dialog"
  adb exec-out screencap -p > "$SHOTS/$name" || return 1
  [ -s "$SHOTS/$name" ] || return 1
  LAST_CAPTURE="$name"
  log "captured $name (app focused: yes, system dialog: $(dialog_is_up && echo yes || echo no))"
  return 0
}

frames_rendered() {
  # How many frames this app has actually drawn. This is the check that replaces
  # "an activity was resumed": a resumed activity can still be showing the splash
  # screen, which is exactly what the first version of this script captured and
  # called a catalogue screenshot.
  adb shell dumpsys gfxinfo "$APP_ID" 2>/dev/null \
    | grep -m1 -E "Total frames rendered" \
    | awk -F: '{gsub(/ /, "", $2); print $2}'
}

try_dismiss_dialog() {
  # An ANR dialog is dismissed by choosing "Wait" - NOT by BACK, which dismisses
  # the dialog *and* leaves the app (run 5 ended up on the launcher that way,
  # with the app still "resumed" in the activity manager). Without a usable
  # accessibility dump the row position is not readable, so the tap uses the
  # layout every AOSP build gives this dialog: a centred card whose "Wait" row
  # sits just below the middle.
  local attempt w h
  w=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | head -n1 | cut -dx -f1)
  h=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | head -n1 | cut -dx -f2)
  for attempt in 1 2 3; do
    dialog_is_up || return 0
    log "a system dialog is focused - tapping its Wait row (attempt $attempt)"
    if [ -n "${w:-}" ] && [ -n "${h:-}" ]; then
      adb shell input tap $((w / 2)) $((h * 57 / 100)) >/dev/null 2>&1 || true
    fi
    sleep 3
  done
  dialog_is_up || return 0
  # Taps did not clear it. BACK as a last resort, then put the app back in front
  # ourselves rather than leaving whatever the system shows behind it.
  log "the dialog survived the taps - using BACK and restoring the app"
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  sleep 3
  bring_app_to_front || true
  return 0
}

mkdir -p "$SHOTS"
# Deliberately no blanket `rm` here. A capture is skipped, not deleted, when it
# cannot be verified this run: removing a previously verified image because a
# flaky emulator hiccuped would lose a true artefact and gain nothing.

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
TOTAL_TIME=$(echo "$START_OUTPUT" | grep -iE "TotalTime|WaitTime" | tr -d '\r' | awk -F: '{gsub(/ /,"",$2); print $2}' | paste -sd/ -)
[ -n "${TOTAL_TIME:-}" ] && FIRST_DISPLAY_MS="$TOTAL_TIME" || FIRST_DISPLAY_MS="not reported by am start -W"

RESUMED=$(adb shell dumpsys activity activities | grep -m1 -E "ResumedActivity|mResumedActivity")
case "$RESUMED" in
  *"$APP_ID"*) : ;;
  *) fail "MainActivity is not the resumed activity after launch" ;;
esac

try_dismiss_dialog
log "waiting for the app to draw frames"
FRAMES=""
if adb shell dumpsys gfxinfo "$APP_ID" 2>/dev/null | grep -q "Total frames rendered"; then
  # Readable: poll until the app has drawn at least one frame. A resumed activity
  # can still be on the splash screen, which is what the first version of this
  # script captured and mislabelled.
  deadline=$((SECONDS + 90))
  while [ "$SECONDS" -lt "$deadline" ]; do
    FRAMES=$(frames_rendered)
    case "${FRAMES:-}" in
      ""|0) sleep 5 ;;
      *) break ;;
    esac
  done
  if [ -z "${FRAMES:-}" ]; then FRAMES="0"; fi
  if [ "$FRAMES" = "0" ]; then
    fail "the app rendered no frames within 90 s (it may still be on the splash screen)"
  fi
else
  # Unreadable on this image. Not a failure - an unreadable metric is not evidence
  # of a defect - but the run then has no proof that the app drew anything, and
  # the report says exactly that instead of implying it was verified.
  FRAMES="unreadable"
  log "dumpsys gfxinfo cannot report frames on this image - skipping the frame wait"
fi
log "frames rendered: $FRAMES"

SCREEN_TEXT=$(ui_text) || SCREEN_TEXT=""
TEXT_STATE="read"
[ -n "$SCREEN_TEXT" ] || TEXT_STATE="unavailable (uiautomator dump produced nothing)"

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

log "capturing the app screen"
# Every capture is verified: the app window must be the focused one at the moment
# of the screencap, and the name says whether a system dialog is also up. A run
# that cannot produce one verified capture fails - a green run whose artefact is
# the launcher (run 5) is worse than a red one.
PRODUCED=""
CAPTURES_OK=0

if [ "$FRAMES" = "0" ]; then
  log "the app has drawn no frames - no 01 capture (it would be the splash screen)"
elif capture_app_screen "01-app-amoled.png"; then
  PRODUCED="$LAST_CAPTURE"
  CAPTURES_OK=$((CAPTURES_OK + 1))
fi

# The file is named for what it shows. The catalogue's honest state on a runner
# that cannot resolve the source site is its error message, so the report states
# which state was on screen rather than letting the filename imply one.
CATALOGUE_STATE="unknown (screen text unavailable - uiautomator dump produced nothing)"
case "$SCREEN_TEXT" in
  *"Chargement"*) CATALOGUE_STATE="still loading" ;;
  *"Source : "*) CATALOGUE_STATE="catalogue items or empty state" ;;
  *"Requête échouée"*) CATALOGUE_STATE="source unreachable (the app reports the cause)" ;;
esac

log "launch with the episode watcher's notification extras"
adb shell am start -n "$APP_ID/.MainActivity" \
  --es open_series_id "voir-anime:one-piece-vf" \
  --es open_series_title "One Piece" >/dev/null 2>&1
sleep 25
DEEPLINK_DIALOG="no"
dialog_is_up && DEEPLINK_DIALOG="yes"
DEEPLINK_TEXT=$(ui_text) || DEEPLINK_TEXT=""
if [ "$FRAMES" != "0" ] && capture_app_screen "02-notification-extras-launch.png"; then
  PRODUCED="$PRODUCED, $LAST_CAPTURE"
  CAPTURES_OK=$((CAPTURES_OK + 1))
fi

# The Settings tab is the rightmost of four. Its own body carries a string that
# exists nowhere else in the app ("Effacer l'historique"), so the tap is verified
# by text as well as by focus: in a single-activity app, nothing else proves the
# tab was reached. The tap is placed inside the app's own navigation bar
# (~92% of the height); the first two runs used height-80/height-110, which is
# the system navigation bar, and both silently ended up somewhere else.
WIDTH=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | head -n1 | cut -dx -f1)
HEIGHT=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | head -n1 | cut -dx -f2)
SETTINGS_VERDICT="not attempted"
if [ -n "${WIDTH:-}" ] && [ -n "${HEIGHT:-}" ]; then
  try_dismiss_dialog
  adb shell input tap $((WIDTH * 7 / 8)) $((HEIGHT * 92 / 100)) >/dev/null 2>&1 || true
  sleep 8
  SETTINGS_TEXT=$(ui_text) || SETTINGS_TEXT=""
  case "$SETTINGS_TEXT" in
    *"Effacer l'historique"*)
      if capture_app_screen "03-settings.png"; then
        SETTINGS_VERDICT="reached and captured (verified by its own text and by focus)"
        PRODUCED="$PRODUCED, $LAST_CAPTURE"
        CAPTURES_OK=$((CAPTURES_OK + 1))
      else
        SETTINGS_VERDICT="reached, but no capture was verifiable"
      fi
      ;;
    "")
      SETTINGS_VERDICT="not verified (screen text unavailable), no capture"
      ;;
    *)
      SETTINGS_VERDICT="not reached (screen shows: ${SETTINGS_TEXT}), no capture"
      ;;
  esac
  log "settings: $SETTINGS_VERDICT"
fi

if [ "$CAPTURES_OK" -eq 0 ]; then
  fail "no verified capture of the app could be produced (see the report for why)"
fi

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

APP_ANR=$(app_anr_lines)
[ -n "${APP_ANR:-}" ] && fail "the app itself ANR'd during the run"
if [ -n "${ANR:-}" ] && [ -z "${APP_ANR:-}" ]; then
  log "system-process ANRs were logged by the emulator; none of them is Canta (reported, not counted as an app failure)"
fi
[ "${CRASHES:-0}" -gt 0 ] && { echo "--- crash log ---"; cat crashes.txt; fail "the app crashed during the smoke test"; }

if adb shell pidof "$APP_ID" >/dev/null 2>&1; then
  log "process is alive"
else
  fail "the app process is not running at the end of the test"
fi

{
  echo
  echo "ANR in $APP_ID: ${APP_ANR:-<none>}"
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
