#!/usr/bin/env bash
# Downloads the models, packs them into dist/model-pack-v1.zip (the companion
# zip the app imports on first launch), and fetches the sherpa-onnx AAR the
# Gradle build links against. Models are NOT baked into the APK. Run from the
# repo root. Idempotent; keeps downloads/ as a cache.
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

# The AAR is a build-time dependency of :app.
mkdir -p app/libs
cp downloads/sherpa-onnx-1.13.4.aar app/libs/

# Drop any assets left over from the old models-in-APK layout.
rm -rf app/src/main/assets/models

# Stage the same tree the app expects under filesDir/models after import.
STAGE=build/model-pack/models
rm -rf build/model-pack dist
mkdir -p "$STAGE/whisper" "$STAGE/llm" "$STAGE/tts" dist

cp downloads/ggml-base.en-q5_1.bin    "$STAGE/whisper/"
cp downloads/qwen3.5-0.8b-q4_k_m.gguf "$STAGE/llm/"

rm -rf downloads/vits-piper-en_US-amy-low
tar -xjf downloads/vits-piper-en_US-amy-low.tar.bz2 -C downloads/
cp    downloads/vits-piper-en_US-amy-low/en_US-amy-low.onnx "$STAGE/tts/"
cp    downloads/vits-piper-en_US-amy-low/tokens.txt         "$STAGE/tts/"
cp -r downloads/vits-piper-en_US-amy-low/espeak-ng-data     "$STAGE/tts/"

# -0 (store): the gguf/onnx payloads barely deflate; skipping compression
# keeps packing and the on-phone import fast.
(cd build/model-pack && zip -0 -r -q ../../dist/model-pack-v1.zip models)

# Real sizes, so the README table can stay honest.
echo "done:"
ls -lh "$STAGE"/whisper/* "$STAGE"/llm/* "$STAGE"/tts/*.onnx
du -sh "$STAGE"/tts/espeak-ng-data dist/model-pack-v1.zip app/libs/*.aar
