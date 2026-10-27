#!/usr/bin/env bash
# Installs the Android SDK pieces the studio builds with (compileSdk 35), then runs the unit tests once to warm Gradle.
set -euo pipefail
SDK=/opt/android-sdk
sudo mkdir -p "$SDK" && sudo chown "$(id -u):$(id -g)" "$SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  cd /tmp
  curl -sSLo tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q tools.zip -d "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest" && rm tools.zip
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools" > /dev/null
cd "$(dirname "$0")/.." && echo "sdk.dir=$SDK" > local.properties && chmod +x gradlew
./gradlew --console=plain -q testStudioDebugUnitTest || echo "First test run did not pass; see above."
