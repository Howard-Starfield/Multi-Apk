# Engine notes (NewBlackbox `Bcore`, vendored in `engine/`)

Facts read from source at commit `89b5983`. Line numbers are for the unmodified
files. Runtime behaviour is unverified unless stated.

## Build

- JDK 21 (`~/android-toolchain/jdk-21.0.13+9`), Android SDK at
  `~/android-toolchain/sdk`, NDK `29.0.13846066`, AGP 8.13.2, Kotlin 1.9.23,
  Gradle 8.14.5 wrapper. `compileSdk 35`, `targetSdk 28` (required by the
  engine), `minSdk 21`. ABIs `arm64-v8a`, `armeabi-v7a` only.
- Before running Gradle from Git Bash:
  `export JAVA_HOME="$HOME/android-toolchain/jdk-21.0.13+9" ANDROID_HOME="$HOME/android-toolchain/sdk"; export PATH="$JAVA_HOME/bin:$PATH"`
  then `./gradlew.bat <task> --console=plain -q`. Redirect output to a file and
  grep it; it is very long.
- Root `build.gradle` `ext`: `compileSdkVersion`, `targetSdkVersion`, `minSdk`,
  `versionCode`, `versionName`. Version catalog in `gradle/libs.versions.toml`.
- A host app uses `implementation project(':Bcore')`. Bcore's manifest merges
  in ~226 permissions, 50 stub process slots (`:p0`..`:p49`), a server process
  `:black`, and providers whose authorities use `${applicationId}`.

## Host initialisation (what the demo `Application` does)

```kotlin
override fun attachBaseContext(base: Context?) {
    super.attachBaseContext(base)
    BlackBoxCore.get().closeCodeInit()                       // remove: see hardening
    BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)
    BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
        override fun getHostPackageName() = base.packageName   // only abstract method
        // overridable: isHideRoot() false, isEnableDaemonService() true,
        // isEnableLauncherActivity() true, isUseVpnNetwork() false,
        // isDisableFlagSecure() false, requestInstallPackage(File, Int) false,
        // getLogSenderChatId()
    })
    BlackBoxCore.get().onAfterMainApplicationAttach(this, base)
}
override fun onCreate() { super.onCreate(); BlackBoxCore.get().doCreate() }
```

The `Application` class runs in every process (main, `:black`, `:pN`). Host UI
code must not assume it is in the main process.

`BlackBoxCore.addAppLifecycleCallback(AppLifecycleCallback)` gives hooks such as
`beforeCreateApplication(pkg, processName, context, userId)` and
`afterApplicationOnCreate(pkg, processName, application, userId)` that run
inside the clone process.

## Clone lifecycle — `top.niunaijun.blackbox.BlackBoxCore` (`BlackBoxCore.get()`)

A clone is `(packageName, userId)`. More clones of one app = same package under
more virtual user ids. Users are created implicitly on first install.

| Method | Notes |
|---|---|
| `InstallResult installPackageAsUser(String packageName, int userId)` | from an app installed on the phone |
| `InstallResult installPackageAsUser(File apk, int userId)` | |
| `InstallResult installPackageAsUser(Uri apk, int userId)` | copies to cache first |
| `boolean launchApk(String packageName, int userId)` | false if no launcher activity |
| `void stopPackage(String packageName, int userId)` | kills its processes |
| `void clearPackage(String packageName, int userId)` | kill + wipe data |
| `void uninstallPackageAsUser(String packageName, int userId)` | |
| `boolean isInstalled(String packageName, int userId)` | |
| `List<ApplicationInfo> getInstalledApplications(int flags, int userId)` | per user |
| `List<PackageInfo> getInstalledPackages(int flags, int userId)` | per user |
| `List<BUserInfo> getUsers()` / `BUserInfo createUser(int)` / `void deleteUser(int)` | `BUserInfo.id` |
| `boolean waitForServicesAvailable(long timeoutMs)` / `isServicesAvailable()` | call before first use |
| `boolean hasAllFilesAccess()` / `requestAllFilesAccess(Activity)` | |

`InstallResult` (`top.niunaijun.blackbox.entity.pm.InstallResult`) has public
fields only: `boolean success`, `String packageName`, `String msg`.

