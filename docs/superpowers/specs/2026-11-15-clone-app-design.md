# CloneLab — app cloner design

Date: 2026-11-15. Status: approved.

## Purpose

A personal-experiment Android app that runs several independent copies of an
app on one phone, lets the user personalise each copy, keeps copies alive in
the background, and gives each copy its own location and device profile.

"CloneLab" is a working name.

## Provenance

The feature list was derived from the manifest, resources and UI strings of a
commercial cloner. Its code was not unpacked, decompiled or copied. No asset,
string, layout or identifier from it is used here.

The virtualization engine is the Apache-2.0 project
[NewBlackbox](https://github.com/ALEX5402/NewBlackbox), vendored under
`engine/` at commit `89b59836c66f173756a4ae258cf379a957649820`. Its licence and
attribution are kept. Everything outside `engine/` is original.

## Scope

In scope:

1. **Core cloning.** Clone an installed app or an APK file. Several clones per
   app, each with separate data. Launch, stop, clear data, uninstall.
2. **Personalisation.** Rename. Custom image icon or coloured text icon. Clone
   number and sort order. Pinned home-screen shortcut that launches the clone.
3. **Keep-alive.** Foreground service with a persistent notification, start on
   boot, battery-optimisation exemption request, a guide screen linking to the
   phone maker's background settings, and restart of clones flagged keep-alive.
4. **Location.** Per-clone on/off with latitude and longitude. Typed
   coordinates first; a map picker later.
5. **Device profile.** Per-clone brand, model and identifiers, from presets or
   randomised, applied when the clone starts.

Out of scope: privacy space, backup and cloud sync, cross-app login routing,
accounts, payment, ads, cloud phone, watermark remover, and any tool that
clears another service's account-restriction markers.

## Constraints

- Test devices run Android 14 or older.
- Sideloaded only. The engine requires `targetSdk 28`, which rules out Play.
- Engine build needs JDK 21, compile SDK 35, NDK 29.0.13846066.
- ABIs: `arm64-v8a` and `armeabi-v7a`.

## Structure

```
engine/            vendored NewBlackbox modules (Bcore, black-reflection, compiler)
app/               our Kotlin app
docs/              this spec and the implementation plan
```

The app talks to the engine only through `CloneEngine`. No other class imports
engine types.

## Components

| Component | Responsibility | Depends on |
|---|---|---|
| `CloneEngine` (interface) and `BlackBoxCloneEngine` | Install from package or APK, list, launch, stop, clear data, uninstall, apply location and device profile | `Bcore` |
| `CloneStore` | Persist per-clone settings: name, number, order, icon choice, location, device profile, keep-alive flag | Android storage only |
| `CloneRepository` | Combine engine state and stored settings into the list the UI shows | `CloneEngine`, `CloneStore` |
| Home screen | Grid of clones, add-clone flow, per-clone detail screen | `CloneRepository` |
| `ShortcutPublisher` and trampoline activity | Pin a shortcut; launch the clone it names | `CloneRepository` |
| `KeepAliveService`, boot receiver, guide screen | Foreground notification, boot start, exemption request, vendor settings links, restart of flagged clones | `CloneRepository` |
| `DeviceProfiles` | Preset list and random profile generation; pure logic | none |

A clone is identified by `(packageName, userId)`, where `userId` is the
engine's virtual user. Clone number is display-only.

## Data flow

Add clone: user picks an installed app or APK → `CloneRepository.add` asks the
engine for a free virtual user and installs → on success a settings record is
created → list refreshes.

Launch: UI or shortcut → repository loads the record → engine applies location
and device profile for that virtual user → engine launches.

Boot: boot receiver starts `KeepAliveService` → service launches every clone
flagged keep-alive, then watches for process death.

## Errors

Install failure (unsupported ABI, split APK, corrupt file), launch timeout and
engine crash are reported per clone with the reason. A failing clone never
takes down the home screen. A stored record whose clone no longer exists in
the engine is shown as broken, with a remove action.

## Testing

- Unit tests: `CloneStore`, `DeviceProfiles`, and `CloneRepository` against a
  fake `CloneEngine`.
- Device tests: a manual checklist per milestone over USB debugging. Cloning
  real apps cannot be tested meaningfully off-device.

## Build order

1. Toolchain; build the stock engine demo unchanged and run it on the phone.
2. Core cloning.
3. Personalisation.
4. Keep-alive.
5. Location and device profile.

## Risks

- The engine may misbehave on a particular phone or app. Step 1 exposes this
  before further investment.
- Device-profile spoofing may be only partly configurable per clone in the
  engine; closing the gap means patching `engine/`.
- Vendor background restrictions can defeat keep-alive regardless of what the
  app does; the guide screen is the mitigation.
