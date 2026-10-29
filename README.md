# toadFILES

A file manager for Android that stays out of your way. No ads, no tracking, no cloud, and no internet permission at all, so it can't phone home even if it wanted to.

I built it because I wanted something that opens fast, browses real folders instead of a fake "recent files" view, and doesn't nag me. It works on actual files in actual directories and tells you honestly when something fails.

## What it does

- Browse any readable folder on internal storage, SD cards and USB drives. Breadcrumbs, hidden-file toggle, list or grid.
- Search the current folder tree. There's a settings toggle to also search inside small text files, and you can set your own result limit.
- Copy, move, rename, share, zip/unzip, delete. Deleted items land in a per-volume trash and stay there for 30 days, with undo.
- Sort by name, size, date or type. Filter down to documents, images, videos, audio, archives or APKs.
- Favorites that follow renames and moves, and get cleaned up when the target is gone.
- Storage analyzer, category counts and recent files, all computed on demand. Nothing scans in the background.
- APK install handoff to the system installer.
- Light / dark / follow-system theme with Material You colors.

Encrypted vault (Keystore plus biometrics) is on the list. It'll show up once I'm confident it can be done right.

## Install

Grab the APK from the [Releases](https://github.com/dukeoftoad/toadFILES/releases) page. Android 8.0 or newer is required. On Android 11+ the app asks for "All files access", which is the standard permission for a file manager.

## Building from source

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Android Studio (Koala or newer) works too. Tests are plain JVM tests running on Robolectric, so no emulator is needed.

## Project layout

Nothing clever here.

```
app/src/main/java/com/toadfiles/app/
├── domain/    # pure Kotlin: models, sorting, path safety, file ops, ZIP handling
├── data/      # java.io, MediaStore and DataStore wrappers
├── ui/        # Compose screens and shared components
├── util/      # share/open intents, video thumbnails
└── ToadApp.kt # DI container, hand-rolled on purpose
```

Two decisions worth knowing about: nothing touches the filesystem until a screen asks for it (no startup scan), and every copy/move/delete/extract reports what actually happened per item, failures included. No Hilt, no Firebase, no network stack. Coil pulls in thumbnails and that's about it.

## License

GPL-3.0. See [LICENSE](LICENSE).
