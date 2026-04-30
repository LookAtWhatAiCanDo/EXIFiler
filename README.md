# EXIFiler

A Kotlin Multiplatform (KMP) Android app that automatically organises media files produced by **Meta AI Glasses (Ray-Ban Smart Glasses)**. It watches the device's `Downloads` folder in the background and silently moves matching photos and videos to a configurable destination folder (`DCIM/EXIFiler/` by default).

---

## Features

- **Automatic detection** — identifies Meta AI Glasses files by inspecting EXIF metadata (JPEG) and MP4 box atoms (MP4/MOV), not just filenames.
- **Background service** — runs as a foreground service with a persistent notification; survives device reboots.
- **Configurable destination** — pick any folder on internal storage via the system folder picker.
- **Activity log** — in-app scrollable log showing recent file moves.
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

1. `EXIFilerService` registers a `ContentObserver` on `MediaStore.Downloads`.
2. When a new JPEG or MP4 appears, it opens a buffered stream and calls `MetadataDetector.detect()`.
3. On a `DetectionResult.Match`, `MediaMover` copies the file to the destination folder via `ContentResolver`, then deletes the original.
4. `MediaScannerHelper` triggers a `MediaStore` re-index so the moved file appears in the gallery immediately.
5. `BootReceiver` restarts the service automatically after a reboot.

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│              shared (commonMain — pure Kotlin)        │
│  MetadataDetector  DetectionResult  MediaMoveRequest  │
│  PreferencesRepository (expect)                       │
└────────────────┬──────────────────────────────────────┘
                 │ androidMain (DataStore actual)
                 ▼
┌──────────────────────────────────────────────────────┐
│              androidApp                               │
│  EXIFilerService ──► MediaMover ──► MediaScannerHelper│
│  ServiceManager + BootReceiver                        │
│  AppPreferencesManager   MainActivity (Compose UI)    │
└──────────────────────────────────────────────────────┘
```

### Module overview

| Path | Purpose |
|------|---------|
| `shared/commonMain` | Detection logic, models, `expect` declarations — zero platform imports |
| `shared/androidMain` | `actual PreferencesRepository` backed by DataStore |
| `shared/iosMain` | iOS stubs (Phase 2) |
| `androidApp` | Android app — service, file mover, Compose UI |

---

## Requirements

- **Android 10+** (minSdk 29)
- Permissions requested at runtime:
  - `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (Android 13+) or `READ_EXTERNAL_STORAGE` (≤ Android 12)
  - `POST_NOTIFICATIONS` (Android 13+)
  - `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`
  - `RECEIVE_BOOT_COMPLETED`
- Optional but recommended: **Manage Media** (`MediaStore.canManageMedia`) — required on Android 12+ to silently delete source files. The app shows a banner and deep-links to Settings if this is missing.

---

## Build

```bash
# Debug APK
./gradlew :androidApp:assembleDebug

# Shared module only
./gradlew :shared:assembleDebug

# Unit tests (all modules)
./gradlew test

# Android Lint
./gradlew :androidApp:lintDebug
```

Output APK: `androidApp/build/outputs/apk/debug/`

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

| Workflow | Trigger | Steps |
|----------|---------|-------|
| `build.yml` | Push to `main` / PR | Build shared module → Build Android app → Run tests → Upload APK artifact |
| `pr-check.yml` | PR open / sync | Lint → Build → Test |

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

