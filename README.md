# Nexus Vision OS

Android spatial computing platform, in early development.

## What exists today

"Emulator" means an Android 14 x86_64 emulator in CI running the arm64 libraries through ARM
translation. **Nothing has been validated on a physical phone yet.**

| Area | Status |
|---|---|
| Spatial mode: camera passthrough or virtual sky, OpenGL ES windows, glass effect | Seen working in emulator screenshots |
| Head tracking | Rotation only (3DoF). No positional tracking |
| MediaPipe hand tracking | Model loads and inference returns results in the emulator, with **no hand in view**. Real hand detection and pinch are untested |
| Gestures: pinch click/drag/scroll, move, resize, two-hand scale, open palm for Home | Logic unit tested with synthetic landmarks; untested with real hands |
| Window input path | A screen touch opened Calculator from Home in the emulator, using the same ray and window code as a pinch |
| Apps: Home, browser, notes, calculator, clock, media player, controls, EN/AR keyboard, Nexus voice | Home and Calculator seen in the emulator; the others and typing are untested on a device. Calculator logic is unit tested |
| Headset (side by side) mode | Experimental, seated use only. Renders in the emulator; no calibrated lens profiles, optics untested. A single camera gives no real depth |
| ElevenLabs TTS and Scribe STT, VAD, barge-in, Android speech fallback | Protocol logic unit tested with fakes; no live or on-device test |
| API keys encrypted with Android Keystore | Implemented; needs device testing |
| AI model setting (OpenAI-compatible streaming, HTTPS only) | Parser tested; no live provider tested |

**Not implemented:** ARCore, 6DoF and anchors, depth occlusion, Files app, browser tabs, bookmarks
and downloads, launcher folders, search and favorites, workspace restore, virtual desktops, AI tool
calling, wake word, visual assistant, performance diagnostics, instrumentation tests, AAB and
production signing.

No API key or paid plan is needed: without one, Android speech services are used, and without an
AI model the app runs a labelled echo test.

## Install
Download the APK from the latest [release](../../releases). It needs Android 10 or newer on an
**arm64** phone. It is debug signed, and each CI build is likely signed with a different key, so
uninstall the previous build first (this deletes notes and saved API keys).

## Hand controls
Hold your hand in front of the **back** camera.
- Pinch thumb and index finger: click. Pinch and move: drag or scroll.
- Pinch a window title to move it, or its corner to resize it. Pinch with both hands to scale.
- Hold an open palm: show or hide Home.

## Test on a real phone
1. The camera image is upright and matches the direction you point. If not, use Controls > Rotate camera.
2. The hand outline lies on top of your hand.
3. Pinch opens an app; lowering your hand never leaves a click or drag stuck.
4. Windows stay in place as you turn.
5. Watch frame rate, hand latency and heat for ten minutes.
6. Covering or losing the camera shows a stop warning, not a frozen picture.

## Modules
- `xr-engine`: pure Kotlin math, windows, gestures and calculator.
- `audio-engine`: pure Kotlin voice engine. It does not depend on any AI model.
- `ai-assistant`: AI wire protocol. It does not depend on the voice engine.
- `app`: Android camera, sensors, renderer, apps and screens.

## Build
GitHub Actions (`.github/workflows/build.yml`) runs unit tests and lint, builds the APK, runs the
emulator smoke test, and publishes a development release only if all pass. Releases tagged
`smoke-failed-*` hold diagnostics from failed runs and contain no app.
Locally: JDK 17, Android SDK 36 and Gradle 8.11.1, then `gradle :app:assembleDebug`.

See [docs/VOICE.md](docs/VOICE.md).
