#!/bin/bash
# Builds the APK: debug by default, or release with "build-apk.sh release". Gradle refuses non-ASCII
# project paths (such as a checkout under "Darkest Dungeon®"), so the project is copied to
# $DD_BUILD/apk and built there. Run build-natives.sh first.
# Release signing: set DD_KEYSTORE, DD_KEYSTORE_PASSWORD, DD_KEY_ALIAS (and DD_KEY_PASSWORD if the key's
# password differs); without DD_KEYSTORE the release APK is unsigned.
# Output: $DD_BUILD/gradle/app/outputs/apk/{debug,release}/app-*.apk
set -euo pipefail
DD_BUILD=${DD_BUILD:-/c/dd-build}
SDK=${ANDROID_SDK:-$(cygpath "$LOCALAPPDATA")/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-"C:/Program Files/Android/Android Studio/jbr"}
TYPE=debug
[ "${1:-}" = release ] && TYPE=release && shift

cd "$(dirname "$0")"
rm -rf "$DD_BUILD/apk" && mkdir -p "$DD_BUILD/apk"
cp -r app native settings.gradle build.gradle gradle.properties gradlew gradlew.bat gradle "$DD_BUILD/apk/"
echo "sdk.dir=$(cygpath -m "$SDK")" > "$DD_BUILD/apk/local.properties"
cd "$DD_BUILD/apk" && ./gradlew -q -PddBuild="$(cygpath -m "$DD_BUILD")" "assemble${TYPE^}" "$@"
ls -la "$DD_BUILD/gradle/app/outputs/apk/$TYPE/"*.apk
