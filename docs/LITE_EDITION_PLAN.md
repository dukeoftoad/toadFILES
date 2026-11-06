# toadFILES Lite — Lightweight Edition Plan

**Goal:** a second APK ("toadFILES Lite") that keeps the fast file-manager core and
strips every feature that is polish rather than function — measurably lower APK
size, CPU, RAM and battery draw on low-end or battery-starved devices.

**Companion in-app feature (shipped now):** Settings → Performance mode. One toggle
in the full app: forces `MotionStyle.NONE` app-wide (no animations, no splash) and
skips video-thumbnail decoding via `LocalPerformanceMode`. Lite is "Performance
mode, baked into the build" — plus feature removal, which a toggle cannot do.

---

## 1. Ground truth (v2.2.0, measured)

| Fact | Value | Consequence for Lite |
| --- | --- | --- |
| Release APK | 4.59 MB | already small; the win must come from resources + deps, not code golf |
| Bundled fonts (`res/font`) | 1.9 MB (**41% of the APK**) | ship 2 of 7 families → save ~1.4 MB |
| App-icon system | 25 activity-aliases, 7 designs × 8 tints (180 KB XML + 32 KB foregrounds) + PackageManager alias work at startup | single alias → smaller manifest, less startup work |
| Video thumbnails | `coil-video` + `MediaMetadataRetriever` per grid cell, in-memory LruCache | the single biggest recurring CPU/RAM cost in the UI |
| 7z support | commons-compress 1.27.1 + xz 1.10 (pure Java) | ~1 MB of dex after R8; decode is pure CPU burn |
| Analytics/telemetry | none | nothing to strip — the app is already clean |
| Minification | R8 + resource shrinking ON | dead code strips only if features are **removed at build time**, not toggled at runtime |

**What Lite is NOT:** it is not a fork. One repo, one codebase, Gradle product
flavors. `full` keeps today's app byte-for-byte; `lite` is the stripped flavor.

---

## 2. Flavor architecture

```kotlin
// app/build.gradle.kts
android {
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }              // today's app, unchanged
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"                     // installs side-by-side
            versionNameSuffix = "-lite"
            resValue("string", "app_name", "toadFILES Lite")
        }
    }
}
```

- `src/main/` — everything shared (Browse, copy/move/delete/trash, favorites, sort,
  search, ZIP create/extract with all the security guards, checksums, Properties).
- `src/lite/` — res overrides (2 fonts, 1 icon, app name); no extra sources needed.
- Feature gating uses **`if (BuildConfig.FLAVOR_edition == "lite")` around route
  registration** in `ToadNavHost` and section rows in `SettingsScreen`. Because the
  gate is a compile-time-constant branch, R8 constant-folds it and strips the dead
  side — real dex savings, not just hidden screens.

---

## 3. What gets scraped (in order of payoff)

### Phase 1 — the resource diet (APK size, zero risk)
| Strip | How | Saves |
| --- | --- | --- |
| 5 of 7 font families (keep system + Toad) | fonts move to `src/full/res/font`; `AppFont` falls back to SYSTEM in lite via flavor check | ~1.4 MB |
| 24 launcher aliases (keep one toad icon) | `manifest.srcFile` override in lite, or single-alias manifest | ~100 KB + faster cold start (no alias resolution) |
| material-icons-extended | keep — R8 already strips unused glyphs | 0 (keep for the core icons) |

**Estimated result: ~4.6 MB → ~2.9–3.1 MB.**

### Phase 2 — the runtime diet (CPU / RAM / battery)
| Strip | Why it costs | How |
| --- | --- | --- |
| Video thumbnails | `MediaMetadataRetriever` decode per cell + `coil-video` + LruCache bitmaps | lite gates `VideoThumb` to the generic icon (same switch Performance mode uses); drop `coil-video` from lite deps |
| Categories / Media screens | per-category directory scans + cursor churn + large grid state | lite nav never registers `Routes.CATEGORIES`; the tab row hides it |
| Storage Analyzer | full-tree walk of the volume — the heaviest single operation in the app (CPU + I/O + battery) | lite nav never registers `Routes.STORAGE`; Home card hidden |
| Update checker / downloader / banner | GitHub network polling on every cold start, radio wakeups | lite never runs `StartupUpdateCheck`; users sideload updates manually |
| 7z support | commons-compress + xz dex, CPU-heavy LZMA decode | lite declares ZIP-only; `ArchiveOps.canExtract` routes .7z to "unsupported" |
| What's New screen + bundled changelog asset | parse + asset I/O per update | lite skips the route and the `syncChangelog` asset copy |
| App-icon switcher UI + logic | Settings section + PackageManager commits | lite hardcodes the icon |
| Themes & accents (keep Classic + system dark) | small code, but trims the Appearance tab to fit what remains | lite `AppTheme` set collapses to CLASSIC/DEFAULT |

**Motion in Lite:** `MotionStyle.NONE` is the only value — the Appearance tab's
Motion card is hidden, `LocalToadMotion` provides the snap/None `ToadMotion`, and
the splash early-return already ships. Zero animation frames, zero splash delay.

