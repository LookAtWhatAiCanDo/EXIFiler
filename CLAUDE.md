# CLAUDE.md — EXIFiler AI Agent Instructions

## What this project is
EXIFiler is a Kotlin Multiplatform (KMP) background-service app that watches the Android
Downloads folder for media files from Meta AI Glasses (JPEG photos and MP4 videos) and
automatically moves them to a dedicated folder (`DCIM/EXIFiler/` by default). The user can
configure the target folder and toggle the service on/off from a minimal Compose UI.

## Repository layout
```
shared/src/commonMain/kotlin/com/exifiler/
  DetectionResult.kt        # Sealed class: Match(deviceName) / NoMatch / Unsupported
  MediaMoveRequest.kt       # Data class for a pending move
  MetadataDetector.kt       # Pure-Kotlin EXIF (JPEG) + MP4 box parser — NO platform imports
  PreferencesRepository.kt  # expect class

shared/src/androidMain/kotlin/com/exifiler/
  PreferencesRepository.android.kt  # actual — DataStore-backed

shared/src/iosMain/kotlin/com/exifiler/
  PreferencesRepository.ios.kt      # actual — TODO stubs for Phase 2

androidApp/src/main/java/com/exifiler/android/
  EXIFilerService.kt        # ForegroundService, ContentObserver on MediaStore.Downloads
  MediaMover.kt             # Scoped-storage copy+delete (ContentResolver / MediaStore)
  MediaScannerHelper.kt     # Triggers MediaStore re-index after a move
  ServiceManager.kt         # start/stop helpers + BootReceiver
  AppPreferencesManager.kt  # DataStore preferences + 10-entry activity log
  MainActivity.kt           # Compose UI: switch, folder picker, scrollable log
```

## Non-negotiable rules
1. **`shared/commonMain` must stay platform-free.** Never add `android.*`, `androidx.*`,
   `apple.*`, or `platform.*` imports to commonMain files.
2. **Scoped storage only.** All file access in the Android module uses `ContentResolver` /
   `MediaStore`. Raw `File` / `java.io.File` paths are forbidden for user media.
3. **`expect`/`actual` constructors must match.** The `expect class PreferencesRepository`
   and every `actual` must declare identical constructor signatures.
4. **`BootReceiver` requires `goAsync()`.** Any `BroadcastReceiver` that launches coroutines
   must call `goAsync()` and `pendingResult.finish()` to survive past `onReceive()`.
5. **`MetadataDetector` must be robust.** Use `source.request(n)` before every read call
   to handle truncated/corrupt files without throwing exceptions.

## Detection signatures (do not alter without updating tests)
| Format | Field | Match condition |
|--------|-------|-----------------|
| JPEG | EXIF IFD0 Make (tag 0x010F) | `== "Meta"` (case-insensitive, strip null bytes) |
| MP4 | `udta/©cmt` box content | contains `"device=Ray-Ban Meta Smart Glasses"` |

## Build & run
```bash
# Build debug APK
./gradlew :androidApp:assembleDebug

# Build shared module only
./gradlew :shared:assembleDebug

# Run unit tests
./gradlew test

# Lint
./gradlew :androidApp:lintDebug
```

## Phase roadmap
| Phase | Target | Status |
|-------|--------|--------|
| 1 | KMP Android | ✅ Complete |
| 2 | KMP iOS | 🔲 Planned |
| 3 | Swift Multiplatform iOS | 🔲 Planned |
| 4 | Swift Multiplatform Android | 🔲 Planned |

## Dependency policy
All dependency versions live in `gradle/libs.versions.toml`. Do not hard-code version
strings in individual `build.gradle.kts` files. Run `./gradlew dependencyUpdates` (if the
Versions plugin is added) before bumping versions.
