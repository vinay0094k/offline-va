<p align="center">
  <img src="docs/logo.svg" width="140" alt="Offline Voice Assistant logo — a microphone standing on a chip">
</p>

# Offline Voice Assistant (Android)

A voice assistant that runs **completely on your phone**. No server, no
account, no internet — the app does not even have permission to go online.

You speak → the phone turns your speech into text → a small AI model writes
a reply → the phone reads the reply out loud. All of it happens on the
device:

```
mic / audio file ──▶ whisper.cpp (speech to text) ──▶ Qwen3.5-0.8B (the "brain") ──▶ Piper (text to speech)
```

## What's inside

| Job | Engine | Model file | Size |
|---|---|---|---|
| Speech → text | whisper.cpp `v1.9.1` | `ggml-base.en-q5_1.bin` | ~57 MB |
| Thinking / replies | llama.cpp `b10217` | `Qwen3.5-0.8B-Q4_K_M.gguf` | ~508 MB |
| Text → speech | sherpa-onnx `1.13.4` | Piper voice `en_US-amy-low` | ~65 MB |

## Why the app and the models are two separate downloads

The AI models are big (~640 MB together), so they are **not** packed inside
the APK.
Instead you download two things:

1. **The app** — a small APK (~18 MB download).
2. **The model pack** — one zip file (`model-pack-v1.zip`, ~640 MB) that you
   copy to the phone once. The app imports it on first use and never needs
   it again.

Why this way? Putting the models inside the APK made it a ~660 MB install
that was slow to download and used double the storage. The other option —
letting the app download the models itself — would need internet
permission, and the whole point of this app is that it *cannot* go online.
So: small app + one manual model download. That is the tradeoff we chose.

## How to install (from GitHub Actions)

Every push to this repo builds the app automatically.

1. Open the repo's **Actions** tab → click the latest `build-apk` run.
2. Download both artifacts at the bottom of the page:
   - `offline-voice-assistant-debug` — the APK
   - `model-pack-v1` — the models
3. Unzip each downloaded file once on your computer. You get
   `app-debug.apk` and `model-pack-v1.zip`.
4. Copy `app-debug.apk` to the phone and install it (allow *Install
   unknown apps* when asked).
5. Copy `model-pack-v1.zip` to the phone too — USB cable, or
   `adb push model-pack-v1.zip /sdcard/Download/`.
6. Open the app, tap **Import model pack**, and pick the zip. The import
   takes about a minute. After that you can delete the zip from the phone.
7. Done. Tap **Record**, speak, tap **Stop** — or answer from an audio
   file with **File**. The reply appears as text; tap **🔊** next to it to
   hear it spoken. **＋ New chat** starts a fresh conversation and
   **☰ Chats** reopens old ones — chats are saved only on your phone, in
   the app's private storage.

The app will ask for microphone access (and storage/media access only to
pick the zip / audio file — via the system file picker, nothing more).

## Building it yourself

Open the project in Android Studio on a normal x86_64 computer (the Android
NDK does not run on arm64 Linux). First run:

```bash
bash scripts/fetch_models.sh
```

This downloads the models, creates `dist/model-pack-v1.zip`, and fetches a
library the build needs. Then build the APK the usual way
(`Build → Build APK` or `./gradlew :app:assembleDebug`).

## Good to know

- **Phone requirements:** any 64-bit Android phone from ~2019 or newer,
  with 4 GB+ RAM (the app uses about 1.2 GB while running). Storage: you
  need ~1.5 GB free during import (the zip + the unpacked models); after
  import you can delete the zip and reclaim ~640 MB, leaving ~650 MB in
  use.
- **Very old phones:** if the app crashes on start, remove
  `GGML_CPU_ARM_ARCH` from `whisper/build.gradle.kts` and
  `llama/build.gradle.kts` and rebuild.
- **Want different models?** Change the download links in
  `scripts/fetch_models.sh` and the matching file names in
  `ModelPack.REQUIRED` and `MainActivity.initModels`. For example, a bigger
  Whisper model hears better, and a bigger Qwen model gives smarter
  answers. Also bump `model-pack-v1` → `v2` (and the `.models-v1` marker in
  `ModelPack.kt`) so phones with the old pack know to re-import.
- **Short answers by design:** replies are limited to 512 tokens and the
  model's internal "thinking" text is removed, so answers stay short and
  natural to listen to.
