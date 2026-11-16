# CloneLab app — implementation plan

Read with `docs/superpowers/specs/2026-11-15-clone-app-design.md` and
`docs/engine-notes.md`. Four tasks, done in order. Each task must leave
`:app:assembleDebug` and `:app:testDebugUnitTest` green.

## Decisions that apply to every task

- Module `app/`, Kotlin, `applicationId` and `namespace` `dev.clonelab.app`.
  `compileSdk`, `minSdk`, `targetSdk` (28), version from root `ext`. Java 21.
- UI: Android Views with viewBinding, Material Components, `RecyclerView`.
  No Compose (avoids compiler/Kotlin version coupling). One `Activity` per
  screen. All user-visible text in `res/values/strings.xml`, English.
- Dependencies (add to `gradle/libs.versions.toml`): the existing appcompat,
  material, constraintlayout, core-ktx; `androidx.recyclerview:recyclerview:1.3.2`,
  `androidx.activity:activity-ktx:1.9.3`,
  `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7`,
  `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`,
  `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`; tests: junit 4.13.2,
  `kotlinx-coroutines-test:1.8.1`, `org.json:json:20240303`.
- Only `engine/BlackBoxCloneEngine.kt` and `CloneLabApp.kt` import
  `top.niunaijun.*`. Do not edit anything under `engine/`.
- Engine calls block. Run them on `Dispatchers.IO` from a `ViewModel`; expose
  UI state as `StateFlow`. Never call the engine on the main thread.
- The `Application` class runs in every process (main, `:black`, `:pN`). Do
  engine init in all of them; do app-only init (`ServiceLocator`) lazily so
  clone processes never touch it.
- Persistence: one JSON file `filesDir/clones.json` written with `org.json`,
  atomically (temp file + rename), guarded by a lock. No database.
- Clone identity is `(packageName, userId)`. **userId rule:** a new clone of
  package P takes the smallest `userId >= 0` that P does not already use in
  the store or in the engine. Different apps share user ids. The app never
  calls `deleteUser`. Clone number shown to the user is `userId + 1`.
- Package layout under `app/src/main/java/dev/clonelab/app/`:
  `model/`, `engine/`, `data/`, `ui/home/`, `ui/add/`, `ui/detail/`,
  `shortcut/`, `keepalive/`, `location/`, `device/`.

## Shared model (create in Task 1, complete; later tasks only use it)