Running state: `BlackBoxCore.isRunningApplication` is broken (always false).
`BActivityManager.get().getRunningAppProcesses(callerPackage, userId)` exists.
Hardening adds a reliable `BlackBoxCore.isPackageRunning(String, int)`.

`Docs.md` upstream documents many methods that do not exist. Trust only this
file and the source.

## Fake location — `top.niunaijun.blackbox.fake.frameworks.BLocationManager`

`BLocationManager.get()`; modes `CLOSE_MODE=0`, `GLOBAL_MODE=1`, `OWN_MODE=2`.

- `void setPattern(int userId, String pkg, int pattern)` / `int getPattern(int userId, String pkg)`
- `void setLocation(int userId, String pkg, BLocation location)` / `BLocation getLocation(int userId, String pkg)`
- `BLocation` (`top.niunaijun.blackbox.entity.location.BLocation`):
  `BLocation(double latitude, double longitude)`, `getLatitude()`, `getLongitude()`.

Persisted by the engine. Set the location before setting `OWN_MODE`.

## Device identity (as shipped)

Not configurable. Hard-coded global values: a native `__system_property_get`
hook returning a fixed "Pixel 6" table (`cpp/Utils/VirtualSpoof.cpp`), telephony
ids = `md5(hostPackage)`, fixed Wi-Fi MAC, no ANDROID_ID handling
(`AndroidIdProxy`/`DeviceIdProxy` are dead code). See "Per-clone device
profile" below for the API we add.

## Keep-alive (as shipped)

`core/system/DaemonService` in `:black` posts a low-importance foreground
notification when `isEnableDaemonService()` is true. An inner service cancels
the same notification id. No boot receiver. No automatic restart of dead clone
processes. `BProcessManagerService.onProcessDie` is where death is observed.

## Shortcuts

The demo pins a shortcut with `ShortcutManagerCompat.requestPinShortcut` whose
intent opens a host activity that calls `launchApk(pkg, userId)`.

## Problems found in the shipped engine

Privacy and safety:

1. `utils/LogSender.java` uploads device info and full logcat to
   `logs-sender-api.vercel.app` (a Telegram relay). Called from
   `BlackBoxCore.sendLogs` and from `utils/SimpleCrashFix` on crashes.
2. `BlackBoxCore.startLogcat()` (called at line 863) clears the system log
   buffer and continuously writes logcat to the public Downloads folder.
3. `closecode/Entry.java` loads `<files>/inject.so` into a hard-coded package.
4. `SimpleCrashFix` installs a global uncaught-exception handler that swallows
   several classes of crash.

Bugs:

5. `core/system/pm/BPackageManagerService.installPackageAsUserLocked` returns
   `success = true` when it catches an exception.
6. `BLocation` writes speed, bearing, accuracy to a Parcel but reads accuracy,
   speed, bearing.
7. `fake/service/ILocationManagerProxy` `getLastLocation`/`getLastKnownLocation`
   dereference a null location in `OWN_MODE` when none is set.
8. `BLocationManagerService.addTask` busy-loops when the location is null.
9. `BlackBoxCore.isRunningApplication` always returns false (bad cast).
10. `DaemonService$DaemonInnerService` cancels the foreground notification.
11. `DaemonService` uses `foregroundServiceType="specialUse"` without the
    `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest property (Android 14).

## Per-clone device profile (API we add to the engine)

Contract between engine and app. Engine side lives in `engine/Bcore`.

```java
package top.niunaijun.blackbox.entity.device;
public class BDeviceProfile {            // plain fields, all nullable = "leave real value"
    public String brand, manufacturer, model, device, product, board, hardware;
    public String fingerprint;
    public String androidId;             // 16 lowercase hex chars
    public String imei;                  // 15 digits, valid Luhn
    public String serial;
    public String wifiMac;               // "aa:bb:cc:dd:ee:ff"
}

package top.niunaijun.blackbox.fake.frameworks;
public class BDeviceManager {
    public static BDeviceManager get();
    public void setProfile(int userId, String packageName, BDeviceProfile profile);
    public BDeviceProfile getProfile(int userId, String packageName);   // null if none
    public void clearProfile(int userId, String packageName);
}
```

A profile takes effect the next time the clone's process starts.
