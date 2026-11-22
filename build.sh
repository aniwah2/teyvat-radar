#!/bin/bash
# Compila o Teyvat Radar sem Android Studio.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${SDK:-$(cd .. && pwd)/sdk}
JAR=$SDK/android-35.jar
RES_JAR=$SDK/android-34.jar  # aapt do Ubuntu nao le o 35
rm -rf out && mkdir -p out/classes out/gen

aapt package -f -m -J out/gen -M AndroidManifest.xml -S res -I "$RES_JAR" -F out/base.apk

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$JAR" -d out/classes \
  $(find src out/gen -name '*.java')

dalvik-exchange --dex --min-sdk-version=26 --output=out/classes.dex out/classes

cp out/base.apk out/unsigned.apk
(cd out && aapt add -f unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 out/unsigned.apk out/aligned.apk

[ -f radar.keystore ] || keytool -genkeypair -keystore radar.keystore -storepass teyvat123 \
  -keypass teyvat123 -alias radar -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Teyvat Radar" >/dev/null 2>&1
apksigner sign --ks radar.keystore --ks-pass pass:teyvat123 --ks-key-alias radar \
  --out TeyvatRadar.apk out/aligned.apk
apksigner verify -v TeyvatRadar.apk | head -4
ls -la TeyvatRadar.apk
