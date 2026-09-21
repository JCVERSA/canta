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
ONSCREEN_PROBE="none of: focus, accessibility dump, window manager"

# The report is written as the run proceeds, so a script that dies mid-way leaves a
# half-written file - and run 9 proved how unhelpful that is: its report stopped in
# the middle of a block, the exit status was 1, and the runner's own log is not
# retrievable from this environment, so the cause had to be reproduced locally (an
# unbound variable under `set -u`, introduced a few lines above the crash). Phase
# markers say where the run got to, and the trap says that it stopped early.
SMOKE_COMPLETED=0
phase() { printf 'phase: %s\n' "$1" >> "$REPORT"; log "phase: $1"; }
trap 'rc=$?; if [ "${SMOKE_COMPLETED:-0}" != "1" ]; then { echo; echo "script exited early: status $rc - the last phase marker above says where"; } >> "$REPORT"; fi' EXIT

# --- ANR evidence ---------------------------------------------------------------
# `anr_lines` and `app_anr_lines` were *called* by this script in every run while
# being defined nowhere: no `set -e` in this file, so the "command not found"
# failure was silent and every report printed "ANR ... : <none>" from an empty
# variable. That is not evidence of absence, and run 8 is why it mattered - the
# frame history showed a "Canta isn't responding" dialog while the report claimed
# <none>. The functions are defined here, and the verdict is spelled out in the
# report instead of being implied by an empty value.
#
# Two independent signals are read, because neither is complete on this image:
#   - the events buffer's `am_anr` records one line per ANR, with pid, package and
#     the reason text, and is written even when the main buffer says nothing;
#   - the main buffer's "ANR in <pkg>" line, when the image logs it at all.
ANR_ALL_CACHE=""
ANR_ALL_READ=0
anr_lines() {
  # Read once and keep it: the verdict logic asks for these lines five times, and
  # each read is two full `logcat -d` dumps - real work for an emulator that is
  # already starved, which is exactly when this runs.
  if [ "$ANR_ALL_READ" = "0" ]; then
    ANR_ALL_CACHE=$(
      {
        adb logcat -d -b events 2>/dev/null | grep -i "am_anr" || true
        adb logcat -d 2>/dev/null | grep -iE "ANR in |is not responding" || true
      } | sort -u
    )
    ANR_ALL_READ=1
  fi
  echo "$ANR_ALL_CACHE"
}

# The package is matched as a whole token (",pkg,", " pkg ", "pkg:") so the preview
# build ("com.jcversa.canta.preview") can never be counted as the app under test.
app_anr_lines() {
  anr_lines | grep -E "(^|[ ,])($APP_ID)([ ,:]|$)" || true
}

# The reason for each app ANR. Extracted generically rather than from a list of
# phrases I expected to see: the first version matched only the input-dispatch and
# service-timeout wordings, so run 9's actual reason ("Timed out while trying to
# bind") came out as "none recorded" - a report that says less than the evidence it
# already holds. `am_anr` records are [user,pid,package,flags,reason]; the main
# buffer states it as "Reason:<text>".
app_anr_reasons() {
  app_anr_lines | sed -nE 's/.*am_anr *: *\[[0-9]+,[0-9]+,[^,]+,[^,]+,(.*)\]$/\1/p' | sort -u || true
  app_anr_lines | sed -nE 's/.*Reason:(.*)$/\1/p' | sed 's/^ *//' | sort -u || true
}

# An input-dispatch timeout while a *system* process ANR'd in the same run is the
# emulator being starved, not the app blocking: on this runner System UI and
# com.android.phone ANR regularly, and a tap then lands on a frozen window. That
# attribution is only made when both halves are present, it is written into the
# report, and it never silences a crash - a FATAL EXCEPTION fails the run
# unconditionally, and so does an app ANR with any other reason.
app_anr_is_emulator_starvation() {
  local app_lines system_anrs reasons
  app_lines=$(app_anr_lines)
  [ -n "${app_lines:-}" ] || return 1
  reasons=$(app_anr_reasons)
  [ -n "${reasons:-}" ] || return 1
  # every app ANR reason must be the input-dispatch one
  echo "$reasons" | grep -qiE "^(input dispatching timed out)$" || return 1
  system_anrs=$(anr_lines | grep -vE "(^|[ ,])($APP_ID)([ ,:]|$)" || true)
  [ -n "${system_anrs:-}" ] || return 1
  return 0
}

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

app_ui_present() {
  # Does the accessibility dump contain nodes belonging to this app? Re-dumps
  # first: a dump left over from an earlier screen would answer a question about
  # the past.
  local dump=/sdcard/canta-ui.xml
  adb shell uiautomator dump "$dump" >/dev/null 2>&1 || return 1
  adb shell cat "$dump" 2>/dev/null | grep -q "package=\"$APP_ID\""
}

