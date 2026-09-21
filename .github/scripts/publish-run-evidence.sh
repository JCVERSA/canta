#!/usr/bin/env bash
# Publishes what a device-smoke run produced onto the branch: the captures, the
# report, and the run's own log.
#
# Why a single script does all of it: runs 10 and 11 each failed in the emulator
# step and left *nothing* readable behind - the two publish steps reported success
# while taking 0.0 s and committing nothing, the job log is unreachable from the
# development sandbox (the Actions log endpoint redirects to a storage host that is
# not reachable there), and the log step wrote a file that never got pushed. A red
# run nobody can read is worth very little, so this script:
#
#   * writes the log to a committed path (`.ci-logs/device-smoke.txt`);
#   * reports the run's failure through *annotations* as well, because check-run
#     annotations are readable through the API from anywhere - that is the channel
#     that made it possible to learn "adb exited 224" from run 10;
#   * commits the log and the captures in one commit and pushes once, with retries
#     and without hiding the reason a push failed.
#
# It is written to be readable from outside: every branch it takes prints a line
# that says what it decided and why.
set -uo pipefail

cd "$(git rev-parse --show-toplevel)"

BRANCH="${GITHUB_REF_NAME:-main}"
STATUS="${JOB_STATUS:-unknown}"
SHA="${GITHUB_SHA:-unknown}"
RUN="${GITHUB_RUN_NUMBER:-0}"

# ---------------------------------------------------------------- annotations
# Escape the little that the workflow-command format needs, and keep messages
# short: annotations are capped, and a truncated dump is not worth sending.
annotate() {
  local level="$1" title="$2" msg="$3"
  msg=$(printf '%s' "$msg" | tr '\n\r' '  ' | sed -e 's/%/%25/g' -e 's/\r//g' | cut -c1-900)
  printf '::%s title=%s::%s\n' "$level" "$title" "$msg"
}

# ------------------------------------------------------------------- the log
mkdir -p .ci-logs
{
  echo "job: device-smoke"
  echo "sha: $SHA"
  echo "run: $RUN"
  echo "job status: $STATUS"
  echo "date: $(date -u)"
  echo
  echo "--- build.log tail ---"
  if [ -s build.log ]; then tail -c 8000 build.log; else echo "(no build.log)"; fi
  echo
  echo "--- smoke.log tail ---"
  if [ -s smoke.log ]; then tail -c 20000 smoke.log; else echo "(no smoke.log)"; fi
} > .ci-logs/device-smoke.txt

{
  echo "job: device-smoke"
  echo "sha: $SHA"
  echo
  echo "--- errors ---"
  grep -hE "^(e: |FAILURE|\* What went wrong)|FATAL EXCEPTION|::error::|smoke: FAIL|script exited early" \
    build.log smoke.log 2>/dev/null | head -n 120
  echo
  echo "--- smoke.log, first 12 lines (device state at the start) ---"
  head -n 12 smoke.log 2>/dev/null
} > .ci-logs/device-smoke-errors.txt

# The readable failure channel. When the smoke script never ran, the most useful
# facts are the device state at the start of the step and its last words, so those
# travel in the annotation itself rather than only in a file that may not reach the
# branch.
if [ -s smoke.log ]; then
  HEAD_LINES=$(head -n 6 smoke.log | tr '\n' ' ')
  TAIL_LINES=$(tail -n 6 smoke.log | tr '\n' ' ')
  if [ "$STATUS" = "failure" ]; then
    annotate error "device-smoke (${SHA:0:7}) failed" "smoke.log start: ${HEAD_LINES} | end: ${TAIL_LINES}"
  else
    annotate notice "device-smoke (${SHA:0:7}) log" "smoke.log start: ${HEAD_LINES}"
  fi
elif [ "$STATUS" = "failure" ]; then
  annotate error "device-smoke (${SHA:0:7}) failed before the smoke script ran" \
    "no smoke.log was produced: the emulator step did not reach the script (check the action's own boot phase in this job)"
fi

# ------------------------------------------------------- commit and push once
# `git add -f` first and compare the *index*, not `git status`: publish-ci-log.sh
# decided whether to publish by asking `git status --porcelain .ci-logs`, which
# says nothing about files that ignore rules hide, and a check that cannot see the
# thing it is checking is how a failure becomes silent.
git add -f .ci-logs screenshots 2>/dev/null
if git diff --cached --quiet 2>/dev/null; then
  echo "nothing to publish: the captures, the report and the log are unchanged"
  ls -la screenshots/ 2>/dev/null || true
  annotate warning "device-smoke (${SHA:0:7}) published nothing" \
    "the working tree was unchanged: the smoke script never rewrote the report, so it did not run"
  exit 0
fi

echo "staged for publication:"
git diff --cached --name-only | sed 's/^/  /'

git config user.name "canta-smoke"
git config user.email "smoke@jcversa.invalid"
if ! git commit -q -m "chore(smoke): run ${RUN} evidence (report, captures, log) [skip ci]"; then
  annotate error "device-smoke (${SHA:0:7}): commit failed" "$(git status --porcelain | head -5 | tr '\n' ' ')"
  exit 1
fi

PUSH_ERR=""
for attempt in 1 2 3 4 5; do
  if PUSH_ERR=$(git push origin "HEAD:${BRANCH}" 2>&1); then
    echo "published run ${RUN} evidence to ${BRANCH}"
    annotate notice "device-smoke (${SHA:0:7}) published" "run ${RUN} evidence is committed on ${BRANCH}"
    exit 0
  fi
  echo "push attempt ${attempt} failed:"
  echo "$PUSH_ERR" | sed 's/^/  /'
  git pull --rebase --autostash origin "$BRANCH" >/dev/null 2>&1 || true
  sleep $((attempt * 3))
done

# The push is the one step whose failure leaves no trace on the branch, so its
# reason goes into the annotation - that is how it stays readable.
annotate error "device-smoke (${SHA:0:7}): could not publish" "git push failed after 5 attempts: $(printf '%s' "$PUSH_ERR" | tr '\n' ' ' | cut -c1-400)"
exit 1