```kotlin
// model/Models.kt
data class CloneId(val packageName: String, val userId: Int)
enum class IconKind { APP, TEXT, IMAGE }
data class LocationConfig(val enabled: Boolean, val latitude: Double, val longitude: Double)
data class DeviceProfile(
    val presetName: String?,
    val brand: String?, val manufacturer: String?, val model: String?, val device: String?,
    val product: String?, val board: String?, val hardware: String?, val fingerprint: String?,
    val androidId: String?, val imei: String?, val serial: String?, val wifiMac: String?,
)
data class CloneRecord(
    val id: CloneId,
    val appLabel: String,            // label of the source app at install time
    val customName: String? = null,
    val sortOrder: Int,              // ascending; new clones go last
    val iconKind: IconKind = IconKind.APP,
    val iconColor: Int = 0,          // ARGB, used when iconKind == TEXT
    val iconImagePath: String? = null, // file in filesDir/icons, when IMAGE
    val keepAlive: Boolean = false,
    val location: LocationConfig? = null,
    val deviceProfile: DeviceProfile? = null,
    val createdAt: Long,
) { val number: Int get() = id.userId + 1
    val displayName: String get() = customName ?: "$appLabel $number" }

// engine/CloneEngine.kt
sealed interface EngineResult {
    data object Ok : EngineResult
    data class Error(val message: String) : EngineResult
}
interface CloneEngine {
    fun awaitReady(timeoutMs: Long): Boolean
    fun installFromInstalledApp(packageName: String, userId: Int): EngineResult
    /** @return Ok plus the installed package name through [onPackage]. */
    fun installFromApk(apkUri: String, userId: Int, onPackage: (String) -> Unit): EngineResult
    fun usedUserIds(packageName: String): Set<Int>
    fun isInstalled(id: CloneId): Boolean
    fun isRunning(id: CloneId): Boolean
    fun launch(id: CloneId): EngineResult
    fun stop(id: CloneId)
    fun clearData(id: CloneId)
    fun uninstall(id: CloneId)
    fun applyLocation(id: CloneId, location: LocationConfig?)
    fun applyDeviceProfile(id: CloneId, profile: DeviceProfile?)
}

// data/CloneStore.kt
class CloneStore(file: java.io.File) {
    fun all(): List<CloneRecord>                 // sorted by sortOrder
    fun get(id: CloneId): CloneRecord?
    fun put(record: CloneRecord)                 // insert or replace
    fun remove(id: CloneId)
    fun reorder(idsInOrder: List<CloneId>)
}

// data/CloneRepository.kt
data class CloneItem(val record: CloneRecord, val installed: Boolean, val running: Boolean)
class CloneRepository(engine: CloneEngine, store: CloneStore, clock: () -> Long) {
    val clones: StateFlow<List<CloneItem>>
    suspend fun refresh()
    suspend fun addFromInstalledApp(packageName: String, appLabel: String): Result<CloneRecord>
    suspend fun addFromApk(apkUri: String, fallbackLabel: String): Result<CloneRecord>
    suspend fun launch(id: CloneId): Result<Unit>   // applies location + profile, then launches
    suspend fun stop(id: CloneId)
    suspend fun clearData(id: CloneId)
    suspend fun remove(id: CloneId)                // uninstall + delete record (+ icon file)
    suspend fun update(id: CloneId, transform: (CloneRecord) -> CloneRecord)
    suspend fun reorder(idsInOrder: List<CloneId>)
}
```

`ServiceLocator` (object, lazy) builds the single `CloneRepository`.
`AppVisuals` (ui helper) resolves the label and icon drawable for a record:
custom image → text icon → source app icon from `PackageManager`
(`getApplicationIcon`, or `getPackageArchiveInfo` on the engine-installed
APK when the app is not installed on the phone) → placeholder.

## Task 1 — skeleton and core cloning

Files: `app/build.gradle`, `app/proguard-rules.pro`, `settings.gradle`
(`include ':app'`), `gradle/libs.versions.toml`, manifest, `CloneLabApp.kt`,
the shared model above, `BlackBoxCloneEngine.kt`, `ServiceLocator.kt`,
`ui/home/HomeActivity` + `HomeViewModel` + `CloneAdapter`,
`ui/add/AddCloneActivity` + `AddCloneViewModel` + `InstalledAppAdapter`,
`ui/detail/CloneDetailActivity` + `CloneDetailViewModel`, layouts, strings,
themes, a vector launcher icon.

- `CloneLabApp`: the init sequence from engine-notes, without
  `closeCodeInit()`. `ClientConfiguration`: `getHostPackageName()` =
  package name, `isEnableDaemonService()` = true, `isHideRoot()` = false,
  `getLogSenderChatId()` = null.
- Manifest: `QUERY_ALL_PACKAGES`; launcher activity `HomeActivity`; other
  activities not exported; `android:allowBackup="false"`;
  `tools:replace` as needed for merge conflicts with Bcore.
- `BlackBoxCloneEngine`: maps to the engine API in engine-notes. Treat
  `InstallResult.success == false` or a null/blank `packageName` as Error.
  `usedUserIds(pkg)` = ids from `getUsers()` where `isInstalled(pkg, id)`.
  `isRunning` uses `BlackBoxCore.get().isPackageRunning`. Catch `Throwable`
  from every engine call and convert to `EngineResult.Error` / safe default.
  `installFromApk` passes `Uri.parse(apkUri)`.
