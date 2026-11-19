#!/bin/sh
# homr (AGPL-3.0, https://github.com/liebharc/homr) nota okuma modellerini indirir,
# SHA-256 ile doğrular ve uygulamanın assets klasörüne koyar. Modeller depoya eklenmez.
set -eu
URL=${HOMR_URL:-https://github.com/liebharc/homr/releases/download/onnx_checkpoints}
ENC=${HOMR_ENCODER:-encoder_pytorch_model_566-ccd1cb311c368b5711dd238d2464c615397a3f2b}
DEC=${HOMR_DECODER:-decoder_pytorch_model_566-ccd1cb311c368b5711dd238d2464c615397a3f2b}
DEST="$(dirname "$0")/../app/src/main/assets/homr"
TMP=$(mktemp -d)
mkdir -p "$DEST"
curl -fsSL -o "$TMP/enc.zip" "$URL/$ENC.zip"
curl -fsSL -o "$TMP/dec.zip" "$URL/$DEC.zip"
cat > "$TMP/sums" <<SUMS
1914488f396bd4d45586c97270945e1394c59488a16e2af38db103de5f3b61fa  $TMP/enc.zip
ba267f67b6d7b5c1ec5ed6f3b10dc0a6ab1d2a6408df83e9d691c9ddbddbf4e1  $TMP/dec.zip
SUMS
sha256sum -c "$TMP/sums"
unzip -o -q "$TMP/enc.zip" -d "$TMP" && mv "$TMP/$ENC.onnx" "$DEST/encoder.onnx"
unzip -o -q "$TMP/dec.zip" -d "$TMP" && mv "$TMP/$DEC.onnx" "$DEST/decoder.onnx"
cat > "$TMP/sums2" <<SUMS
ab4135c1ad65883bf9a63f19cb600911d69f3ef5e8cb9deaccfa562b326db38c  $DEST/encoder.onnx
c6075364b02bd2cc7b47e428d518fa5c5e84412ae62bf38542ea4e567afbed47  $DEST/decoder.onnx
SUMS
sha256sum -c "$TMP/sums2"
rm -rf "$TMP"
ls -la "$DEST"
