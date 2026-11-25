# Nexus Vision OS

Android spatial computing platform, in early development.

## What exists today
| Area | Status |
|---|---|
| Voice engine: ElevenLabs HTTP streaming TTS and realtime Scribe STT | Implemented; protocol logic unit tested with fakes |
| Local voice activity detection and barge-in | Implemented; needs device testing |
| Android SpeechRecognizer / TextToSpeech fallback | Implemented; needs device testing |
| API keys encrypted with Android Keystore | Implemented; needs device testing |
| Separate AI model setting (OpenAI-compatible streaming) | Implemented; parser tested, no live provider tested |
| Handheld camera preview and basic device report (CameraX) | Implemented; needs device testing |
| GPU renderer, ARCore, spatial windows, Liquid Glass, hand tracking, keyboard, built-in apps, tool calling, wake word, stereo mode | **Not started** |
| Signed release APK and AAB | **Not started** (development APK only) |

No API key or paid plan is needed: without one, Android speech services are used, and without an
AI model the app runs a labelled echo test.

## Modules
- `audio-engine`: pure Kotlin voice engine. It does not depend on any AI model.
- `ai-assistant`: AI wire protocol. It does not depend on the voice engine.
- `app`: Android adapters and screens.

## Build
GitHub Actions (`.github/workflows/build.yml`) tests, lints, builds and publishes a development APK.
Locally: JDK 17, Android SDK 36 and Gradle 8.11.1, then `gradle :app:assembleDebug`.

See [docs/VOICE.md](docs/VOICE.md).
