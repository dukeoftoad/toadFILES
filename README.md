# toadFILES 🐸

A lightweight, fast, **privacy-first file manager for Android** — no ads, no tracking, no cloud, no internet permission.

toadFILES blends the simplicity of Fossify File Manager, the category/storage insights of Mi File Manager, the clean navigation of iOS Files, and a Material 3 design with an original toad-in-a-folder identity.

## Feature status

| Feature | Status |
|---|---|
| Home (storage summary, quick access, favorites, category overview) | ✅ Implemented |
| Real filesystem browsing (any readable folder, breadcrumbs) | ✅ Implemented |
| Search (debounced, cancellable, scoped to the current folder tree) | ✅ Implemented |
| Sort (natural order: name / size / date / type, asc/desc, dirs first) | ✅ Implemented |
| Filter (docs / images / videos / audio / archives / APKs) | ✅ Implemented |
| Multi-select + Copy / Move / Rename / Delete / Share / Properties | ✅ Implemented |
| Conflict handling (keep-both / replace / skip) with honest result reports | ✅ Implemented |
| Self-copy / move-into-itself protection | ✅ Implemented |
| ZIP create + safe extract (ZIP-slip guard, bomb limits, no silent overwrite) | ✅ Implemented |
| Categories via MediaStore (images, videos, audio, docs, archives, APKs) | ✅ Implemented |
| Recent files (MediaStore, on-demand) | ✅ Implemented |
| Storage analyzer (top-level folder sizes, computed on demand, cancellable) | ✅ Implemented |
| Favorites (pinned locally via DataStore, follow renames & moves) | ✅ Implemented |
| Trash with undo — deletes move to a per-volume trash, kept 30 days, auto-purged | ✅ Implemented |
| Browse thumbnails — images (Coil) + video frames (MediaMetadataRetriever) | ✅ Implemented |
| Grid / list view toggle in Browse (persisted) | ✅ Implemented |
| SD card & USB-OTG volumes (StorageManager) with per-volume stats, analyzer & trash | ✅ Implemented |
| APK install handoff to the system package installer | ✅ Implemented |
| Share from Recent & Categories + "Show in folder" jump from Categories | ✅ Implemented |
| Theme: light / dark / follow system + Material You dynamic color | ✅ Implemented |
| Privacy: no INTERNET permission, no analytics, no background scans | ✅ Implemented |
| Encrypted Vault (Keystore + biometrics) | 🧭 Planned — omitted from v1 until it can be done safely |

## Requirements

- Android 8.0+ (API 26); primary target is Android 16 (API 36)
- "All files access" permission (standard for file managers on Android 11+); legacy storage permission on Android 8–10

## Build

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # 127 JVM tests (JUnit + Robolectric + Compose E2E)
./gradlew :app:lintDebug              # Android lint
```

Or open the project in Android Studio (Koala or newer) and press Run.

## Architecture

Small, boring, on purpose:

```
app/src/main/java/com/toadfiles/app/
├── domain/        # Pure Kotlin: models, natural sorting, path safety,
│                  # file operations engine, safe ZIP support (all unit-tested)
├── data/          # FileRepository (java.io), MediaRepository (MediaStore),
│                  # SettingsRepository (DataStore), session Clipboard
├── ui/            # Compose screens: home / browse / categories /
│                  # recent / storage / trash / settings + shared components
├── util/          # Real share/open intents (FileProvider), video thumbnails
└── ToadApp.kt     # Hand-rolled DI container — no framework
```

Design decisions:

- **No startup scanning.** Nothing touches the filesystem until a screen asks for it.
- **Honest operations.** Every copy/move/delete/extract reports real per-item results; failures are listed, never hidden.
- **Safety first.** Copy-into-itself guard, ZIP-slip canonical-path checks, zip-bomb entry/size/ratio caps, cancellation with partial-target cleanup.
- **Minimal dependencies.** AndroidX + Compose + DataStore + Coil for thumbnails. No Hilt, no Firebase, no network stack.

## Privacy

toadFILES requests no `INTERNET` permission. Favorites and preferences live in local DataStore. There is no account, no sync, no telemetry — the APK physically cannot phone home.

## CI

GitHub Actions runs on every push: JDK 17 + Gradle cache + wrapper validation + unit tests + lint + debug assemble (`.github/workflows/android.yml`).

## License

GPL-3.0 — see [LICENSE](LICENSE).
