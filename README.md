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
- **Hand shortcuts:** five still hand shapes (two or three fingers, thumbs up, two with the little
  finger) can each be given an action. Held for one second. All are off until you choose.
- **Hand practice:** what the tracker sees, plain advice when it struggles, a pinch exercise and
  easier/harder pinch settings.
- **Keyboard suggestions:** finishes words, guesses the next word from your own typing and fixes
  one-letter slips. Learned words stay on the phone. There is no swipe typing.
- **Mouse, keyboard and game controller** (Bluetooth or USB, paired in the phone's settings). A
  mouse and the left stick move a cursor in the view; keys type into the focused window.
- **Speed check:** frames per second, tracking delay, memory and battery use per hour (shown only
  after five measured minutes). Optional crash reports stay on the phone until you share them and
  leave out error messages and your content. Crashes inside native libraries are not captured.
- **Voice:** over 100 offline English and Arabic commands for windows, pointing, typing, browser,
  media, view and system. `type …`, `search for …`, `go to …`, `click …`, `set timer for … minutes`,
  `add task …`. The Voice app lists them all.
- **AI control:** other sentences go, as text, to the AI model you configure. It can only reply
  with commands from the same list. Presses, Enter, closing everything, paste, cut, clear and
  risky button names (pay, send, delete and similar) need "confirm". Can be turned off.
- **Browser:** tabs that reopen, bookmarks, history, downloads, search engine choice, desktop sites.
  A page's full screen video fills the browser window, with an
  exit button; Back also leaves it. Website sign-ins stay on the phone until you sign out in Settings. Some companies, including
  Google, may refuse sign-in inside a built-in browser.
- **Files:** import copies into "My files"; open pictures, video, audio, PDF and text (editable);
  save a copy to Downloads. A chosen phone folder can be browsed read-only.
- **Apps:** photos, photo and video capture (no sound), notes, tasks, calendar, calculator,
  converter, paint, games, weather, clock, media player, keyboard (EN/AR), Nexus assistant.
- **Spaces and voice shortcuts:** three saved sets of windows (work, study, cinema), and your own
  phrases that each run up to eight built-in commands. Risky steps still ask first.
- **Reminders and calendar:** add events with a reminder, or say "remind me in 10 minutes to ...".
  They are stored on the phone; with notification permission they also arrive when spatial mode is
  closed (Android may be a few minutes late). An event can be copied to the phone's calendar.
- **Player:** several files as a playlist, SRT/VTT subtitle files with timing adjustment, and a
  large or very large curved screen.
- **Walk mode (6DoF, optional):** with Google's ARCore, windows stay in place while you walk. Turn it
  on in Controls or say "walk mode on". It needs an ARCore phone and Google Play Services for AR (free).
  When tracking is lost the view keeps turning with the motion sensor. Uses more battery and heat.
- **Heat:** follows Android's thermal status and does less work as the phone warms (lighter glass,
  slower hand tracking, 30 FPS when very hot). It never stops the app and can be turned off.
- **Conversation:** the assistant keeps listening after each reply until you say "bye" / "خلاص" or stay
  silent. Talking over a reply stops it and your words are answered. A configured AI model also gets
  the last few turns.
- **Phone test:** Speed check > Test on this phone: camera, hand signals, microphone, speaker, speech,
  interruption, walk mode and heat, with a report you can share.
- **Places:** dusk, night, mountains, ocean, cinema hall, deep space, beach, or your own 360° photo.
- **3D interface sound** (left/right, needs headphones) and an opening animation.
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
| Keys from a keyboard typed into Notes | Emulator, with injected key events (no real Bluetooth device) |
| Hand shapes, tracking advice, suggestions, cursor, crash report contents, battery sums | Unit tests with made-up input |
| Practice and Speed check windows | Emulator screenshot |
| Real mouse, game controller or Bluetooth keyboard | **Not tested** |
| Hand shapes with a real hand; catching and sharing a real crash | **Not tested** |
| Speech recognition (microphone), offline language packs | **Not tested** |
| AI control with a real provider; ElevenLabs live | **Not tested** |
| Real hand gestures, direct touch, calibration, hand occlusion | **Not tested** |
| Website sign-in persistence, file import, PDF viewer, downloads, weather | **Not tested** |
| GPU hand tracking (off by default, falls back to CPU after a crash) | **Not tested** |
| Release signing with your key | **Not tested** until the secrets are added |
| Same window size in camera view and virtual places | Unit test and emulator screenshots |
| Walk mode maths (pose joining, rays after walking, picture conversion) | Unit tests with made-up input |
| Walk mode with real ARCore | **Not tested**; the emulator only checks that asking for it does not crash |
| Input matches the animated window on screen; overlaps pick the window painted on top | Unit tests |
| Tutorial counts only the lesson's own action; skipped lessons are not counted | Unit tests; first lesson seen in an emulator screenshot |
| Browser full screen video | Builds; **not tested** with any video |
| Opening other Android apps in a window | **Not implemented**; see [feasibility notes](docs/NATIVE_APPS_ARCHITECTURE.md) |
| Heat steps | Unit tests; **not tested** on a hot phone |
| Conversation and interruption logic | Unit tests with fake audio; **not tested** with a real microphone |
| Phone test window | Builds and opens; its tests were **not run** on a phone |
| Screen fit by voice, messages drawn in both eyes | Emulator screenshot |
| Moving screens by finger drag | **Not tested** |
| Example voice shortcut, study space, spoken 1-minute reminder, new places | Emulator |
| Warning for an event time that has passed | **Not tested on screen** |
| Reminder notification with spatial mode closed or after a restart | **Not tested** |
| Subtitles, playlist, own 360° photo, copying to the phone calendar | **Not tested on Android** (subtitle reading has unit tests) |
| 3D sound by ear | **Not tested** |
| Headset optics and comfort | **Not tested**; experimental, seated use only |

## Limits
Without walk mode, head tracking is rotation only. Walk mode has no obstacle warnings. No eye
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

The release then has a signed APK and AAB. Without a key, both are debug-signed. Keep a private backup of the key. Never commit it.

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
9. Run Speed check > Test on this phone and share the report.

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
