#!/usr/bin/env bash
set -euo pipefail

# Repertoire de ce script = sources du projet (AndroidManifest.xml, res/, src/).
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

# Espace de travail pour le SDK et les artefacts intermediaires.
# Surchargable : BUILD_HOME=/chemin ./build-standalone.sh
BUILD_HOME="${BUILD_HOME:-$SRC_DIR/.build}"

# Outils : pris dans l'environnement s'ils existent, sinon dans BUILD_HOME.
JAVA_HOME="${JAVA_HOME:-$BUILD_HOME/sdk/jdk-17.0.13+11}"
SDK="${ANDROID_SDK_ROOT:-$BUILD_HOME/sdk/android}"
BT="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

OUT="$BUILD_HOME/out"
KS="${KEYSTORE:-$BUILD_HOME/debug.keystore}"
APK_OUT="${APK_OUT:-$SRC_DIR/../ram-cleaner-tv.apk}"

for t in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAVA_HOME/bin/javac"; do
  [ -x "$t" ] || { echo "Outil manquant : $t" >&2; echo "Installe le SDK sous $BUILD_HOME/sdk ou exporte JAVA_HOME/ANDROID_SDK_ROOT/BUILD_TOOLS." >&2; exit 1; }
done

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$SRC_DIR/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$SRC_DIR/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 22 \
  --target-sdk-version 29 \
  --version-code 1 --version-name 1.0 \
  "$OUT/res.zip"

echo "[3/6] javac"
find "$SRC_DIR/src" "$OUT/gen" -name '*.java' > "$OUT/srcs.txt"
javac -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/srcs.txt"

echo "[4/6] d8"
CLASSES=$(find "$OUT/classes" -name '*.class')
"$BT/d8" --release --min-api 22 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" $CLASSES

echo "[5/6] package + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && "$JAVA_HOME/bin/jar" -uf "$OUT/unsigned.apk" classes.dex ) || \
  ( cd "$OUT/dex" && zip -j "$OUT/unsigned.apk" classes.dex )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --min-sdk-version 22 \
  --out "$APK_OUT" \
  "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$APK_OUT"

echo
echo "APK => $APK_OUT"
ls -la "$APK_OUT"
