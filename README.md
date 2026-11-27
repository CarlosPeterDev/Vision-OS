# Nexus Vision OS

An Android spatial computing app: floating glass windows over the camera view (or a virtual sky),
controlled by your hands, your voice or the screen.

## Install
Download the APK from the latest [release](../../releases). It needs Android 10 or newer on an
**arm64** phone. See [Keep your data across updates](#keep-your-data-across-updates).

## Features
- **Windows:** liquid glass, smooth animations (reduced automatically below 15 FPS), move, scale,
  free width and height, distance, curved cinema screen, saved layouts, control center.
- **Hands (MediaPipe):** pinch or direct poke to click, drag to scroll, flick, look-and-pinch,
  calibration, left/right hand choice, hand menu, tutorial. A press is a click; only a hold of
  2 seconds (adjustable) is a long press, and lag does not count towards it.
- **Voice:** over 100 offline English and Arabic commands for windows, pointing, typing, browser,
  media, view and system. `type …`, `search for …`, `go to …`, `click …`, `set timer for … minutes`,
  `add task …`. The Voice app lists them all.
- **AI control:** other sentences go, as text, to the AI model you configure. It can only reply
  with commands from the same list. Presses, Enter, closing everything, paste, cut, clear and
  risky button names (pay, send, delete and similar) need "confirm". Can be turned off.
- **Browser:** tabs that reopen, bookmarks, history, downloads, search engine choice, desktop sites.
  Website sign-ins stay on the phone until you sign out in Settings. Some companies, including
  Google, may refuse sign-in inside a built-in browser.
- **Files:** import copies into "My files"; open pictures, video, audio, PDF and text (editable);
  save a copy to Downloads. A chosen phone folder can be browsed read-only.
- **Apps:** photos, photo and video capture (no sound), notes, tasks, calendar, calculator,
  converter, paint, games, weather, clock, media player, keyboard (EN/AR), Nexus assistant.
- **Headset screen fit:** move each eye's picture on its own, change the gap, width and height with
  buttons, by dragging with a finger (two fingers resize, double tap finishes) or by voice ("wider
  gap", "screens closer", "bigger screens"). Messages and the listening and recording badges are
  drawn in both eyes.
- **Voice engine:** ElevenLabs (optional, your own key) with Android speech as fallback.
  See [docs/VOICE.md](docs/VOICE.md).

No account, API key or paid plan is needed. AI control of free-form requests needs an AI model.

The app no longer slows or stops itself when the phone is hot. Android's own heat protection still
applies. If the phone gets hot, take it out of the headset and let it cool.

## What has been tested
"Emulator" means an Android 14 emulator in CI. **The assistant that wrote this has not tested on a
physical phone.**

| Area | Status |
|---|---|
| Core logic (windows, gestures, press timing, commands, AI command filter, converter, games) | Unit tests |
| Spatial view, Home, most app windows, headset view | Emulator screenshots |
| Short press opens, 2 s hold is a long press; effects reduce below 15 FPS | Emulator |
| Photo and video saving | Emulator checks that the save succeeds; the video was not played back |
| Typing and window reshaping by command | Emulator, by injecting the sentence as text |
| Speech recognition (microphone), offline language packs | **Not tested** |
| AI control with a real provider; ElevenLabs live | **Not tested** |
| Real hand gestures, direct touch, calibration, hand occlusion | **Not tested** |
| Website sign-in persistence, file import, PDF viewer, downloads, weather | **Not tested** |
| GPU hand tracking (off by default, falls back to CPU after a crash) | **Not tested** |
| Release signing with your key | **Not tested** until the secrets are added |
| Screen fit by voice, messages drawn in both eyes | Emulator screenshot |
| Moving screens by finger drag | **Not tested** |
| Headset optics and comfort | **Not tested**; experimental, seated use only |

## Limits
Head tracking is rotation only (no walking around; ARCore position tracking is not built). No eye
tracking, no true stereo passthrough and no real depth from one camera. Offline recognition depends on your phone: normally Android 12 or
newer with the language pack installed. With "Offline only" off, the phone's speech service may
use the internet; the listening badge shows which service is in use.

## Keep your data across updates
Without a signing key, every build is signed with a new one-time key, so you must uninstall before
updating, which deletes sign-ins, notes and imported files. To fix this once:
1. Make a key (`keytool -genkeypair -keystore nexus.jks -storetype PKCS12 -alias nexus -keyalg RSA
   -keysize 3072 -validity 10000`), using the same password for the store and the key.
2. In the repository: Settings > Secrets and variables > Actions, add `NEXUS_KEYSTORE_PASSWORD`
   and `NEXUS_KEYSTORE_BASE64` (the output of `base64 -w0 nexus.jks`).
3. Run the Build workflow. Uninstall the old app one last time and install the new APK.

The release then has a signed APK and AAB. Keep a private backup of the key. Never commit it.

## Test on a real phone
1. The camera image is upright and matches where you point (otherwise: Settings > Rotate camera).
2. The hand outline lies on your hand; a quick pinch clicks; lowering your hand leaves nothing stuck.
3. Voice app > Start. Say "open notes", tap the text field, say "type hello".
4. Sign in to a website, close the browser, open it again.
5. Files > Import a PDF and a picture and open both.
6. Take a photo and record a short video, then play it.
7. Home > Screen fit > Drag with finger: line up both pictures, double tap, then put the phone in
   the headset and fine tune with the buttons. Check that messages look single and sharp.
8. Covering or losing the camera shows a stop warning, not a frozen picture.

## Modules
- `xr-engine`: pure Kotlin math, windows, gestures, commands and small app logic.
- `audio-engine`: pure Kotlin voice engine. It does not depend on any AI model.
- `ai-assistant`: AI wire protocol.
- `app`: Android camera, sensors, renderer, apps and screens.

## Build
GitHub Actions (`.github/workflows/build.yml`) runs unit tests and lint, builds the app, runs the
emulator smoke test, and publishes a release only if all pass. Releases tagged `smoke-failed-*`
hold diagnostics from failed runs and contain no app.
Locally: JDK 17, Android SDK 36 and Gradle 8.11.1, then `gradle :app:assembleDebug`.
Pure logic tests: `./run-core-tests.sh`.
