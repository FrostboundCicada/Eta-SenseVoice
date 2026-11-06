#!/usr/bin/env bash
# 下载本地语音识别运行时：sherpa-onnx 官方预编译 AAR（v2.0.1，49 MB，含 arm64-v8a 原生库）。
# 该 AAR 不在 Maven 中央仓库，只在 GitHub Release 里发布，因此由本脚本拉取到 app/libs。
# SenseVoice 模型文件（model.int8.onnx + tokens.txt）不在这里下载，由 App 内的「本地模型」从
# HuggingFace csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 拉取或从本地文件夹导入。
set -euo pipefail

VERSION="2.0.1"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"
SHA256="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/libs/sherpa-onnx-${VERSION}.aar"

if [ -f "$DEST" ] && [ "$(shasum -a 256 "$DEST" 2>/dev/null | cut -d' ' -f1 || sha256sum "$DEST" | cut -d' ' -f1)" = "$SHA256" ]; then
    echo "已存在且校验通过：$DEST"
    exit 0
fi

mkdir -p "$(dirname "$DEST")"
TMP="$DEST.part"
echo "下载 $URL"
curl -fL --retry 3 --connect-timeout 20 -o "$TMP" "$URL"

ACTUAL="$(shasum -a 256 "$TMP" 2>/dev/null | cut -d' ' -f1 || sha256sum "$TMP" | cut -d' ' -f1)"
if [ "$ACTUAL" != "$SHA256" ]; then
    rm -f "$TMP"
    echo "校验失败：期望 $SHA256，实际 $ACTUAL" >&2
    exit 1
fi

mv "$TMP" "$DEST"
echo "完成：$DEST"
