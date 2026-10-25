#!/usr/bin/env bash
# Fetches Media3 1.9.4 + ML Kit Translate/Language-ID (T40) + deps into libs/ (git-ignored), verifies pinned sha256, unpacks classes.jar + consumer proguard rules.
# build.sh always includes libs/jars/*.jar and runs this script itself when libs/jars is missing. Needs curl, unzip, sha256sum (Git Bash has them).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p libs/dl libs/jars libs/native/lib/armeabi-v7a libs/native/lib/arm64-v8a libs/assets libs/res/raw libs/res/xml
rm -f libs/proguard-media3.txt
while read -r sha url; do
  f=libs/dl/$(basename "$url")
  [ -f "$f" ] || curl -sfL -o "$f" "$url"
  echo "$sha  $f" | sha256sum -c --quiet - || { echo "BAD SHA $f"; exit 1; }
  case $f in
    *.aar) n=$(basename "$f" .aar); unzip -qo -j "$f" classes.jar -d libs/jars && mv libs/jars/classes.jar "libs/jars/$n.jar"
           unzip -p "$f" proguard.txt >> libs/proguard-media3.txt 2>/dev/null || true
           # ML Kit: both TV ABIs (armeabi-v7a + arm64-v8a); models download at runtime. res/ only from translate (metadata json + xml); manifests are merged by hand into AndroidManifest.xml
           unzip -qo -j "$f" 'jni/armeabi-v7a/*.so' -d libs/native/lib/armeabi-v7a 2>/dev/null || true
           unzip -qo -j "$f" 'jni/arm64-v8a/*.so' -d libs/native/lib/arm64-v8a 2>/dev/null || true
           unzip -qo "$f" 'assets/*' -d libs/ 2>/dev/null || true
           case $n in translate-*) unzip -qo -j "$f" 'res/raw/*' -d libs/res/raw; unzip -qo -j "$f" 'res/xml/*' -d libs/res/xml ;; esac ;;
    *.jar) cp "$f" libs/jars/ ;;
  esac
