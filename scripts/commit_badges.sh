#!/usr/bin/env bash
# Commit badge JSON files onto the orphan `badges` branch of the GitHub repo (created on first
# use). Nothing else lives there, so it never touches the release branches (main/26.2/26.1).
# Retries with a fresh clone because two lines' builds can finish at the same moment.
# (Copied from k33bz/sanctuary via k33bz/postbox.)
# Usage: GH_TOKEN=... REPO=owner/name scripts/commit_badges.sh <dir-with-json> "<message>"
set -euo pipefail
src=$1
msg=$2
url="https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
for attempt in 1 2 3 4 5; do
  work=$(mktemp -d)
  if git ls-remote --exit-code --heads "$url" badges >/dev/null 2>&1; then
    git clone -q --depth 1 --branch badges "$url" "$work"
  else
    git -C "$work" init -q -b badges
    git -C "$work" remote add origin "$url"
    printf '# badges\n\nshields.io endpoint data written by CI (scripts/publish_badges.py). Do not edit.\n' > "$work/README.md"
  fi
  cp -r "$src"/. "$work"/
  git -C "$work" add -A
  if git -C "$work" diff --cached --quiet; then
    echo "badges unchanged"
    exit 0
  fi
  git -C "$work" -c user.name="github-actions[bot]" \
      -c user.email="41898282+github-actions[bot]@users.noreply.github.com" commit -q -m "$msg"
  if git -C "$work" push -q origin badges; then
    echo "badges updated: $msg"
    exit 0
  fi
  echo "push raced another badge update, retrying ($attempt)"
  rm -rf "$work"
  sleep $((attempt * 5))
done
echo "::warning::could not update the badges branch"
exit 0