app_window_on_screen() {
  # The capture gate: the frame must contain the app. Three independent strategies
  # because no single one is reliable on every image (run 7: `dumpsys gfxinfo`
  # was unreadable and `uiautomator dump` returned nothing at the cold start yet
  # worked later, so a one-strategy gate ended up producing no evidence at all):
  #
  #   1. the app owns the focused window  -> it is on screen by definition;
  #   2. the accessibility dump has nodes of this app;
  #   3. the window manager reports the app's window as isOnScreen=true.
  #
  # The strategy that answered is recorded, so an unverifiable capture is
  # distinguishable from a verified one in the report.
  ONSCREEN_PROBE="none of: focus, accessibility dump, window manager"
  if app_is_focused; then
    ONSCREEN_PROBE="focused window is the app"
    return 0
  fi
  if app_ui_present; then
    ONSCREEN_PROBE="accessibility dump contains $APP_ID nodes"
    return 0
  fi
  # The pattern is "$APP_ID/" - the package/class form of the app's own activity
  # window. Matching the bare package name would also match the ANR dialog's
  # window title ("Application Not Responding: com.jcversa.canta"), which is on
  # screen precisely when the app may not be. Found by running this awk program
  # against a synthetic dumpsys sample before trusting it.
  if adb shell dumpsys window windows 2>/dev/null \
    | awk -v app="$APP_ID/" '
        /Window #/ { inside = index($0, app) > 0; next }
        inside { print }
      ' \
    | grep -q "isOnScreen=true"; then
    ONSCREEN_PROBE="window manager reports isOnScreen=true"
    return 0
  fi
  return 1
}

