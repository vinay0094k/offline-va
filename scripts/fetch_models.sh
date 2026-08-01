#!/usr/bin/env bash
# Downloads the models + sherpa-onnx AAR and arranges them where the Gradle
# build expects them. Run from the repo root. Idempotent; keeps downloads/
# as a cache.
set -euo pipefail

WHISPER_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
QWEN_URL="https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
PIPER_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar"

mkdir -p downloads

dl() { # url filename
    if [ ! -s "downloads/$2" ]; then
        echo "downloading $2 ..."
        curl -fL --retry 3 -o "downloads/$2.part" "$1"
        mv "downloads/$2.part" "downloads/$2"
    else
        echo "cached: $2"
    fi
}

dl "$WHISPER_URL" ggml-base.en-q5_1.bin
dl "$QWEN_URL"    qwen3.5-0.8b-q4_k_m.gguf
dl "$PIPER_URL"   vits-piper-en_US-amy-low.tar.bz2
dl "$AAR_URL"     sherpa-onnx-1.13.4.aar

ASSETS=app/src/main/assets/models
rm -rf "$ASSETS"
mkdir -p "$ASSETS/whisper" "$ASSETS/llm" "$ASSETS/tts"

cp downloads/ggml-base.en-q5_1.bin  "$ASSETS/whisper/"
cp downloads/qwen3.5-0.8b-q4_k_m.gguf "$ASSETS/llm/"

rm -rf downloads/vits-piper-en_US-amy-low
tar -xjf downloads/vits-piper-en_US-amy-low.tar.bz2 -C downloads/
cp    downloads/vits-piper-en_US-amy-low/en_US-amy-low.onnx "$ASSETS/tts/"
cp    downloads/vits-piper-en_US-amy-low/tokens.txt         "$ASSETS/tts/"
cp -r downloads/vits-piper-en_US-amy-low/espeak-ng-data     "$ASSETS/tts/"

mkdir -p app/libs
cp downloads/sherpa-onnx-1.13.4.aar app/libs/

echo "done. assets:"
du -sh "$ASSETS"/* app/libs/*.aar
