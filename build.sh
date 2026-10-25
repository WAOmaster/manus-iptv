#!/usr/bin/env bash
# bash build.sh [aab]: with "aab" also builds + signs manus-iptv.aab for Google Play (tools/build-aab.sh).
# Builds manus-iptv.apk with no Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner. Run in Git Bash.
set -euo pipefail
cd "$(dirname "$0")"
# Owner-only settings (git-ignored): see local.env.example. Plain environment variables override the file's defaults.
if [ -f local.env ]; then set -a; . ./local.env; set +a; fi
# FLAVOR=play (default): no iptv-org anywhere, the user adds their own playlist. FLAVOR=full: GitHub/sideload build with the opt-in free-list step.
FLAVOR=${FLAVOR:-play}; FEED_URL=${FEED_URL:-}; DEFAULT_IPTVORG=${DEFAULT_IPTVORG:-0}
case "$FLAVOR" in play|full) ;; *) echo "FLAVOR must be play or full"; exit 1;; esac
[[ "$FEED_URL" =~ ^[A-Za-z0-9:/._~%?=\&-]*$ ]] || { echo "FEED_URL has odd characters"; exit 1; }
[ "$FLAVOR" = play ] && DEFAULT_IPTVORG=0
# Play builds never ship the owner's data feed (users add their own); override only with PLAY_FEED_URL.
[ "$FLAVOR" = play ] && FEED_URL=${PLAY_FEED_URL:-}
VERSION_CODE=${VERSION_CODE:-1}; VERSION_NAME=${VERSION_NAME:-1.0.0}
[[ "$VERSION_CODE" =~ ^[0-9]+$ && "$VERSION_NAME" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "bad VERSION_CODE / VERSION_NAME"; exit 1; }
TARGET_SDK=${TARGET_SDK:-$([ "$FLAVOR" = play ] && echo 35 || echo 34)}
SDK=${ANDROID_SDK:-$(cygpath -u "${LOCALAPPDATA:-$HOME/AppData/Local}")/Android/Sdk}
BT=$SDK/build-tools/${BT_VERSION:-36.1.0}; JAR=$SDK/platforms/android-36/android.jar
rm -rf build && mkdir -p build/classes build/dex build/gen/lk/manus/iptv
# DEBUG=1 bash build.sh enables the hidden test intent extras; default is a release build that ignores them
b() { [ "$1" = 1 ] && echo true || echo false; }
printf 'package lk.manus.iptv;\npublic final class Config {\n  public static final boolean DEBUG = %s;\n  public static final boolean PLAY = %s;\n  public static final boolean IPTVORG = %s;\n  public static final String FEED_URL = "%s";\n}\n' "$(b "${DEBUG:-0}")" "$([ "$FLAVOR" = play ] && echo true || echo false)" "$(b "$DEFAULT_IPTVORG")" "$FEED_URL" > build/gen/lk/manus/iptv/Config.java
# Media3 libs (sha256-pinned, git-ignored) are always part of the app; fetched on first build. R8=0 skips shrinking (plain d8, ~3.4 MB).
ls libs/jars/translate-*.jar >/dev/null 2>&1 || bash tools/fetch-libs.sh
LIBS=(libs/jars/*.jar)
CP="$(cygpath -w "$JAR")"; for j in "${LIBS[@]}"; do CP="$CP;$(cygpath -w "$j")"; done
sed -e "s/android:targetSdkVersion=\"[0-9]*\"/android:targetSdkVersion=\"$TARGET_SDK\"/" -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" AndroidManifest.xml > build/AndroidManifest.xml
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" compile --dir libs/res -o build/res-mlkit.zip # ML Kit translate's metadata json + xml
"$BT/aapt2.exe" link -o build/base.apk -I "$JAR" --proguard build/aapt.pro --manifest build/AndroidManifest.xml -A libs/assets --java build/gen --extra-packages com.google.mlkit.nl.translate build/res.zip build/res-mlkit.zip # extra R: the AAR's classes read R.raw / R.xml of their own package
javac -encoding UTF-8 -source 11 -target 11 -classpath "$CP" -d build/classes $(find src -name '*.java') build/gen/lk/manus/iptv/Config.java build/gen/com/google/mlkit/nl/translate/R.java 2>build/javac.log || { cat build/javac.log; echo "javac failed"; exit 1; }
grep -v "bootstrap classpath\|system modules path\|^[0-9]* warning\|unknown enum constant" build/javac.log || true
jar cf build/classes.jar -C build/classes . # one jar: the class list as arguments exceeded the cmd.exe line limit
if [ "${R8:-1}" = 1 ]; then
  { cat libs/proguard-media3.txt build/aapt.pro; echo '-keep class lk.manus.iptv.** { *; }'; echo '-dontwarn androidx.media3.**'; echo '-dontwarn androidx.exifinterface.**'; echo '-dontwarn org.checkerframework.**'; echo '-keep class androidx.collection.** { *; }'; echo '-keep class com.google.mlkit.** { *; }'; echo '-keep class com.google.android.gms.internal.mlkit_** { *; }'; echo '-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }'; echo '-dontwarn androidx.**'; echo '-dontwarn com.google.android.gms.**'; echo '-dontwarn com.google.firebase.**'; echo '-dontwarn com.google.android.datatransport.**'; echo '-dontwarn okhttp3.**'; echo '-dontwarn okio.**'; echo '-dontwarn javax.inject.**'; echo '-dontwarn kotlin.**'; echo '-dontwarn org.jspecify.**'; } > build/rules.pro
  java -cp "$(cygpath -w "$BT/lib/d8.jar")" com.android.tools.r8.R8 --release --min-api 26 --lib "$(cygpath -w "$JAR")" --pg-conf "$(cygpath -w build/rules.pro)" --output "$(cygpath -w build/dex)" "$(cygpath -w build/classes.jar)" $(for j in "${LIBS[@]}"; do cygpath -w "$j"; done)
else
  cmd.exe //c "$(cygpath -w "$BT/d8.bat")" --lib "$(cygpath -w "$JAR")" --output "$(cygpath -w build/dex)" --min-api 26 "$(cygpath -w build/classes.jar)" $(for j in "${LIBS[@]}"; do cygpath -w "$j"; done)
fi
(cd build/dex && jar uf ../base.apk classes*.dex)
# ML Kit native libs, both TV ABIs (armeabi-v7a + arm64-v8a). Stored uncompressed so zipalign can page-align them.
(cd libs/native && jar uf0 ../../build/base.apk lib)
python -I tools/elfalign.py $(find libs/native/lib/arm64-v8a -name '*.so') || echo "WARNING: a 64-bit .so is not 16 KB aligned (Play rejects 64-bit libs like that)"
"$BT/zipalign.exe" -P 16 -f 4 build/base.apk build/aligned.apk
"$BT/zipalign.exe" -c -P 16 4 build/aligned.apk
KS=$HOME/.android/manus-iptv.keystore
mkdir -p "$HOME/.android"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias iptv -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Manus IPTV" >/dev/null
cmd.exe //c "$(cygpath -w "$BT/apksigner.bat")" sign --ks "$(cygpath -w "$KS")" --ks-pass pass:android --out "$(cygpath -w manus-iptv.apk)" "$(cygpath -w build/aligned.apk)"
[ "${1:-}" = aab ] && . tools/build-aab.sh
rm -rf build
echo "built manus-iptv.apk (flavor=$FLAVOR, iptv-org default=$DEFAULT_IPTVORG, feed=${FEED_URL:-none}, targetSdk=$TARGET_SDK, version=$VERSION_NAME/$VERSION_CODE)"
