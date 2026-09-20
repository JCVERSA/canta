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
git push origin "HEAD:${GITHUB_REF_NAME:-main}" || echo "push failed; log stays in the workspace"
