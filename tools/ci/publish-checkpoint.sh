#!/usr/bin/env bash
set -euo pipefail
message="$1"
shift
if [ "$#" -eq 0 ]; then
  echo "publish_checkpoint: at least one path is required" >&2
  exit 2
fi
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A -- "$@"
if git diff --cached --quiet; then
  echo "CHECKPOINT_UNCHANGED $message"
  exit 0
fi
git commit -m "$message"
for attempt in 1 2 3 4; do
  git fetch origin main
  if ! git merge-base --is-ancestor origin/main HEAD; then
    if ! git rebase origin/main; then
      git rebase --abort || true
      echo "::error::Checkpoint rebase conflict: $message"
      exit 1
    fi
  fi
  if git push origin HEAD:main; then
    echo "CHECKPOINT_PUBLISHED $message"
    exit 0
  fi
  git fetch origin main
  if git merge-base --is-ancestor HEAD origin/main; then
    echo "CHECKPOINT_PUSH_CONFIRMED $message"
    exit 0
  fi
  sleep "$((attempt * 3))"
done
echo "::error::Could not publish checkpoint: $message"
exit 1
