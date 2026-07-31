#!/bin/bash
# Serialize the shared Gradle cache mount + disable fs-watching so 18 parallel
# BuildKit service builds don't corrupt /root/.gradle. Idempotent.
set -eu
REPO=/mnt/d/GMEPay+/code
n=0
for df in "$REPO"/services/*/Dockerfile "$REPO"/simulators/*/Dockerfile; do
  [ -f "$df" ] || continue
  if ! grep -q "sharing=locked" "$df"; then
    sed -i 's#type=cache,target=/root/.gradle#type=cache,target=/root/.gradle,sharing=locked#' "$df"
  fi
  if ! grep -q -- "--no-watch-fs" "$df"; then
    sed -i 's/ --no-daemon/ --no-daemon --no-watch-fs/' "$df"
  fi
  n=$((n+1))
done
echo "processed $n Dockerfiles"
echo "== verify (config-registry RUN gradlew line) =="
grep -nE "sharing=locked|no-watch-fs" "$REPO/services/config-registry/Dockerfile"
echo "== files with sharing=locked =="
grep -l "sharing=locked" "$REPO"/services/*/Dockerfile "$REPO"/simulators/*/Dockerfile | wc -l