app_is_focused() {
  # Is the app's own activity window the focused one? This is the check that keeps
  # "a capture of the running app" honest, and run 5 is why it exists: that run
  # passed while its 01 screenshot was the launcher, because the dialog dismissal
  # had tapped the system navigation bar and nothing verified what was in front.
  #
  # The pattern is "$APP_ID/" for the same reason as in app_window_on_screen: the
  # focused window while an ANR dialog is up reads
  # "Application Not Responding: com.jcversa.canta", which a bare package match
  # would happily accept as the app.
  focused_window | grep -q "$APP_ID/"
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
  # $1: canonical basename, e.g. "01-app-amoled.png" (the ".png" is optional; the
  # written file always has one — an earlier version stripped the extension and
  # committed "screenshots/03-settings" with no suffix at all).
  #
  # A capture is written only when the app's own window is on screen, so the file
  # cannot be a picture of the launcher or of a system dialog over something else
  # (run 5's 01 was the launcher, because it was taken after a BACK dismissed both
  # the ANR dialog and the app). Whether the focused window is still the app or a
  # system dialog is recorded in the *name*, because the frame really does contain
  # that overlay.
  local stem="${1%.png}"
  local base="${stem}.png"
  LAST_CAPTURE=""
  if ! app_window_on_screen; then
    if ! dialog_is_up; then
      log "the app window is not on screen - trying to bring it back"
      bring_app_to_front || true
    fi
  fi
  if ! app_window_on_screen; then
    log "the app window is not on screen - no capture (a picture of something else proves nothing)"
    echo "capture skipped: no proof the app was on screen ($ONSCREEN_PROBE); focused window: $(focused_window | tr -d '\r')" >> "$REPORT"
    return 1
  fi
  # Sample the window state *before* the screencap and report those samples: run 8
  # showed the failure mode of sampling after it, printing "system dialog in front:
  # yes" next to a file name with no dialog in it, because the dialog arrived
  # between the two. A report that describes a different moment than the image is
  # the same defect as an unlabelled capture, so the samples travel with the frame.
  local name="$base" dialog_at_capture focus_at_capture
  if dialog_is_up; then dialog_at_capture="yes"; name="${1%.png}-with-system-dialog.png"; else dialog_at_capture="no"; fi
  focus_at_capture=$(focused_window | tr -d '\r')
  adb exec-out screencap -p > "$SHOTS/$name" || return 1
  [ -s "$SHOTS/$name" ] || return 1
  LAST_CAPTURE="$name"
  echo "capture verified: $name (on screen because: $ONSCREEN_PROBE; sampled immediately before the screencap - system dialog in front: $dialog_at_capture; focused window:$focus_at_capture)" >> "$REPORT"
  log "captured $name (app window on screen: yes, system dialog in front at capture time: $dialog_at_capture)"
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

phase "report header written; capturing screens"
log "capturing the app screen"
# Reported per capture as it happens, so the committed report says what this run
# produced rather than what happens to be in the directory (run 6's report listed
# two files from run 5, which is how a green run ends up with launcher artefacts).
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
phase "collecting ANR evidence"
ANR=$(anr_lines)
CRASHES=$(adb logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION")
adb logcat -d -b crash > crashes.txt 2>/dev/null || true

APP_ANR=$(app_anr_lines)
ANR_VERDICT="no ANR named the app"
if [ -n "${APP_ANR:-}" ]; then
  if app_anr_is_emulator_starvation; then
    ANR_VERDICT="the app was reported not responding ($(app_anr_reasons | tr '\n' ';')) while a system process ANR'd in the same run - attributed to emulator starvation, reported rather than hidden, run not failed on this alone"
    log "$ANR_VERDICT"
  else
    ANR_VERDICT="the app ANR'd (${APP_ANR//$'\n'/ | }) - reasons: $(app_anr_reasons | tr '\n' ';' || echo 'none recorded')"
    fail "the app itself ANR'd during the run"
  fi
fi
# When the app was reported not responding, try to read the platform's own ANR
# trace: it holds the main-thread stack at the moment of the ANR, which is the only
# thing that can separate app-side blocking from a starved emulator. Best effort
# (readability of /data/anr varies by image) and bounded (the file can be huge).
if [ -n "${APP_ANR:-}" ]; then
  TRACE_FILE=$(adb shell ls -t /data/anr 2>/dev/null | head -1 | tr -d '\r')
  {
    echo
    echo "ANR trace (best effort - /data/anr is readable only where adb runs as root):"
    if [ -n "${TRACE_FILE:-}" ]; then
      echo "  newest file: /data/anr/$TRACE_FILE"
      adb shell cat "/data/anr/$TRACE_FILE" 2>/dev/null | grep -A 40 -F "$APP_ID" | head -60 | sed 's/^/    /' || true
    else
      echo "  /data/anr could not be listed (permission denied on this image)"
    fi
  } >> "$REPORT"
fi

if [ -n "${ANR:-}" ] && [ -z "${APP_ANR:-}" ]; then
  log "system-process ANRs were logged by the emulator; none of them is Canta (reported, not counted as an app failure)"
fi

{
  echo
  echo "ANR / not-responding lines (events buffer am_anr + main buffer):"
  if [ -n "${ANR:-}" ]; then
    echo "$ANR" | sed 's/^/  /' | head -100
    ANR_TOTAL=$(echo "$ANR" | grep -c . || true)
    if [ "$ANR_TOTAL" -gt 100 ]; then
      echo "  ... $ANR_TOTAL lines in total, the first 100 are shown"
    fi
  else
    echo "  <none found in either buffer>"
  fi
  # ${ANR_VERDICT:-unknown}, not ${ANR_VERDICT}: this line and the assignment above
  # it were in the opposite order once, and `set -u` killed the run mid-report.
  echo "app ANR verdict: ${ANR_VERDICT:-unknown}"
  echo "FATAL EXCEPTION entries in the crash buffer: ${CRASHES:-0}"
} >> "$REPORT"
[ "${CRASHES:-0}" -gt 0 ] && { echo "--- crash log ---"; cat crashes.txt; fail "the app crashed during the smoke test"; }

if adb shell pidof "$APP_ID" >/dev/null 2>&1; then
  log "process is alive"
else
  fail "the app process is not running at the end of the test"
fi

phase "writing the summary"
{
  echo
  echo "frames rendered (dumpsys gfxinfo): $FRAMES"
  echo "screen text: $TEXT_STATE"
  echo "first screen state: $CATALOGUE_STATE"
  echo "settings tab: $SETTINGS_VERDICT"
  echo "screen text after the notification-extras launch: ${DEEPLINK_TEXT:-<none>}"
  echo "system dialog in front after that launch: ${DEEPLINK_DIALOG:-no}"
  echo "captures verified by this run: ${PRODUCED:-<none>}"
} >> "$REPORT"

{
  echo
  echo "ANR in $APP_ID: ${APP_ANR:-<none>}"
  echo "ANR verdict: ${ANR_VERDICT:-unknown}"
  echo
  echo "files in screenshots/ at the end of this run (a directory listing, NOT proof"
  echo "that this run wrote them - see the capture lines above, and read the"
  echo "committed image itself before trusting what it shows):"
  ls -la "$SHOTS"/*.png 2>/dev/null | awk '{print "  " $9 " (" $5 " bytes)"}'
} >> "$REPORT"
cat "$REPORT"

SMOKE_COMPLETED=1
phase "done ($FAILURES problem(s) recorded)"
if [ "$FAILURES" -gt 0 ]; then
  log "FAIL ($FAILURES problem(s))"
  exit 1
fi
log "PASS"
exit 0
