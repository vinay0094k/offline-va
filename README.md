# Offline Voice Assistant (Android)

A fully on-device voice assistant APK. No server, no internet permission —
the entire pipeline runs on the phone:

```
mic / audio file ──▶ whisper.cpp (STT) ──▶ Qwen3.5-0.8B via llama.cpp ──▶ Piper voice via sherpa-onnx (TTS)
```

## Components

| Stage | Engine | Model | Size |
|---|---|---|---|
| STT | whisper.cpp `v1.9.1` (JNI, `:whisper`) | `ggml-base.en-q5_1.bin` | ~57 MB |
| LLM | llama.cpp `b10217` (JNI, `:llama`) | `Qwen3.5-0.8B-Q4_K_M.gguf` | ~600 MB |
| TTS | sherpa-onnx `1.13.4` (official AAR) | Piper `en_US-amy-low` | ~65 MB |

## Thin APK + model pack (the tradeoff we chose)

The APK contains only code and native libs (~30 MB). The ~720 MB of models
ship separately as **`model-pack-v1.zip`**, which you copy to the phone once
and import via the in-app **Import model pack** button (system file picker →
unzipped into app-private storage; native code needs real file paths).

Why not bake models into assets? A ~750 MB APK bloats install size (Android
would hold the assets *and* the unpacked copy, ~1.5 GB), slows first launch,
and makes the CI artifact painful to download.

Why not a first-run in-app download? That is smoother UX, but it requires the
`INTERNET` permission — and "this app *cannot* phone home" is the point of
the project. We chose to keep the app provably offline: the only permission
is `RECORD_AUDIO`, and the one-time model download happens outside the app.

`scripts/fetch_models.sh` builds the pack (and fetches the sherpa-onnx AAR
the build links against); models are never committed to the repo.

## Getting the APK (GitHub Actions)

Every push runs `.github/workflows/build-apk.yml`, which builds `arm64-v8a`
native code with the NDK and uploads two artifacts.

1. Push this repo to GitHub, open **Actions** → latest `build-apk` run.
2. Download both artifacts: `offline-voice-assistant-debug` (~30 MB APK) and
   `model-pack-v1` (~720 MB; unzip the artifact once on your computer to get
   the inner `model-pack-v1.zip`).
3. Copy the APK to the phone, enable *Install unknown apps*, install.
4. Copy `model-pack-v1.zip` to the phone (USB, `adb push
   model-pack-v1.zip /sdcard/Download/`, or any file transfer).
5. Open the app → **Import model pack** → pick the zip (~1 min). After that
   the zip can be deleted; the app never needs it again. Then: tap
   **Record**, speak, tap **Stop** — or pick an audio file with **File**.

## Building locally instead

Open the project in Android Studio (x86_64 host; the NDK toolchain is not
available for arm64 Linux hosts), run `bash scripts/fetch_models.sh` first
(the build needs the AAR it fetches; the model pack lands in `dist/`), then
`Build → Build APK`.

## Notes / tuning

- Native code targets `armv8.2-a+dotprod+fp16` — any 2019+ arm64 phone.
  For very old devices remove `GGML_CPU_ARM_ARCH` from the two module
  `build.gradle.kts` files.
- Swap models by editing `scripts/fetch_models.sh` plus the file names in
  `ModelPack.REQUIRED` and `MainActivity.initModels` (e.g.
  `ggml-small.en-q5_1.bin` for better STT, a bigger Qwen quant for better
  answers, any other Piper voice for TTS). Bump the pack name and the
  `.models-v1` marker in `ModelPack.kt` so old installs re-import.
- RAM use is roughly 1.2 GB with everything loaded; a 4 GB+ phone is
  recommended.
- Replies are capped at 512 tokens and `<think>` blocks are stripped, keeping
  answers short and speakable.
