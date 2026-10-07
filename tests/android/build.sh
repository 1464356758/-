#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk="${ANDROID_SDK_ROOT:?}"
bt="$sdk/build-tools/35.0.0"
rm -rf tests/android/build/classes
rm -f tests/android/build/classes.dex tests/android/build/unsigned.apk tests/android/build/aligned.apk
mkdir -p tests/android/build/classes
javac -source 8 -target 8 -classpath "$sdk/platforms/android-35/android.jar:app/build/classes:app/vendor/litert/classes.jar:app/vendor/litert-api/classes.jar" -d tests/android/build/classes tests/android/*.java
"$bt/d8" --min-api 29 --lib "$sdk/platforms/android-35/android.jar" --classpath app/build/classes --output tests/android/build tests/android/build/classes/com/cameraprofile/studio/tests/*.class
"$bt/aapt" package -f -M tests/android/AndroidManifest.xml -I "$sdk/platforms/android-35/android.jar" -A tests/android/assets -F tests/android/build/unsigned.apk
(cd tests/android/build && zip -q unsigned.apk classes.dex)
"$bt/zipalign" -f 4 tests/android/build/unsigned.apk tests/android/build/aligned.apk
rm -f tests/android/build/signed.apk
"$bt/apksigner" sign --ks app/build/release.jks --ks-pass pass:camera-profile-local --out tests/android/build/signed.apk tests/android/build/aligned.apk
"$bt/apksigner" verify tests/android/build/signed.apk
mv -f tests/android/build/signed.apk tests/android/RuntimeTests.apk
