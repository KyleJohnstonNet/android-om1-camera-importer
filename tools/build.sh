#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Prefer the isolated tools used for this checkout; otherwise use installed tools.
if [[ -d "$PWD/.local-tools/jdk/Contents/Home" ]]; then
    export JAVA_HOME="$PWD/.local-tools/jdk/Contents/Home"
fi
if [[ -d "$PWD/.local-tools/android-sdk" ]]; then
    export ANDROID_HOME="$PWD/.local-tools/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.local-tools/gradle-home}"
if [[ $# -eq 0 ]]; then
    set -- :core:test :app:assembleDebug :app:lintDebug :camera-helper:assembleDebug :camera-helper:lintDebug
fi
exec ./gradlew --no-daemon "$@"
