# GitHub Copilot Instructions for EXIFiler

## Project overview
EXIFiler is a Kotlin Multiplatform (KMP) app targeting Android (Phase 1) and iOS (Phase 2+).
It monitors the device Downloads folder for media files from Meta AI Glasses and automatically
moves them to a configurable destination folder (`DCIM/EXIFiler/` by default).

## Project structure
```
shared/          # KMP shared module — pure Kotlin, no platform imports
  commonMain/    # Detection logic, models, expect declarations
  androidMain/   # Android actuals (DataStore-backed PreferencesRepository)
  iosMain/       # iOS actual stubs (TODO — Phase 2)

androidApp/      # Android application module
  EXIFilerService.kt       # ForegroundService + ContentObserver on MediaStore.Downloads
  MediaMover.kt            # Scoped-storage copy+delete via ContentResolver
  MediaScannerHelper.kt    # MediaStore rescan after moves
  ServiceManager.kt        # Start/stop + BootReceiver
  AppPreferencesManager.kt # DataStore preferences + activity log
  MainActivity.kt          # Compose UI: toggle, folder picker, log
```

## Coding conventions
- **Shared module**: Pure Kotlin only. No `android.*`, `ios.*`, or any platform imports.
  Use `expect`/`actual` for platform-specific implementations.
- **Android module**: Use scoped storage exclusively (no raw file paths). All file I/O goes
  through `ContentResolver` and `MediaStore` APIs.
- **Coroutines**: Use `Dispatchers.IO` for file/disk work. Use `SupervisorJob` in services.
- **State management**: Flow + StateFlow via ViewModel and DataStore. Avoid LiveData.
- **UI**: Jetpack Compose only. No XML layouts.
- **Build**: Kotlin DSL (`.kts`) for all Gradle files. Use the version catalog in
  `gradle/libs.versions.toml` for all dependency versions.

## Detection logic (do not change without updating tests)
- **JPEG**: Match EXIF IFD0 Make tag (0x010F) == `"Meta"` (case-insensitive, null-trimmed).
- **MP4**: Match `udta/©cmt` atom Comment contains `"device=Ray-Ban Meta Smart Glasses"`
  (no trailing generation number — intentionally forward-compatible with Gen 3+).

## Key constraints
- Never use raw filesystem paths on Android 10+ (`minSdk = 29`).
- Do not add Android or iOS platform imports to `shared/commonMain`.
- `PreferencesRepository` `expect` and `actual` constructors must match exactly.
- The `BootReceiver` must call `goAsync()` before launching coroutines.
- `processedUris` in `EXIFilerService` is bounded (LRU, max 500) and must be accessed
  only on a single coroutine (use `Mutex` or single-thread dispatcher).

## Phase roadmap
1. ✅ KMP Android — current
2. KMP iOS
3. Swift Multiplatform iOS
4. Swift Multiplatform Android