done <<'LIST'
6bd4c7c7476f8260cd3bdbb81183583e93fc9f790c27dea7dc314181cbf87aa0 https://dl.google.com/android/maven2/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1.aar
1e343917ebf27ba96fe4dc52b1cad7fd32b738fbc6355bb6cd5b3b305d7212d0 https://dl.google.com/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar
cbfc3906b19b8f55dd7cfd6dfe0aa4532e834250d7f080bd8d211a3e246b59cb https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar
a1f8a4bafe5a77232d333356843e194599b30837574a7f4be1063daaaaab102c https://repo1.maven.org/maven2/com/google/guava/guava/33.4.8-android/guava-33.4.8-android.jar
7a75d0200839800feae0cc15bfc6166166475994515ef95183b27abe0527c188 https://dl.google.com/android/maven2/androidx/media3/media3-common/1.9.4/media3-common-1.9.4.aar
f73cea4fe8a2bf9eb6462bfb7a547d1aaf55395d0007eed6fadb129b56927c0d https://dl.google.com/android/maven2/androidx/media3/media3-container/1.9.4/media3-container-1.9.4.aar
de88a2ca8fd1efb1dbe7d8cd4c728fca15fafb629442514a689dea1788817637 https://dl.google.com/android/maven2/androidx/media3/media3-database/1.9.4/media3-database-1.9.4.aar
ccaf5530a31e64dffe2a803feeeb654e7e81fc4a2597f6d929ce7a9aba7ff13e https://dl.google.com/android/maven2/androidx/media3/media3-datasource/1.9.4/media3-datasource-1.9.4.aar
2337461b4ed4ec3cd32330cd530d9b471d5d45b76410f6e0d66f1331c9a0bc08 https://dl.google.com/android/maven2/androidx/media3/media3-decoder/1.9.4/media3-decoder-1.9.4.aar
f362b5e0afa17654234ecc6d61c4d57b7e299b3fea550f4409c7d2b1be7283cd https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/1.9.4/media3-exoplayer-1.9.4.aar
4ca85490b83d81c38cbe86db3313784d2f7fc5d8d0810c42210fc56ae328e7a5 https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer-dash/1.9.4/media3-exoplayer-dash-1.9.4.aar
f8540171a92c61b3725166a946e9935dd7d083ef5c1f4da8131c74472ab84c36 https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer-hls/1.9.4/media3-exoplayer-hls-1.9.4.aar
fb1d260cea679efd3568b4b4b02c9aa1dd84cef847cd50f93f50ca02e59cb20b https://dl.google.com/android/maven2/androidx/media3/media3-extractor/1.9.4/media3-extractor-1.9.4.aar
16d77e8c443fa55fe9a6074d00445d520ca5c9f913cefdbf4828356255214e42 https://dl.google.com/android/maven2/androidx/collection/collection/1.2.0/collection-1.2.0.jar
8830f2e8a5e434cbc5e4bd18133073b6e8518a8ce8ec2ce6f3b273c91d9664c2 https://dl.google.com/android/maven2/com/google/firebase/firebase-annotations/16.0.0/firebase-annotations-16.0.0.jar
8bda3ee3a88887d54f6679fb6b6cd788629f73234ac91c8bbed924e721ec85b8 https://dl.google.com/android/maven2/androidx/core/core/1.9.0/core-1.9.0.aar
e62f73ba1c7f5d15f5d86d8e9a298c42ccd0648e7ce60b9e312f2ecd123b5ed0 https://dl.google.com/android/maven2/com/google/android/datatransport/transport-api/2.2.1/transport-api-2.2.1.aar
b22a14d560a590dc875e902424b29a13a02d1faf297b7c39a9007967ffadccbd https://dl.google.com/android/maven2/com/google/android/datatransport/transport-backend-cct/2.3.3/transport-backend-cct-2.3.3.aar
d83ad23c134429691161c92476a3c83df236cd1f8badbfd4f00723b4eccd741c https://dl.google.com/android/maven2/com/google/android/datatransport/transport-runtime/2.2.6/transport-runtime-2.2.6.aar
59a5c0c2da12311d75d965ce1f419498536b1a167fb28ff7dfc2dfd9cefa4157 https://dl.google.com/android/maven2/com/google/android/gms/play-services-base/18.5.0/play-services-base-18.5.0.aar
ce5c936fd66814b3602f5c6a5e929911ff973d4b05f6de9996da596bef97cad2 https://dl.google.com/android/maven2/com/google/android/gms/play-services-basement/18.4.0/play-services-basement-18.4.0.aar
7f2aaa8f502068eaf54356ca92aec04271d6e7c416c52c45c0d23440fcbd1654 https://dl.google.com/android/maven2/com/google/android/gms/play-services-tasks/18.2.0/play-services-tasks-18.2.0.aar
82e91aa5ed186eedaf1f2210f5d241ba6e4456cf0c32949d5cdbe152b0e2e88b https://dl.google.com/android/maven2/com/google/firebase/firebase-components/16.1.0/firebase-components-16.1.0.aar
8f89a74298bbccc2c543385208a2f0c67f603ce67e6ec21cc772e98e688f7cf5 https://dl.google.com/android/maven2/com/google/firebase/firebase-encoders/16.1.0/firebase-encoders-16.1.0.jar
8295c755bcadc87a2aaee5b9e568f27fd138fd45ff1159a8ee747cf4c3a38d85 https://dl.google.com/android/maven2/com/google/firebase/firebase-encoders-json/17.1.0/firebase-encoders-json-17.1.0.aar
66408a08dbb2465687ff5dd0c8f6410b56549ce3ecf449d9096f3712282131d0 https://dl.google.com/android/maven2/com/google/mlkit/common/18.11.0/common-18.11.0.aar
3610374edfab8537c795195d53645c53923afa1acbe80ef25f5fc8551076a8f7 https://dl.google.com/android/maven2/com/google/mlkit/language-id/17.0.6/language-id-17.0.6.aar
8fc9c3e380076bdc7b9bf5ade6f217422d509df0d66e05663e94902f3847c3b8 https://dl.google.com/android/maven2/com/google/mlkit/language-id-common/16.1.0/language-id-common-16.1.0.aar
b6194f7b42034309cf8299784b2d5d70a82a2e9287fbe1650dea4d1f3ad1fe55 https://dl.google.com/android/maven2/com/google/mlkit/translate/17.0.3/translate-17.0.3.aar
8f25394193dffd55b11d70eede78086afef736174ccdf74a1e3f6f51b1a2e4aa https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/3.0.0/okhttp-3.0.0.jar
114bdc1f47338a68bcbc95abf2f5cdc72beeec91812f2fcd7b521c1937876266 https://repo1.maven.org/maven2/com/squareup/okio/okio/1.6.0/okio-1.6.0.jar
91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar
7bad7a188804adea6fa1f35d5ef99b705f20bd93ecadde484760ff86b535fefc https://dl.google.com/android/maven2/androidx/lifecycle/lifecycle-common/2.0.0/lifecycle-common-2.0.0.jar
LIST
# bundletool (only for tools/build-aab.sh; needs Java 17)
BT_JAR=libs/dl/bundletool-all-1.18.1.jar
[ -f "$BT_JAR" ] || curl -sfL -o "$BT_JAR" https://github.com/google/bundletool/releases/download/1.18.1/bundletool-all-1.18.1.jar
echo "675786493983787ffa11550bdb7c0715679a44e1643f3ff980a529e9c822595c  $BT_JAR" | sha256sum -c --quiet - || { echo "BAD SHA $BT_JAR"; exit 1; }
