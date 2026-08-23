#!/bin/sh
# e2e/git-server/seed-and-serve.sh — seed the e2e-test/* bare repos and serve them.
#
# Runs entirely offline: `git` and `git-daemon` are already installed in the image
# (see this directory's Dockerfile), so nothing here touches the network except the
# `git daemon` listener itself. Seeding three tiny local repos takes a fraction of a
# second, so the container's healthcheck (a `git ls-remote` against localhost) should
# pass on its very first attempt.
set -eu

BASE=/srv/git
REPOS="e2e-test/mock-repo e2e-test/mock-frontend e2e-test/dind-repo"

git config --global user.email "e2e@choruskube.local"
git config --global user.name "ChorusKube E2E"
git config --global init.defaultBranch main

mkdir -p "$BASE"
SEED=$(mktemp -d)
cd "$SEED"
git init -q -b main .
printf '# ChorusKube E2E mock repository\n' > README.md
git add README.md
git commit -q -m "Initial commit (e2e git-server seed)"

for r in $REPOS; do
  d="$BASE/$r.git"
  git init -q --bare -b main "$d"
  git config -f "$d/config" receive.denyCurrentBranch ignore
  git push -q "$d" main
  git -C "$d" symbolic-ref HEAD refs/heads/main
  : > "$d/git-daemon-export-ok"
  echo "git-server: seeded $r.git"
done

echo "git-server: serving $BASE (receive-pack enabled) on :9418"
exec git daemon --verbose --reuseaddr --listen=0.0.0.0 --port=9418 \
  --base-path="$BASE" --export-all \
  --enable=upload-pack --enable=receive-pack "$BASE"