### Phase 3 — belt and braces
- `lint` flavor-aware baseline (one per flavor, checked in CI).
- Release gate runs `testLiteDebugUnitTest` too (Robolectric suite is flavor-blind —
  it exercises `src/main` shared logic on both).
- Optional: `android.enableR8.fullMode` is default-on with AGP 8; verify proguard
  rules don't keep the stripped screens' classes (delete the keeps rather than
  relying on shrink).

---

## 4. Expected outcome

| Metric | Full (today) | Lite (target) | How |
| --- | --- | --- | --- |
| APK size | 4.59 MB | **~2.5–3.0 MB** | fonts + aliases + deps stripped |
| Cold start | splash ≥0.9 s + alias resolve + update poll | **first frame instantly** | no splash (NONE), 1 alias, no network |
| RAM steady-state | thumbnails, analyzer, media grids | **lower by tens of MB on media folders** | no bitmaps, no grid state |
| CPU | springs/fades every transition, retriever decodes | **zero animation frames** | MotionStyle.NONE + no decode work |
| Battery | update poll + heavy I/O features | **no background network, less I/O** | checker stripped, analyzer gone |

## 5. Sequencing & effort

1. **PR-1 (mechanical, ~half a day):** flavor scaffolding + fonts to `src/full` +
   lite manifest + CI builds/attaches both APKs. No behavior change for `full`.
2. **PR-2 (~1 day):** nav/Settings gating for Categories, Analyzer, 7z, update
   checker; hide Motion card; wire the existing `LocalPerformanceMode` checks.
3. **PR-3 (optional polish):** versionName/format tooling, Lite screenshot in docs.

Every PR keeps `full`'s unit suite green (the tests are the flavor-blind contract)
and adds one `SettingsRepositoryTest`-style assertion per stripped gate.

## 6. Explicit non-goals

- No new features in Lite. It is a subset, so its bug surface can only shrink.
- No shared-preferences migration: Lite is a different `applicationId` with its own
  DataStore — a user switching editions re-chooses settings (favorites export via
  Properties is manual and acceptable for v1).
- No F-Droid/Play flavor matrix. Two editions, one dimension, done.

---

## 7. Implementation status (post-v2.3.0)

**Landed (the plan's PR-1 + PR-2 in one change):**

- §2 flavor architecture: `full`/`lite` product flavors, `BuildConfig.EDITION` gate
  (`domain/Edition.kt`), `.lite` suffix, label via `src/lite/res/values/strings.xml`
  (a flavor res override — a `resValue` would have collided with main's
  `app_name` string and failed the lite resource merge).
- §3 phase 1 (resource diet): fonts moved to `src/full/res/font` +
  `BundledFonts.kt` flavor seam (`bundledFontFamilyOrNull`), Appearance lists
  SYSTEM + TOAD in lite.
- §3 phase 2 (runtime diet): Categories / Storage / Analyzer / What's New routes
  gated in `ToadNavHost`; update checker + banner + About Updates/What's-new cards
  gated in `MainActivity`/`AboutScreen`; Home hero storage card gated; Appearance
  hides Motion, App icon and accent, themes collapse to CLASSIC; 7z via the
  `SevenZipImpl` flavor seam (lite answers "not included"); `ArchiveOps` ZIP-only;
  INTERNET permission + 42 aliases stripped at manifest merge, one `LauncherLite`.
- §4 motion: `MotionStyle.NONE` pinned + `LocalPerformanceMode` forced ON in lite
  at the `ToadTheme` call site (NOT inside `ToadTheme` itself — component tests
  drive the theme directly with their own motion params).
- CI (android.yml, release.yml): both editions linted/assembled/signed; the release
  uploads `toadfiles-<tag>.apk` + `toadfiles-lite-<tag>.apk`.

**Deliberately deferred (PR-3 / phase 3 leftovers):**

- `testLiteDebugUnitTest` in the gate: the shared Robolectric suite is NOT actually
  flavor-blind — `AppearanceScreenTest` (font rows), `AboutScreenTest` (Updates /
  What's-new cards), `ToadNavHostE2ETest` (Categories tab, storage card),
  `TabScreenshotsTest` and the 7z `ArchiveOps` dispatch tests assert full-edition
  contracts and would go red under the lite flavor without edition-aware surgery.
  CI runs the full suite (`testFullDebugUnitTest`, including `src/testFull`) and
  builds + lints both flavors instead. Revisit only if lite gains behavior of its
  own (a bug surface the full suite can't catch).
- Lite-specific screenshots in `docs/screenshots/`.
- `versionName` tooling beyond the `-lite` suffix.

**One convention fix during landing:** flavor-only unit tests live in
`src/testFull/java` (AGP's flavor test source set — task `testFullDebugUnitTest`),
NOT `src/full/test/java`, which AGP silently never compiles or runs. The 7z
fixtures (`SevenZipSupportTest`, `ArchiveOpsVolumeTest`) were relocated
accordingly.
