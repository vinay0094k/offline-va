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

Models are **not** committed to the repo. `scripts/fetch_models.sh` downloads
them into `app/src/main/assets/models/` before the Gradle build; on first app
launch they are copied to internal storage (native code needs real file paths).

## Getting the APK (GitHub Actions)

Every push runs `.github/workflows/build-apk.yml`, which fetches the models,
builds `arm64-v8a` native code with the NDK, and uploads the debug APK.

1. Push this repo to GitHub.
2. Open the **Actions** tab → latest `build-apk` run → download the
   `offline-voice-assistant-debug` artifact (~700 MB, contains the APK).
3. Copy the APK to the phone, enable *Install unknown apps*, install.
4. First launch unpacks models (~1 min), then: tap **Record**, speak, tap
   **Stop** — or pick an audio file with **File**.

## Building locally instead

Open the project in Android Studio (x86_64 host; the NDK toolchain is not
available for arm64 Linux hosts), run `bash scripts/fetch_models.sh` first,
then `Build → Build APK`.

## Notes / tuning

- Native code targets `armv8.2-a+dotprod+fp16` — any 2019+ arm64 phone.
  For very old devices remove `GGML_CPU_ARM_ARCH` from the two module
  `build.gradle.kts` files.
- Swap models by editing `scripts/fetch_models.sh` and the file names in
  `MainActivity.initModels` (e.g. `ggml-small.en-q5_1.bin` for better STT, a
  bigger Qwen quant for better answers, any other Piper voice for TTS).
- RAM use is roughly 1.2 GB with everything loaded; a 4 GB+ phone is
  recommended.
- Replies are capped at 512 tokens and `<think>` blocks are stripped, keeping
  answers short and speakable.
