# toadFILES

A file manager for Android that stays out of your way. No ads, no tracking, no cloud, and no internet permission at all, so it can't phone home even if it wanted to.

I built it because I wanted something that opens fast, browses real folders instead of a fake "recent files" view, and doesn't nag me. It works on actual files in actual directories and tells you honestly when something fails.

## What it does

- Browse any readable folder on internal storage, SD cards and USB drives. Breadcrumbs, hidden-file toggle, list or grid.
- Search the current folder tree. There's a settings toggle to also search inside small text files, and you can set your own result limit.
- Copy, move, rename, share, zip/unzip, delete. Deleted items land in a per-volume trash and stay there for 30 days, with undo.
- Sort by name, size, date or type. Filter down to documents, images, videos, audio, archives or APKs.
- Favorites that follow renames and moves, and get cleaned up when the target is gone.
- Storage analyzer and smart-recent files on Home (computed on demand, never in the background).
- Long-press a file for a Mi-Files-style action sheet: open, share, rename, copy, move, delete, details, zip, favorite, copy path, open with, or start a multi-select.
- Share is local-only: a long-press "Share" copies the chosen file(s) into an app-private "shared" folder on this device. Nothing leaves the device, and the About screen shows what's there with a one-tap clear.
- Material You dynamic color on Android 12+, or pick one of seven accent colors (toad, blue, purple, green, orange, red, pink) when dynamic color is off.
- About page with version/build, storage stats, per-category counts, OSS credits, an inline changelog, a diagnostics toggle, and a "clear locally-shared copies" action.
- APK install handoff to the system installer.

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
├── domain/    # pure Kotlin: models, sorting, path safety, file ops, ZIP handling, AccentColor
├── data/      # java.io, MediaStore, DataStore wrappers, SharedRepository (local-only share)
├── ui/        # Compose screens and shared components (FileActionSheet, EmptyState, dialogs)
│   ├── about/ # About page (version/build, storage, credits, changelog, diagnostics, shared)
│   ├── home/  # Home tab: storage, quick links, favorites, smart-recent
│   └── theme/ # ToadTheme + AccentPalettes (7 hand-tuned light/dark color schemes)
├── util/      # share/open intents, video thumbnails
└── ToadApp.kt # DI container, hand-rolled on purpose
```

Two decisions worth knowing about: nothing touches the filesystem until a screen asks for it (no startup scan), and every copy/move/delete/extract reports what actually happened per item, failures included. No Hilt, no Firebase, no network stack. Coil pulls in thumbnails and that's about it.

## License

GPL-3.0. See [LICENSE](LICENSE).
