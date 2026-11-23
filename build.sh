#!/bin/sh
# Standalone APK build: aapt2 + javac + d8 + zipalign + apksigner (no Gradle)
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
OS="$(uname -s 2>/dev/null || echo Windows)"
case "$OS" in
    MINGW*|MSYS*|CYGWIN*|Windows*)
        SDK="${ANDROID_SDK_ROOT:-C:/Android/sdk}"
        JDK="${JAVA_HOME:-C:/SoftWare/JDK/jdk-17.0.21+9}/bin"
        EXE=".exe"
        SEP=";"
        wpath() { cygpath -w "$1"; }
        ;;
    *)
        SDK="${ANDROID_SDK_ROOT:-/root/toolchains/android/Sdk}"
        if [ -d "/usr/lib/jvm/java-17-openjdk-amd64/bin" ]; then
            JDK="/usr/lib/jvm/java-17-openjdk-amd64/bin"
        else
            JDK="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin"
        fi
        EXE=""
        SEP=":"
        wpath() { printf "%s\n" "$1"; }
        ;;
esac
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
OUT="$ROOT/build"
VERSION_CODE="${DOUYIN_VERSION_CODE:-1002}"
VERSION_NAME="${DOUYIN_VERSION_NAME:-1.0.2}"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

if [ ! -d "$ROOT/libs/media3/jars" ] || [ -z "$(ls -A "$ROOT/libs/media3/jars" 2>/dev/null)" ]; then
    echo "Fetching Media3 1.5.1 dependencies..."
    mkdir -p "$OUT/tools"
    "$JDK/javac$EXE" -encoding UTF-8 -d "$OUT/tools" "$(wpath "$ROOT/tools/FetchMedia3.java")"
    "$JDK/java$EXE" -cp "$(wpath "$OUT/tools")" FetchMedia3 "$(wpath "$ROOT/libs/media3/jars")"
fi

if [ ! -f "$ROOT/libs/media3/deps_dex.jar" ]; then
    echo "Pre-dexing dependencies..."
    wpath "$ROOT/libs/zxing-core-3.5.3.jar" > "$OUT/deps.txt"
    find "$ROOT/libs/media3/jars" -name "*.jar" | while read -r f; do wpath "$f"; done >> "$OUT/deps.txt"
    "$JDK/java$EXE" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
        --lib "$PLATFORM" --min-api 23 --output "$(wpath "$ROOT/libs/media3/deps_dex.jar")" @"$(wpath "$OUT/deps.txt")"
fi

"$BT/aapt2$EXE" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$BT/aapt2$EXE" link \
    -o "$OUT/unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --min-sdk-version 23 \
    --target-sdk-version 28 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    -R "$OUT/res.zip" \
    --java "$OUT/gen" \
    --auto-add-overlay

CP="$(wpath "$PLATFORM")$SEP$(wpath "$ROOT/libs/zxing-core-3.5.3.jar")"
for jar in "$ROOT/libs/media3/jars/"*.jar; do
    CP="$CP$SEP$(wpath "$jar")"
done

find "$ROOT/src" "$OUT/gen" -name "*.java" | while read -r f; do wpath "$f"; done > "$OUT/sources.txt"
"$JDK/javac$EXE" -encoding UTF-8 -source 8 -target 8 \
    -cp "$CP" \
    -d "$OUT/classes" @"$(wpath "$OUT/sources.txt")"

find "$OUT/classes" -name "*.class" | while read -r f; do wpath "$f"; done > "$OUT/classes.txt"
D8_CP="--classpath $(wpath "$ROOT/libs/zxing-core-3.5.3.jar")"
for jar in "$ROOT/libs/media3/jars/"*.jar; do
    D8_CP="$D8_CP --classpath $(wpath "$jar")"
done
"$JDK/java$EXE" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$PLATFORM" $D8_CP --min-api 23 --output "$(wpath "$OUT/app_dex.jar")" @"$(wpath "$OUT/classes.txt")"
"$JDK/java$EXE" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 23 --output "$(wpath "$OUT/dex")" \
    "$(wpath "$OUT/app_dex.jar")" "$(wpath "$ROOT/libs/media3/deps_dex.jar")"

(cd "$OUT/dex" && "$JDK/jar$EXE" uf "$OUT/unsigned.apk" classes*.dex)
"$BT/zipalign$EXE" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$ROOT/debug.keystore" ]; then
    "$JDK/keytool$EXE" -genkeypair -keystore "$ROOT/debug.keystore" \
        -alias androiddebugkey -storepass android -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=DouyinTV Debug,O=DouyinTV,C=CN" >/dev/null 2>&1
fi

APK_VERSIONED="$OUT/DouyinTV-$VERSION_NAME.apk"
"$JDK/java$EXE" -jar "$BT/lib/apksigner.jar" sign \
    --ks "${DOUYIN_KEYSTORE:-$ROOT/debug.keystore}" \
    --ks-pass "${DOUYIN_STORE_PASS:-pass:android}" \
    --key-pass "${DOUYIN_KEY_PASS:-pass:android}" \
    --out "$APK_VERSIONED" "$OUT/aligned.apk"

cp "$APK_VERSIONED" "$OUT/DouyinTV.apk"
"$BT/zipalign$EXE" -c -p 4 "$APK_VERSIONED"
echo "OK: $APK_VERSIONED"
ls -l "$APK_VERSIONED"
