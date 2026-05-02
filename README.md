# EXIFiler

A Kotlin Multiplatform (KMP) Android app that monitors a device's folder(s) for matching filename(s) and EXIF metadata and moves them to a destination folder.    
It was originally intended to solve the problem of the **Meta AI App** importing all **Meta AI Glasses** media files to the `Download` folder.  

---

## Features

- **Automatic detection** — identifies Meta AI Glasses files by inspecting EXIF metadata (JPEG) and MP4 box atoms (MP4/MOV), not just filenames.
- **Background service** — runs as a foreground service with a persistent notification; survives device reboots.
- **Configurable destination** — pick any folder on internal storage via the system folder picker.
- **Activity log** — in-app scrollable log showing the 100 most recent file operations.
- **Scoped storage** — all file I/O uses `ContentResolver`/`MediaStore`; no raw filesystem paths.

---

## How It Works

### Detection logic

Detection is profile-driven. Each `MonitoringProfile` defines an `exifFilters` map of
EXIF key/value pairs that must all match. The default profile targets Meta AI Glasses:

| Format | Metadata field | Default match condition |
|--------|---------------|------------------------|
| JPEG / JPG | EXIF IFD0 string tag (Make, Model, Software, Artist, or Copyright) | all `exifFilters` entries must match (case-insensitive) |
| MP4 / MOV | `udta/©cmt` box content | `Make=Meta` maps to the Ray-Ban `device=` atom check |

An empty `exifFilters` map matches **all** files of the supported types — useful for
profiles that move everything in a folder regardless of metadata.

The detector is implemented in pure Kotlin in the `shared` module (`MetadataDetector.kt`) with no platform dependencies, making it fully portable.

### Service flow

1. `EXIFilerService` starts as a foreground service and registers `ContentObserver` instances on **three** `MediaStore` collections — `Downloads`, `Images`, and `Video` — because some devices index Download-folder media under `Images`/`Video` rather than `Downloads`.
2. On each change notification, `MediaScanner.scan()` queries all three collections for candidates matching each active profile's input folder and file patterns.
3. Each candidate URI is checked against an in-memory LRU cache (`processedUris`, max 500 entries) so already-processed files are skipped.
4. For new files, `MetadataDetector.detect()` reads the file bytes through a buffered `okio` stream and returns one of:
   - `DetectionResult.Match(deviceName)` — file is from a Meta AI Glasses device
   - `DetectionResult.NoMatch` — file is a supported format but not from a matching device
   - `DetectionResult.Unsupported` — file extension is not handled
5. On a `Match`, `MediaMover.moveFile()` copies the file to the destination folder via `ContentResolver` and attempts to delete the source. The result is one of three states:
   - `MoveResult.Success` — copy and delete both succeeded
   - `MoveResult.CopiedDeletePending(sourceUri)` — copy succeeded but delete was denied (for example, on Android 12+/API 31+ when `MANAGE_MEDIA` has not yet been granted); the URI is queued for retry
   - `MoveResult.Failure` — copy itself failed
6. Pending-delete URIs are retried at the start of every subsequent scan. On Android 12+/API 31+, if deletion was previously blocked only because **Manage Media** was not granted, those retries can succeed automatically after the user grants that permission. On Android 11/API 30, `MANAGE_MEDIA` does not exist, and the current implementation only retries `contentResolver.delete` (it does not launch a user-confirmed `MediaStore.createDeleteRequest` flow), so originals may remain in `Downloads`.
7. `MediaScannerHelper` notifies `MediaStore` of the new file so it appears in the gallery immediately.
8. `BootReceiver` reads the saved `service_enabled` preference via DataStore and restarts the service after a device reboot if the toggle was on.
9. When `MainActivity` detects that **Manage Media** was just granted (via `onResume`) on supported Android versions, it calls `ServiceManager.requestScan()` which sends an `ACTION_SCAN_NOW` intent to trigger an immediate retry of any pending deletes. On Android 11/API 30, this rescan still occurs, but it does not by itself provide the user-confirmed delete flow needed for many source files.
10. If files were scanned but none matched, a summary entry (`Scan: N file(s) checked — 0 matched Meta Glasses criteria`) is written to the activity log so the user can confirm the service is actively watching.

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│              shared (commonMain — pure Kotlin)        │
│  MetadataDetector  DetectionResult  MediaMoveRequest  │
│  PreferencesRepository (expect)                       │
└────────────────┬──────────────────────────────────────┘
                 │ androidMain (DataStore actual + AppContextHolder)
                 ▼