- Home: grid (3 columns) of clones showing icon, name, running dot; empty
  state; FAB "Add clone"; tap = launch; long-press = open detail. Refresh in
  `onResume`. Errors shown in a Snackbar with the engine's message.
- Add clone: searchable list of launchable installed apps (exclude our own
  package), tap to clone; button "From APK file" using
  `ActivityResultContracts.OpenDocument` (`application/vnd.android.package-archive`,
  fall back to `*/*`). Progress indicator while installing; finish on success.
- Detail: name, package, clone number, state; buttons Launch, Stop, Clear
  data, Uninstall (the last two behind confirmation dialogs). A record whose
  clone is missing from the engine shows "Broken" and only offers Remove.
- If `awaitReady(10_000)` is false, Home shows an error banner with Retry.

Unit tests (`app/src/test`), with `FakeCloneEngine` (in-memory):
`CloneStoreTest` — round-trips every field; survives a corrupt file (returns
empty, does not throw); `reorder` persists; `remove` works.
`CloneRepositoryTest` — first clone of a package gets userId 0; second gets
1; gap is reused after removal; two different packages both get 0; engine
install error creates no record; `launch` applies location and profile
before launching (assert call order); record missing in engine is reported
`installed = false`; `remove` uninstalls and deletes the record.

## Task 2 — personalisation

Files: `ui/detail/*` (extend), `ui/detail/EditIconDialog`, `ui/home/*`
(drag to reorder with `ItemTouchHelper`, persisted through `reorder`),
`ui/TextIconDrawable.kt`, `shortcut/ShortcutPublisher.kt`,
`shortcut/ShortcutTrampolineActivity.kt`, `AppVisuals`.

- Rename: dialog, blank resets to default name.
- Icon: choose App icon / Text icon (first letter of the name + number on a
  colour chosen from 8 swatches) / Image (photo picker via
  `ActivityResultContracts.GetContent("image/*")`, centre-cropped and scaled
  to 192 px, saved as PNG in `filesDir/icons/<pkg>_<userId>.png`).
- Shortcut: `ShortcutManagerCompat.requestPinShortcut` with id
  `"<pkg>#<userId>"`, the clone's display name and resolved icon bitmap, and
  an explicit intent to `ShortcutTrampolineActivity` carrying the id. The
  trampoline is `android:exported="false"`, `noHistory`,
  `excludeFromRecents`, translucent theme; a pinned shortcut is started with
  the publishing app's identity so it can target a non-exported activity
  (verify on device; if a launcher refuses, export it and validate that the
  id names an existing record). It calls `repository.launch` and finishes;
  on failure it shows a Toast. Renaming or changing the icon updates an
  already pinned shortcut via `ShortcutManagerCompat.updateShortcuts`;
  removing a clone disables it.
- Show "Pinning is not supported by this launcher" when
  `isRequestPinShortcutSupported` is false.

Tests: `TextIconTest` (letter/number derivation for names incl. empty and
non-Latin), repository `update` test for name/icon fields.

## Task 3 — keep-alive

Files: `keepalive/KeepAliveService.kt`, `keepalive/BootReceiver.kt`,
`keepalive/KeepAliveController.kt`, `keepalive/VendorSettings.kt`,
`ui/keepalive/KeepAliveGuideActivity`, detail screen switch "Keep alive",
manifest.

- `KeepAliveService`: foreground service in the main process, `START_STICKY`,
  channel "Background clones" (importance LOW). Notification text lists the
  running clones ("3 clones running"), tap opens Home. Runs while at least
  one record has `keepAlive == true`; `KeepAliveController.sync(context)`
  starts or stops it and is called after every record update and on app
  start.
- Watchdog: every 30 s the service checks `isRunning` for each keep-alive
  clone. For a dead one it calls `repository.launch` **only if**
  `Settings.canDrawOverlays` is true (background activity starts are blocked
  on Android 10+ otherwise); if not, it posts a notification "<name> stopped
  — tap to restart" whose `PendingIntent` targets the trampoline. At most
  one automatic restart per clone per 2 minutes.
