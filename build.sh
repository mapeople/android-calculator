#!/usr/bin/env bash
# 无 Gradle 的构建脚本：aapt2 + javac + d8 + zipalign + apksigner
# 依赖：JDK 17、Android SDK build-tools 34.0.0、platforms/android-34
# 用法：./build.sh
set -euo pipefail

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk-arm64}"
: "${ANDROID_HOME:=/opt/android-sdk}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"

APP_NAME="Calculator"
VERSION="2.1.0"
MIN_SDK=21
TARGET_SDK=34

rm -rf build
mkdir -p build/compiled build/gen build/classes build/dex

echo "==> 1/6 编译资源 (aapt2 compile)"
aapt2 compile --dir app/src/main/res -o build/compiled/res.zip

echo "==> 2/6 链接资源与清单 (aapt2 link)"
aapt2 link \
  -o build/unsigned.apk \
  -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  build/compiled/res.zip

echo "==> 3/6 编译 Java (javac)"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$PLATFORM:$BT/core-lambda-stubs.jar" \
  -d build/classes \
  app/src/main/java/com/mapeople/calculator/*.java \
  build/gen/com/mapeople/calculator/R.java

echo "==> 4/6 转 dex (d8)"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api "$MIN_SDK" \
  --lib "$PLATFORM" \
  --output build/dex \
  $(find build/classes -name "*.class")

echo "==> 5/6 打包 + 对齐"
python3 - <<'PY'
import zipfile
src = 'build/unsigned.apk'
dst = 'build/stage.apk'
zin = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    zout.writestr(item, zin.read(item.filename))
zin.close()
zout.write('build/dex/classes.dex', 'classes.dex')
zout.close()
print('   stage.apk 写入完成')
PY
zipalign -f 4 build/stage.apk build/aligned.apk

echo "==> 6/6 签名 (apksigner)"
KS="keystore/calc-release.jks"
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KS" -storetype JKS \
    -alias calc -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass calc123456 -keypass calc123456 \
    -dname "CN=Calculator, OU=Personal, O=mapeople, L=Beijing, ST=Beijing, C=CN" >/dev/null 2>&1
  echo "   已生成新签名证书 $KS"
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:calc123456 --key-pass pass:calc123456 --ks-key-alias calc \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "build/$APP_NAME-$VERSION.apk" build/aligned.apk

"$JAVA" -jar "$BT/lib/apksigner.jar" verify --print-certs "build/$APP_NAME-$VERSION.apk" | head -3

echo
echo "构建完成: build/$APP_NAME-$VERSION.apk"
ls -lh "build/$APP_NAME-$VERSION.apk"
