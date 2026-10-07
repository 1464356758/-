#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:?Please set ANDROID_SDK_ROOT}"
bt="$sdk/build-tools/35.0.0"
rm -rf build/classes
mkdir -p build/classes build/package/lib
dependency_cp="vendor/litert/classes.jar:vendor/litert-api/classes.jar"
javac -source 8 -target 8 -classpath "$sdk/platforms/android-35/android.jar:$dependency_cp" -d build/classes src/com/cameraprofile/studio/*.java
"$bt/d8" --lib "$sdk/platforms/android-35/android.jar" --min-api 29 --output build build/classes/com/cameraprofile/studio/*.class vendor/litert/classes.jar vendor/litert-api/classes.jar
"$bt/aapt" package -f -M AndroidManifest.xml -I "$sdk/platforms/android-35/android.jar" -S res -A assets -0 tflite -F build/unsigned.apk
cp build/classes.dex build/package/classes.dex
cp -R vendor/litert/jni/* build/package/lib/
(cd build/package && zip -q -0 -r ../unsigned.apk classes.dex lib)
if [ ! -f build/release.jks ]; then
keytool -genkeypair -keystore build/release.jks -storepass camera-profile-local -keypass camera-profile-local -alias profile -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Camera Profile Studio'
fi
"$bt/zipalign" -P 16 -f 4 build/unsigned.apk build/aligned.apk
rm -f build/final-signed.apk
"$bt/apksigner" sign --ks build/release.jks --ks-pass pass:camera-profile-local --out build/final-signed.apk build/aligned.apk
"$bt/apksigner" verify --verbose build/final-signed.apk
"$bt/zipalign" -c -P 16 4 build/final-signed.apk
"$bt/aapt" dump badging build/final-signed.apk > build/package-info.txt
mv -f build/final-signed.apk CameraProfileStudio-2.2.apk
