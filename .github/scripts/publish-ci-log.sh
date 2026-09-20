#!/usr/bin/env bash
# Commits the .ci-logs/ files produced by a failing CI job back to the branch.
#
# Why this exists: `gh run view --log` and the artifact download endpoints are
# unreachable from some development environments (Termux, restricted sandboxes).
# Without this, a red build there is a mystery; with it, the Gradle failure is one
# `git pull` away. Each job calls this with its own log already written to
# .ci-logs/<job>.txt, and the commit carries [skip ci] so it does not re-trigger CI.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if [ -z "$(git status --porcelain .ci-logs 2>/dev/null)" ]; then
  echo "no log files to publish"
  exit 0
fi

git config user.name "Canta CI"
git config user.email "ci@jcversa.invalid"
git add -f .ci-logs
git commit -m "ci: build log for ${GITHUB_SHA:-local} [skip ci]" >/dev/null

# The branch may have moved while the job ran (a developer pushing a fix is the
# normal case, not the exception). Rebase onto it and retry: a log that never
# lands is a log that does not exist for anyone developing from a restricted
# environment, which is the entire reason this step exists.
BRANCH="${GITHUB_REF_NAME:-main}"
for attempt in 1 2 3; do
  if git push origin "HEAD:${BRANCH}"; then
    echo "published .ci-logs to ${BRANCH}"
    exit 0
  fi
  echo "push attempt ${attempt} rejected - rebasing onto origin/${BRANCH}"
  git pull --rebase --autostash origin "${BRANCH}" || true
  sleep $((attempt * 3))
done
echo "push failed after 3 attempts; log stays in the workspace"
