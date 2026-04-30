# EXIFiler

A Kotlin Multiplatform (KMP) Android app that monitors a device's folder(s) for matching filename(s) and EXIF metadata and moves them to a destination folder.    
It was originally intended to solve the problem of the **Meta AI App** importing all **Meta AI Glasses** media files to the `Download` folder.  

---

## Features

- **Automatic detection** — identifies Meta AI Glasses files by inspecting EXIF metadata (JPEG) and MP4 box atoms (MP4/MOV), not just filenames.
- **Background service** — runs as a foreground service with a persistent notification; survives device reboots.
- **Configurable destination** — pick any folder on internal storage via the system folder picker.
- **Activity log** — in-app scrollable log showing the 10 most recent file operations.
- **Scoped storage** — all file I/O uses `ContentResolver`/`MediaStore`; no raw filesystem paths.

---

## How It Works

### Detection logic

| Format | Metadata field | Match condition |
|--------|---------------|----------------|
| JPEG / JPG | EXIF IFD0 Make tag (`0x010F`) | equals `"Meta"` (case-insensitive, null-trimmed) |
| MP4 / MOV | `udta/©cmt` box content | contains `"device=Ray-Ban Meta Smart Glasses"` |

The detector is implemented in pure Kotlin in the `shared` module (`MetadataDetector.kt`) with no platform dependencies, making it fully portable.

### Service flow

1. `EXIFilerService` starts as a foreground service and registers `ContentObserver` instances on **three** `MediaStore` collections — `Downloads`, `Images`, and `Video` — because some devices index Download-folder media under `Images`/`Video` rather than `Downloads`.
2. On each change notification, `scanDownloads()` queries all three collections for JPEG/MP4 candidates in the `Download/` relative path.
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

> **Note:** No unit tests have been written yet. The `./gradlew test` task succeeds but runs nothing.

### Key versions

| Dependency | Version |
|------------|---------|
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.5.2 |
| Compose BOM | 2024.09.03 |
| Coroutines | 1.8.1 |
| DataStore | 1.1.1 |
| Okio | 3.9.0 |

All versions are managed in `gradle/libs.versions.toml`.

---

## CI

| Workflow | Trigger | Jobs |
|----------|---------|------|
| `build.yml` | Push to `main` / PR | Build shared module → Build Android app → Run tests → Upload APK artifact (on push) |
| `pr-check.yml` | PR open / sync | **lint** (Android Lint) and **build-and-test** (assembleDebug + test) run in parallel |

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

* Add ability to clear all Recent Activity
* Add ability to clear specific Recent Activity
* Add ability to define separate:
  * input folder to monitor
  * file spec(s) to match
  * EXIF metadata value(s) to match
  * output folder
* Change Service Running description to be more generic and not say "Monitor Downloads for Meta AI Glasses files"
* Add pre-defined input/filename/EXIF/output setting presets
  * e.g. "Meta AI Glasses Download → DCIM/Meta AI"
* Add unit tests for `MetadataDetector` (JPEG EXIF parsing and MP4 box parsing)