- `BootReceiver` (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` not needed;
  also `MY_PACKAGE_REPLACED`): calls `KeepAliveController.sync`. With
  targetSdk 28, `startForegroundService` from the receiver is allowed.
- Permissions in manifest: `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`,
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK` (most already merged from Bcore).
- Guide screen, a checklist with live status and a button per row:
  notifications allowed; battery optimisation ignored
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); display over other apps
  (`ACTION_MANAGE_OVERLAY_PERMISSION`), explained as "needed to restart
  clones automatically"; vendor auto-start page.
- `VendorSettings.autoStartIntents(manufacturer): List<Intent>` returns
  explicit component intents for Xiaomi/Redmi/POCO, Huawei/Honor, Oppo/
  Realme/OnePlus, Vivo/iQOO, Samsung, in priority order; the screen tries
  each with `resolveActivity` + try/catch and falls back to
  `ACTION_APPLICATION_DETAILS_SETTINGS`. Pure function of the manufacturer
  string, unit-tested for known brands and unknown brand (empty list).
- Turning keep-alive on for the first time opens the guide.

Tests: `VendorSettingsTest`; `WatchdogPolicyTest` for a pure
`WatchdogPolicy.decide(now, lastRestartAt, running, canDrawOverlays)`
returning `None | Restart | Notify`.

## Task 4 — location and device profile

Files: `location/LocationActivity` + ViewModel, `location/Coordinates.kt`,
`device/DeviceProfiles.kt`, `device/DeviceProfileActivity` + ViewModel,
detail screen entries.

- Location screen: switch, latitude and longitude fields; accepts decimal
  degrees or a pasted `"lat, lng"` pair; `Coordinates.parse` validates
  ranges (lat ±90, lng ±180) and rejects `0,0` with a hint (the engine
  treats it as empty). Saved to the record; applied by `repository.launch`.
  `applyLocation`: enabled → `setLocation` then `setPattern(OWN_MODE)`;
  null or disabled → `setPattern(CLOSE_MODE)`. Note on screen: takes effect
  on next launch. Map picker is deferred.
- `DeviceProfiles`: `presets: List<DeviceProfile>` (about ten common phones,
  brand/manufacturer/model/device/product/board/hardware/fingerprint, no
  identifiers); `randomIdentifiers(random): Identifiers` with `androidId`
  (16 lowercase hex), `imei` (15 digits, valid Luhn), `serial` (12 upper
  alphanumerics), `wifiMac` (locally administered, unicast);
  `withFreshIdentifiers(profile, random)`; validators `isValidImei`,
  `isValidAndroidId`, `isValidMac`.
- Device profile screen: "Use real device" (profile = null) or pick a
  preset; identifiers shown and editable with validation; "Randomise
  identifiers". Saved to the record; applied by `repository.launch` through
  `BDeviceManager.setProfile` / `clearProfile`. Note: takes effect the next
  time the clone starts; offer "Stop clone now" when it is running.

Tests: `CoordinatesTest`, `DeviceProfilesTest` (Luhn validity over 1000
seeds, MAC bits, lengths, presets have no identifiers and unique names).

## Manual device checklist (per task)

1. Install, open; clone an installed app twice; both launch with separate
   data; stop, clear data, uninstall; clone from an APK file; bad APK shows
   an error.
2. Rename; text icon; image icon; reorder survives restart; pinned shortcut
   launches the right clone and follows a rename.
3. Enable keep-alive; notification stays; reboot → service returns; kill a
   clone → restarted (overlay permission) or notified (without); vendor
   page opens.
4. Clone with a location shows it in a maps app; clone with a profile shows
   the chosen model and identifiers in a device-info app; another clone of
   the same app shows different values.

## Unverified until run on a device

Everything that involves the engine at runtime: install and launch on the
target Android version, `isPackageRunning`, location and profile taking
effect, non-exported shortcut trampoline on the user's launcher, automatic
restart from the background, vendor settings component names.
