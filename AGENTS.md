# AGENTS.md — EXIFiler AI Agent Guide

## Project summary
**EXIFiler** detects media files produced by Meta AI Glasses (Ray-Ban Smart Glasses) in the
Android `Downloads` folder and moves them to a user-configured destination (`DCIM/EXIFiler/`
by default). Detection is based on EXIF Make == "Meta" for JPEGs and a `©cmt` MP4 atom
containing `"device=Ray-Ban Meta Smart Glasses"` for videos.

The project is a **Kotlin Multiplatform (KMP)** app. Phase 1 targets Android; Phases 2–4
target iOS (KMP then Swift).

---

## Architecture at a glance

```
┌──────────────────────────────────────────────────────┐
│              shared (commonMain)                     │
│  MetadataDetector  DetectionResult  MediaMoveRequest │
│  PreferencesRepository (expect)                      │
└────────────────┬─────────────────┬───────────────────┘
                 │ androidMain      │ iosMain
                 │ (DataStore)      │ (TODO stubs)
                 ▼
┌──────────────────────────────────────────────────────┐
│              androidApp                              │
│  EXIFilerService ─► MediaMover ─► MediaScannerHelper │
│  ServiceManager + BootReceiver                       │
│  AppPreferencesManager  MainActivity (Compose)       │
└──────────────────────────────────────────────────────┘
```

---

## Invariants — always maintain these

| # | Rule |
|---|------|
| 1 | `shared/commonMain` has zero platform imports (`android.*`, `androidx.*`, `apple.*`) |
| 2 | File I/O on Android uses `ContentResolver`/`MediaStore` only (no raw `File` paths) |
| 3 | `expect PreferencesRepository` and every `actual` share the same constructor signature |
| 4 | `BroadcastReceiver.onReceive` that launches coroutines calls `goAsync()` first |
| 5 | `MetadataDetector` uses `source.request(n)` guards — never throws on corrupt files |
| 6 | `processedUris` in `MediaScanner` is bounded (LRU ≤ 500) and accessed under a `Mutex` |

---

## How to make changes

### Adding a new file type
1. Update `MetadataDetector.detect()` — add a new extension branch and a private `detectXxx()`.
2. Update `MediaMover.guessMimeType()` with the new MIME type.
3. Add the MIME type to `MediaScanner.scanForProfile()` selection args.
4. Add unit tests in `shared/src/jvmTest/`.

### Adding a new detected device
Update the constants in `MetadataDetector`:
```kotlin
private const val META_MAKE = "Meta"              // JPEG Make tag
private const val RAY_BAN_DEVICE_PREFIX = "device=Ray-Ban Meta Smart Glasses"  // MP4 comment
```

### Updating the UI
`MainActivity.kt` contains all Compose UI. The `MainViewModel` holds state via `StateFlow`.
Keep the UI minimal — 3 elements: service toggle, folder picker, activity log.

### Adding the iOS actual (Phase 2)
Replace the `TODO()` bodies in `shared/src/iosMain/kotlin/com/exifiler/PreferencesRepository.ios.kt`
with `NSUserDefaults.standardUserDefaults` calls wrapped in a `@ObjCName` class.

---

## Build commands
```bash
./gradlew :androidApp:assembleDebug     # Build debug APK
./gradlew :shared:assembleDebug         # Build shared module only
./gradlew test                          # Unit tests (all modules)
./gradlew :androidApp:lintDebug         # Android Lint
./gradlew --stop                        # Kill Gradle daemon
```

## CI
| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `build.yml` | push to main | Build + test + lint + upload APK artifact |
| `pr-check.yml` | PR open/sync | Lint + build + test for every PR |

---

## Dependency versions
All versions are in `gradle/libs.versions.toml`. Do not add version strings directly to
`build.gradle.kts` files. Key versions:

| Dependency | Version |
|------------|---------|
| Kotlin | 2.2.10 |
| AGP | 9.2.0 |
| Compose BOM | 2024.09.03 |
| okio | 3.9.0 |
| DataStore | 1.1.1 |
| Coroutines | 1.8.1 |
