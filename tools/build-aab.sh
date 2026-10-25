#!/usr/bin/env bash
# Sourced by `bash build.sh aab` (needs build/ from that run). Packs the base module, runs bundletool, signs with the UPLOAD key, verifies.
# Upload key: created once (strong random password) and recorded in git-ignored local.env as UPLOAD_KS / UPLOAD_KS_PASS / UPLOAD_ALIAS. Never commit them.
BTJAR=libs/dl/bundletool-all-1.18.1.jar
[ -f "$BTJAR" ] || bash tools/fetch-libs.sh
bt() { java -jar "$(cygpath -w "$BTJAR")" "$@"; }
if [ -z "${UPLOAD_KS_PASS:-}" ]; then
  UPLOAD_KS=$HOME/.android/manus-iptv-upload.jks; UPLOAD_ALIAS=upload
  UPLOAD_KS_PASS=$(openssl rand -base64 33 | tr -dc 'A-Za-z0-9' | head -c 32)
  [ ! -f "$UPLOAD_KS" ] || { echo "$UPLOAD_KS exists but UPLOAD_KS_PASS is not in local.env: restore the password first"; exit 1; }
  export UPLOAD_KS_PASS
  keytool -genkeypair -keystore "$(cygpath -w "$UPLOAD_KS")" -storepass:env UPLOAD_KS_PASS -keypass:env UPLOAD_KS_PASS -alias "$UPLOAD_ALIAS" -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Manus IPTV upload" >/dev/null
  printf '\n# Play UPLOAD key (T43). BACK UP the .jks and this password: losing them means a Play support reset.\nUPLOAD_KS=%s\nUPLOAD_KS_PASS=%s\nUPLOAD_ALIAS=%s\n' "$UPLOAD_KS" "$UPLOAD_KS_PASS" "$UPLOAD_ALIAS" >> local.env
  echo "NEW upload key: $UPLOAD_KS (password in local.env). BACK BOTH UP; never commit them."
fi
export UPLOAD_KS_PASS
mkdir -p build/m/manifest build/m/dex
"$BT/aapt2.exe" link --proto-format -o build/proto.apk -I "$JAR" --manifest build/AndroidManifest.xml -A libs/assets build/res.zip build/res-mlkit.zip
(cd build/m && unzip -qo ../proto.apk && mv AndroidManifest.xml manifest/ && cp ../dex/classes*.dex dex/ && cp -r ../../libs/native/lib .)
echo '{"optimizations":{"uncompressNativeLibraries":{"enabled":true,"alignment":"PAGE_ALIGNMENT_16K"}}}' > build/config.json
(cd build/m && jar cfM ../base.zip .)
rm -f manus-iptv.aab
bt build-bundle --modules="$(cygpath -w build/base.zip)" --config="$(cygpath -w build/config.json)" --output="$(cygpath -w manus-iptv.aab)"
jarsigner -keystore "$(cygpath -w "$UPLOAD_KS")" -storepass:env UPLOAD_KS_PASS -sigalg SHA256withRSA -digestalg SHA-256 "$(cygpath -w manus-iptv.aab)" "$UPLOAD_ALIAS" >/dev/null
jarsigner -verify "$(cygpath -w manus-iptv.aab)" | head -1
bt validate --bundle="$(cygpath -w manus-iptv.aab)" && echo "bundletool validate OK"
rm -f build/u.apks; printf %s "$UPLOAD_KS_PASS" > build/ks.pw # build/ is deleted at the end
bt build-apks --bundle="$(cygpath -w manus-iptv.aab)" --mode=universal --output="$(cygpath -w build/u.apks)" --ks="$(cygpath -w "$UPLOAD_KS")" --ks-pass=file:"$(cygpath -w build/ks.pw)" --ks-key-alias="$UPLOAD_ALIAS"
(cd build && unzip -qo u.apks universal.apk && cp universal.apk ../manus-iptv-universal.apk)
bt dump manifest --bundle="$(cygpath -w manus-iptv.aab)" | grep -E "versionCode|versionName|targetSdk|minSdk"
"$BT/aapt2.exe" dump badging manus-iptv-universal.apk | grep -E "^package|sdkVersion|targetSdk|native-code|leanback|banner"
ls -l manus-iptv.aab
