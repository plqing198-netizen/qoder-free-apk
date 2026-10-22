#!/bin/bash
# Qoder 白嫖 APK 一键构建脚本（Linux arm64）
set -e
cd "$(dirname "$0")"

# 工具路径（按需修改）
AJ=${AJ:-/sdcard/MT2/qoder-apk/tools/platform33/android-13/android.jar}
D8=${D8:-/sdcard/MT2/qoder-apk/tools/build-tools/android-14/d8}
AAPT2=/usr/bin/aapt2
APKSIGNER=/usr/bin/apksigner
ZIPALIGN=/usr/bin/zipalign
KS=${KS:-/tmp/qoder.keystore}
KSPASS=${KSPASS:-qoder123}

rm -rf build && mkdir -p build/classes build/gen

# 生成应用图标（纯脚本，无需二进制资源）
mkdir -p res/mipmap
python3 - <<'PYICON'
import struct, zlib
w = h = 192
def chunk(t, d):
    c = struct.pack('>I', len(d)) + t + d
    c += struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return c
ihdr = struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)
raw = b''
for y in range(h):
    raw += b'\x00'
    for x in range(w):
        raw += bytes([37, 99, 235, 255])
idat = zlib.compress(raw)
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b'')
open('res/mipmap/ic_launcher.png','wb').write(png)
PYICON

echo "[1/5] 编译 Java..."
javac -g:none -source 8 -target 8 -bootclasspath "$AJ" \
  -d build/classes $(find src -name '*.java')

echo "[2/5] d8 转 dex..."
bash "$D8" --release --lib "$AJ" --output build/ \
  $(find build/classes -name '*.class')

echo "[3/5] aapt2 编译资源 + 链接..."
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml \
  -A assets build/res.zip --java build/gen

echo "[4/5] 打包 + 对齐..."
cd build
cp base.apk unsigned.apk
zip -q unsigned.apk classes.dex
"$ZIPALIGN" -f 4 unsigned.apk aligned.apk

echo "[5/5] 签名..."
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias qoder -keyalg RSA \
    -keysize 2048 -validity 3650 -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=Qoder, OU=dev, O=qoder, L=cn, ST=cn, C=CN"
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:"$KSPASS" \
  --key-pass pass:"$KSPASS" --out qoder-app.apk aligned.apk

echo ""
echo "✅ 构建完成：build/qoder-app.apk"
ls -la qoder-app.apk
