# ANGLE Deck v0.8.0 — Shizuku graphics manager

Android 16, Shizuku UserService (ADB shell), no root.

## New
- Start a live Logcat session for the selected app UID. UID filtering captures all its processes and survives PID changes.
- Stream from main, system and crash buffers while the user tests a game; the shell UserService continues buffering while the UI is in the background.
- Bounded in-memory queues (180 KB service, 90 KB UI). No automatic uploads or log files.
- All / Graphics / Errors display filters plus text search, clear *viewer only*, manual export through Android document picker.
- No device-wide log clearing, persistent background service or unrelated GPU overclocking commands.

## Limitations
- This logs messages written under the game's UID. system_server, tombstoned, and AndroidRuntime may log crashes using different UIDs and won't appear in this view. The existing diagnostics view can inspect wider device buffers, without attributing them to the game.
- On some ROMs logcat --uid may be unsupported; the service will display an error instead of silently reading all apps.
- Capturing the app may stop if Shizuku stops, the user service dies, or Android kills ANGLE Deck. Logs are stored in a bounded memory buffer, not indefinitely.
- Filter labels affect display only, not the device's ring buffers. Exported logs may contain sensitive data.
- Recheck app compatibility on the actual device. Build is not pre-tested.

## Review of uploaded reference code
- AxManager-main.zip (Apache 2.0): inspected architecture, terminal-like UI, shell-level privilege model; no code copied.
- game_gpu_tune_ax.zip: inspected portable shell routines and dashboard ideas; no commands or code copied.
- SoCXtreme_Mediatek(.zip): inspected root-dependent performance tweaks, *not* imported because Shizuku shell cannot safely apply them.
- All Mali GPU Drivers .zip: archived assorted vendor driver packages are not generic installable Shizuku drivers. Nothing flashed/installed.


## v0.6 UI organization

- **Home**: app overview, current GLES route, GPU driver, and launch status.
- **Drivers**: ANGLE/native selection, updatable GPU-driver choices, pipeline explainer.
- **Live logs**: UID-filtered Shizuku Logcat, filter/search, local heuristic crash insights, export.
- **Tools**: manual test outcomes, safe rollback, advanced diagnostics.

The bottom navigation preserves the selected app, session history, and bound Shizuku service.
Crash analysis is heuristic and must not be mistaken for a verified root cause.


## v0.7 Selected-app Restart + System UI Renderer

- Drivers: **Apply route & restart selected app** saves a per-app ANGLE/native/default setting, force-stops only that package and launches its main activity via Shizuku. A normal Apply still stops the app without automatically relaunching.
- Home: **Force restart selected app** keeps existing graphics settings, but asks for confirmation before closing the chosen application. SystemUI, Settings, common launchers and ANGLE Deck itself are blocked.
- Tools → **System UI renderer** opens a separate page (four-tab navigation stays compact): `none`, `skiagl`, `skiavk`; read property, verify changes, apply with optional targeted restart, and restore the saved previous value if unchanged externally.
- `debug.hwui.renderer` is **global** and only concerns Android HWUI/Skia; it does not override an Unreal/Unity game renderer or force native Vulkan. `ANGLE` only requests the GLES translation driver, and successful Vulkan use must be verified separately.
- No system-wide force-stop; only the explicitly selected app is restarted. Changes may be ignored on some ROMs. Shizuku shell permissions are required.


## v0.8 Changes

- **Quiet game launch**: stops ANGLE Deck's own Shizuku Logcat capture before opening the selected app. It does not alter device logs, Android diagnostics, Perfetto, crash reporting, thermal controls, binder tracing, SurfaceFlinger flags, or global settings.
- **ANGLE evidence (experimental)**: review ANGLE/Vulkan clues from the selected application's previously captured UID log. Clues are not definitive backend verification; absence of clues is not proof of native rendering.
- **Read-only graphics audit**: ANGLE support property, Vulkan features, built-in ANGLE package lookup, HWUI renderer property, SurfaceFlinger layer listing, thermal-status summary. ROMs may deny specific dumpsys commands.
- **Reset selected game's GLES + GPU choices**: user-confirmed, stops only the selected game. Sequential independent updates are not atomic; verify both labels after reset. Existing per-app settings for other packages are preserved.
- **HWUI system-default fix**: clear the debug.hwui.renderer property value rather than writing literal `none`. Exact previous value is still recorded for conditional restore.
- **Protected-app guard**: prevent driver changes that would force-stop ANGLE Deck, Settings, SystemUI, GMS, permission controllers, or the device's current home launcher. Require a launchable target for settings that force-stop.

### Why StarFlow's log suppression was NOT copied
The reference Magisk scripts use privileged global tracing, logging, battery, performance, and binder commands. ANGLE Deck is an unrooted Shizuku utility (ADB shell UID), and disabling system diagnostics is not a proven frame-rate improvement; it can hide crashes and cause instability. This build offers quiet **in-app** capture instead. SurfaceFlinger is read-only.

### Build status
This is source for an Android 16 (compileSdk 36) build, intended for the existing GitHub Actions workflow. The source was validated locally for file completeness; it was not compiled or tested on a device in this environment. Existing private signing secrets MUST be preserved if installing over earlier versions.
