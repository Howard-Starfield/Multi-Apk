# CloneLab

A personal-experiment Android app that runs several independent copies of an
app on one phone. Each copy can be renamed, given its own icon and home-screen
shortcut, kept alive in the background, and given its own location and device
profile.

Status: builds and passes its unit tests. Not yet run on a device.

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
