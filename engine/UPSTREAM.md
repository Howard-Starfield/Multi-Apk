# Vendored engine

Source: https://github.com/ALEX5402/NewBlackbox
Commit: 89b59836c66f173756a4ae258cf379a957649820 (2026-07-19)
Licence: Apache-2.0 (see LICENSE)

Modules copied unchanged: `Bcore`, `black-reflection`, `compiler`.
Record every local change to these modules in the "Local changes" list below.

## Local changes

- Removed `utils/LogSender.java` (network log upload); `BlackBoxCore.sendLogs(...)` overloads are now no-ops that call `listener.onFailure("Log upload is disabled")` if a listener is given; `ClientConfiguration.getLogSenderChatId()` returns null.
- Removed `BlackBoxCore.startLogcat()` and its call, plus the helpers only it/`sendLogs` used (`logDeviceInfo`, `getDeviceInfoString`).
- Removed `closecode/Entry.java`; `BlackBoxCore.closeCodeInit()` is an empty no-op.
- `utils/SimpleCrashFix` no longer installs a global uncaught-exception handler (no swallowed crashes, no `sendLogs`); it only installs the context wrapper hook.
- `BPackageManagerService.installPackageAsUserLocked` returns a failed `InstallResult` (with the exception message) when a Throwable is caught.
- `BLocation(Parcel)` reads speed, bearing, accuracy in the same order they are written.
- `ILocationManagerProxy` `getLastLocation`/`getLastKnownLocation` null-check the fake location and fall through to the real call when none is set.
- `BLocationManagerService`: `addTask` sleeps when location is null and exits when the listener is removed; listener map is a `ConcurrentHashMap`.
- Added `isPackageRunning(String, int)` to `IBActivityManagerService` (AIDL), `BActivityManagerService`, `BActivityManager` and `BlackBoxCore`; static `isRunningApplication` now delegates to it.
- `DaemonService`: removed `DaemonInnerService` (it cancelled the foreground notification) and its manifest entry; notification title/text come from Bcore string resources (`daemon_notification_title`, `daemon_notification_text`: "Clone engine running").
- Manifest: added `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property to the `DaemonService` `<service>`.
- `Bcore/build.gradle`: replaced `tasks.matching { ... }.all` with `tasks.configureEach` for the AIDL-comment-stripping hook; the eager `matching` realised the R8 task before AGP registered its build service and broke configuration ("R8D8ThreadPoolBuildService is not registered").
- Per-clone device profile: added `entity/device/BDeviceProfile`, `fake/frameworks/BDeviceManager` (one JSON file per (userId, package) at `<system dir>/device-profiles/<userId>/<package>.json`, atomic write; helper `BEnvironment.getDeviceProfileFile`) and `core/device/DeviceProfileApplier`.
- `BActivityThread.handleBindApplication` loads the profile right after `NativeCore.init` and applies it: reflective `android.os.Build` fields plus native property overrides via new JNI `NativeCore.setSpoofProps(String[], String[])` (registered in `BoxCore.cpp` `gMethods`).
- `cpp/Utils/VirtualSpoof.cpp`: removed the hard-coded global "Pixel 6" property table (it also applied to the host); the table is now empty by default and set per process (copy-on-write, mutex-guarded snapshot). Only `__system_property_get` is hooked; `__system_property_find`/`__system_property_read_callback` readers are not.
- `ITelephonyManagerProxy` (getDeviceId, getImeiForSlot, getMeidForSlot, getDeviceIdWithFeature), `IDeviceIdentifiersPolicyProxy` (serial) and `IWifiManagerProxy` (getConnectionInfo MAC) return the profile value when set, else the previous behaviour.
- ANDROID_ID: `SystemProviderStub` and `ContentProviderStub` pass `call` results through `DeviceProfileApplier.patchSettingsCall`, which substitutes the `value` of a `GET_secure`/`android_id` settings call with `profile.androidId`. `AndroidIdProxy`/`DeviceIdProxy` are left in place (inert: `getWho()` is null).
- Added `BlackBoxCore.isEngineAlive()`: true only when `getService(PACKAGE_MANAGER)` returns a binder that is alive and answers `pingBinder()` (`waitForServicesAvailable`/`isServicesAvailable` are set true on every path, including fallbacks without a server).
