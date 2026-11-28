# Native Apps: feasibility notes

**Status: not implemented. The app cannot open other Android apps in a window.** This file records
what has been checked so far.

## Emulator probe (2026-11-28)
`ci/native-probe` runs with `app_process` as the `shell` user (uid 2000), the identity Shizuku
provides in ADB mode. It launches a small test app of its own on a new virtual display backed by an
`ImageReader`. Workflow: `.github/workflows/native-probe.yml`
([run](https://github.com/CarlosPeterDev/Vision-OS/actions/runs/46142695089)). x86_64 `google_apis` emulators.

| Check | Android 12 (API 31) | 13 (API 33) | 14 (API 34) |
|---|---|---|---|
| Trusted virtual display | Refused: needs `ADD_TRUSTED_DISPLAY` | Pass | Pass |
| Untrusted fallback display | Pass | not needed | not needed |
| Launch the test app on it | Pass | Pass | Pass |
| Real pixels from the app (red) | Pass | Pass | Pass |
| `input -d` tap changes the pixels (green) | Pass | Pass | Pass |
| A frame arrives after resizing to a new surface | Pass | Pass | Pass |
| Back closes the test app | Pass | Pass | Pass |
| Display released | Pass | Pass | Pass |

The Android 12 job is red only because the trusted display is refused. This matches AOSP's Shell
manifest: `ADD_TRUSTED_DISPLAY` is absent at tag `android-12.0.0_r1` and present at
`android-13.0.0_r1` and `android-14.0.0_r1`
([source](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/packages/Shell/AndroidManifest.xml)).
In the Android 12 log the test app was also created a second time around Back; this is unexplained.

## What the probe does not show
- Anything through Shizuku itself: installing, permission, `UserService`, binder death.
- Passing a `Surface` from the Nexus process to the privileged process.
- Showing those frames through Nexus's `SurfaceTexture` and OpenGL renderer.
- Programmatic input injection from a `UserService` (the probe used the `input` command), drags,
  multi-touch, or typing.
- Third-party apps, secondary activities, dialogs, keyboards, orientation changes, or what an
  untrusted display refuses.
- Any physical phone or manufacturer build.

## Next steps
1. An emulator test with real Shizuku: a minimal `UserService` that creates the display on a
   `Surface` received from the app, drawn by the existing renderer.
2. A narrow AIDL interface only: create, resize and release a display; launch a chosen launcher
   activity on it; inject pointer and key events into displays the service created. No command
   execution method.
3. A physical-phone check before the feature is offered.

## References
- [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (MIT). Its README lists
  `dev.rikka.shizuku:api` and `:provider`, newest changelog entry 13.1.5; check Maven before adding.
- [scrcpy `NewDisplayCapture`](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java):
  adds the trusted flags only from API 33. Read for its flag selection; no code copied.
- Not yet studied: Pocket XR, NextVR-Android, SimpleVirtualDisplay.