┌──────────────────────────────────────────────────────┐
│              androidApp                               │
│  EXIFilerApp (initialises AppContextHolder)           │
│  EXIFilerService ──► MediaMover ──► MediaScannerHelper│
│  ServiceManager + BootReceiver                        │
│  AppPreferencesManager   MainActivity (Compose UI)    │
└──────────────────────────────────────────────────────┘
```

### Module overview

| Path | Purpose |
|------|---------|
| `shared/commonMain` | Detection logic, models, `expect` declarations — zero platform imports |
| `shared/androidMain` | `actual PreferencesRepository` backed by DataStore; `AppContextHolder` provides `Context` to the shared module |
| `shared/iosMain` | iOS stubs (Phase 2) |
| `androidApp` | Android app — service, file mover, Compose UI |

`AppContextHolder` is an object in `shared/androidMain` that holds the application `Context`. `EXIFilerApp.onCreate()` populates it so the shared `PreferencesRepository` actual can access DataStore without a platform constructor parameter.

---

## Requirements

- **Android 10+** (minSdk 29)
- Manifest-declared permissions:
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_DATA_SYNC`
  - `RECEIVE_BOOT_COMPLETED`
- Permissions requested at runtime:
  - `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (Android 13+) or `READ_EXTERNAL_STORAGE` (≤ Android 12)
  - `POST_NOTIFICATIONS` (Android 13+)
- Optional but recommended: **Manage Media** (`MediaStore.canManageMedia`) — required on Android 12+ to silently delete source files without a per-file confirmation prompt. The app shows a banner and deep-links to Settings if this permission is missing; once granted, pending deletions are retried immediately.

---

## Build

```bash
# Debug APK
./gradlew :androidApp:assembleDebug

# Shared module only
./gradlew :shared:assembleDebug

# Android Lint
./gradlew :androidApp:lintDebug
```

Output APK: `androidApp/build/outputs/apk/debug/`

### Key versions

| Dependency | Version |
|------------|---------|
| Kotlin | 2.2.10 |
| Android Gradle Plugin | 9.2.0 |
| Compose BOM | 2024.09.03 |
| Coroutines | 1.8.1 |
| DataStore | 1.1.1 |
| Okio | 3.9.0 |

All versions are managed in `gradle/libs.versions.toml`.

---

## CI

| Workflow | Trigger | Jobs |
|----------|---------|------|
| `build.yml` | Push to `main` | Build shared module → Build Android app → Run tests → Lint → Upload APK artifact |
| `pr-check.yml` | PR open / sync | **lint** (Android Lint) and **build-and-test** (assembleDebug + test) run in parallel |
| `release.yml` | Push of `v*` semver tag / manual dispatch | Build signed release APK + AAB → create GitHub Release with artefacts |

---

## How to Release

1. Bump `versionCode` and `versionName` in `androidApp/build.gradle.kts`, commit, and push to `main`.
2. Tag the commit and push the tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. The **Release** workflow builds a signed APK and AAB and publishes them as a GitHub Release automatically.

> **One-time setup required:** the release workflow needs four GitHub Secrets to sign the build.
> See [`docs/RELEASING.md`](docs/RELEASING.md) for the full guide — keystore generation, secret storage, key rotation, and the future iOS release checklist.

---

## Phase Roadmap

| Phase | Target | Status |
|-------|--------|--------|
| 1 | KMP Android | ✅ Complete |
| 2 | KMP iOS | 🔲 Planned |
| 3 | Swift Multiplatform iOS | 🔲 Planned |
| 4 | Swift Multiplatform Android | 🔲 Planned |

---

## TODO

* Add JPEG unit tests for `MetadataDetector` (synthetic EXIF byte array coverage)
* Add folder suggestion shortcuts in the profile editor
* Add permission denial feedback (banner when READ_MEDIA_IMAGES/VIDEO is denied)
* Migrate activity log storage from newline-delimited string to JSON array
* Add adb hack for truly persistent scanning icon
