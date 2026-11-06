#!/usr/bin/env bash
# Mayo TV's core comes only from its own fork (core-fork.properties: repo, ref), built from source.
#
# Checks the fork out next to the job and writes owntv.corePath into ~/.gradle/gradle.properties, so every
# later ./gradlew in the job builds against that source (settings.gradle.kts refuses to build without it).
# With --test it also runs core's and player-core's unit tests first.
#
# Every workflow that runs Gradle calls this before its first ./gradlew, so the tests, lint, the smoke
# test and the APK all see the same core.
set -euo pipefail
cd "$(dirname "$0")/../.."

REPO=$(sed -n 's/^repo=//p' core-fork.properties | tr -d '\r')
REF=$(sed -n 's/^ref=//p' core-fork.properties | tr -d '\r')
if [ -z "$REPO" ] || [ -z "$REF" ]; then
  echo "::error::core-fork.properties needs repo= and ref= (Mayo TV builds only against its core fork)."
  exit 1
fi

CORE="${RUNNER_TEMP:-/tmp}/owntv-core"
rm -rf "$CORE"
git clone --quiet --depth 1 --branch "$REF" "https://github.com/$REPO.git" "$CORE"
echo "::notice::Core from $REPO@$REF ($(git -C "$CORE" rev-parse --short HEAD))"

mkdir -p "$HOME/.gradle"
echo "owntv.corePath=$CORE" >> "$HOME/.gradle/gradle.properties"

if [ "${1:-}" = "--test" ]; then
  (cd "$CORE" && chmod +x ./gradlew && ./gradlew :core:testDebugUnitTest :player-core:testDebugUnitTest --stacktrace)
fi
