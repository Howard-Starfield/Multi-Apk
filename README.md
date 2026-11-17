# CloneLab

A personal-experiment Android app that runs several independent copies of an
app on one phone. Each copy can be renamed, given its own icon and home-screen
shortcut, kept alive in the background, and given its own location and device
profile.

Status: early and experimental. Tested by hand on two phones (Android 11 and
Android 15), mostly with one app.

## Download

Get the APK from the [latest release](https://github.com/Howard-Starfield/Multi-Apk/releases/latest)
and open it on the phone. Android will ask you to allow installing from that
source, and may warn that the app targets an old Android version; that target
is required by the engine.

- Phones: Android 5 or later, ARM (64- or 32-bit).
- It is a debug-signed build, not a store release. A later build installs over
  it only if it is signed with the same key.
- On Android 15, "Display over other apps" is greyed out for apps installed
  from a file until you allow it once: Settings, Apps, CloneLab, the menu in
  the top-right corner, "Allow restricted settings". The app walks you through it.

## Layout

| Path | What |
|---|---|
| `app/` | The app (Kotlin, original code) |
| `engine/` | Vendored virtualization engine, [NewBlackbox](https://github.com/ALEX5402/NewBlackbox), Apache-2.0, with local changes listed in `engine/UPSTREAM.md` |
| `docs/` | Design spec, implementation plan, engine notes |

## Build

Needs JDK 21, Android SDK platform 35, build-tools 35.0.0 and NDK
29.0.13846066. Put the SDK path in `local.properties` (`sdk.dir=...`).

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It targets
SDK 28, which the engine requires, so it is for sideloading only.

## Licence

`engine/` is Apache-2.0 (see `engine/LICENSE`). No licence has been chosen for
the rest of the repository yet.
