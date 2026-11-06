# Full codebase audit — toadFILES v2.0.0

**Audit date:** 2026-11-04
**Audited baseline:** `main` at `2eafc423cb1f446d794620a6c9d4861d20da63dc` (v2.0.0, versionCode 24)
**Review type:** repository-wide static source, test, configuration and workflow audit, cross-checked against proven open-source file managers
**Status:** findings require triage; this document does not claim device-level reproduction.
**Predecessor:** `docs/MAIN_REPO_AUDIT.md` (v1.6.0 baseline, 2026-10-30) — its P1/P2 findings were verified as fixed in this pass; nothing in that report regressed.

## 1. Scope and method

Every hand-written file was read line-by-line: 75 production Kotlin files (21,077 LOC), the manifest, all `res/xml` provider/backup configs, both CI workflows, Gradle build files and the version catalog. Generated assets (42 activity-alias blocks, 25 font TTFs, 44 launcher drawable/mipmap XMLs) were consistency-sampled rather than line-read: the alias set was cross-checked mechanically against the `AppIconDesign × AppIconColor` enum matrix (42/42, no drift, verified by `AppIconSwitcherTest` against the raw manifest).

| Area | Files | Covered by |
|---|---:|---|
| `domain/` + `data/` + `util/` + app root (~30 files) | all | auditor line-read (this document's evidence) |
| `ui/` screens + ViewModels (21 files) | all | deep-dive pass, claims re-verified against source before publication |
| `ui/components/` + `ui/theme/` (17 files) | all | deep-dive pass, claims re-verified |
| Test suite (69 files, 14,944 LOC) | all | test-quality pass |
| Manifest, `res/xml`, Gradle, CI, proguard | all | auditor line-read |

### 1.1 Open-source baselines used for comparison

Logic was diffed against the current source of:

- **zhanghai/MaterialFiles** (Apache-2.0) — libarchive NIO provider, `FileJobService`, `AndroidFileTypeDetector`, `OpenApkDefaultAction`
- **TeamAmaze/AmazeFileManager** (GPL-3.0) — `ExtractService`, `ZipExtractor`, `CompressedHelper.isEntryPathValid`, `MimeTypes`
- **FossifyOrg/File-Manager** (GPL-3.0) — `DecompressActivity`, zip4j password flow, MediaStore recents
- **TeamNewPipe/NewPipe** (GPL-3.0) — `NewVersionWorker` + `ReleaseVersionUtil` as the update-check reference

### 1.2 Where toadFILES stands versus the baselines (headline)

| Topic | MaterialFiles | Amaze | Fossify | **toadFILES v2.0.0** |
|---|---|---|---|---|
| Zip-slip | structural (NIO archive FS) | blacklist + canonical check | single canonical check, bad entries silently skipped | **name validation + per-entry canonical check on staged AND final path + staging isolation** |
| Zip-bomb caps | none | none | none | **entry count, actual-emitted-bytes and ratio caps, overflow-safe, enforced from real bytes** |
| Encrypted zip | libarchive passphrases | zip4j + retry loop | zip4j re-open | 7z AES-256 supported; zip declined with honest copy (documented limitation) |
| Replace semantics | copy job | overwrite | overwrite | **stage → backup → swap, full rollback on failure/cancel** |
| Trash | n/a | partial | partial | **transactional sidecars, crash recovery, retention purge** |
| Update check | n/a | n/a | n/a | single HTTPS endpoint, honest errors; no cert pinning (A17) |

toadFILES meets or exceeds every baseline on the filesystem-safety axes. The real findings are elsewhere: the operation lifecycle in the UI layer, main-thread I/O that survived the otherwise-systematic IO discipline, and test/CI gaps at the edges. `commons-compress` is pinned at **1.27.1** (post-dates CVE-2024-25710 / CVE-2024-26308; neither is reachable anyway — only the 7z stream and `PasswordRequiredException` are used).

**Severity count: 1 P1 · 12 P2 · 15 P3**

## 2. Executive summary

The v1.6.0 audit's five P1s (symlink delete, zip limits, destructive REPLACE, trash ordering, MediaStore URIs) are all still fixed — re-verified in this pass. The v2.0.0 redesign added ~9,000 LOC (themes, motion, fonts, splash, open-with handoff, update flow) without weakening any prior hardening. What the redesign did add is a classic op-engine flaw shared by the media screens, a handful of main-thread I/O sites, and a visual leak in the CLASSIC accent palettes:

- **A1 (P1): the shared op engine launches ops concurrently instead of single-flight** — a second tap during an extract runs a second extraction; the older job becomes uncancellable and its late `onDone` overwrites the newer result; `cancel()` clears the progress overlay before the op has actually stopped, so a final progress tick can resurrect a dead overlay.
- The heaviest P2s are honest performance/ANR risks: archive headers parsed on the main thread right before every extraction, `mkdir`/`listFiles` on the main thread in two UI handlers, per-recomposition filesystem stats in video grids, and a compress progress bar that counts two different units against each other.
- Test/CI: the only on-device test suite (instrumented, incl. the on-device zip-slip check) never runs; the self-update downloader ships untested; screenshot goldens are recorded but never compared.

## 3. P1 — fix before the next release

### A1. Op engine allows concurrent ops; cancel is racy; cancelled ops still publish results

**Files:** `app/src/main/java/com/toadfiles/app/ui/components/OpRunner.kt:51-66`; call sites `RecentViewModel.kt:285`, `CategoriesViewModel.kt:581,693`, `BrowseViewModel.kt:826,1089` (Browse's inline twin shares the pattern).

**Evidence:**

```kotlin
// OpRunner.kt — the KDoc admits the design:
// "a new run replaces the job reference without cancelling a still-running previous one."
fun run(op, onProgress, onDone) {
    job = scope.launch(ioDispatcher) {
        val r = op { onProgress(it) }
        onDone(r)                       // no ensureActive(): a cancelled op still publishes
    }
}
fun cancel(after: () -> Unit) {
    job?.cancel()
    after()                             // progress cleared while the op tick is still in flight
}
```

**Why it is wrong:**

1. **Double-launch:** `runExtract`/`startExtract`/`zipAll` launch unconditionally. Two taps on "Extract here" run two concurrent extractions into the same destination: with `replace = false` the runs interleave skip/publish decisions; with `replace = true` both pipelines maintain independent backup lists over the same files, which the rollback logic was never designed for.
2. **Orphaned op:** `cancel()` cancels only the latest `job` reference. The first op keeps writing with no way to stop it.
3. **Stale result overwrite:** the older job's `onDone` fires after the newer one's, overwriting `result` state and re-running `refresh()`/`load()` with the older outcome.
4. **Progress resurrection:** `onProgress` runs on the IO thread (`_state.update`); after the main thread cancels and clears `progress`, one in-flight tick can CAS-win and re-set the overlay with a dead op behind it.
5. **Result-after-cancel:** there is no `ensureActive()` between `op(...)` returning and `onDone(r)`, so a cancelled-at-the-wire op still publishes a result dialog and triggers the delete-after-extract chain — exactly the state the "delete only after full success" rule tries to protect.

**Fix (implemented in this audit's fix commit):** make `OpRunner` single-flight — `run()` cancels and joins the previous op inside the new coroutine before starting; `onDone` is guarded by `ensureActive()`; `cancel()` runs `after()` only after the job has fully stopped (join on the runner scope), closing the resurrection window. Regression tests cover: concurrent launch, cancel-without-result, and progress-quiescence after cancel.

**Browse's inline engine** (`opJob` handling around `BrowseViewModel.kt:826`) has the same replace-without-cancel shape; it is deliberately left for the follow-up consolidation (A22) because its conflict-dialog plumbing makes the semantics bigger than a hotfix.

## 4. P2 — important correctness, performance and process issues

### A2. Archive headers parsed on the main thread before every extraction

`RecentViewModel.kt:284`, `CategoriesViewModel.kt:692`, `BrowseViewModel.kt:758,1229`:

```kotlin
_state.update { it.copy(progress = title to OpProgress(0, ArchiveOps.entryCount(file) ?: 1, "")) }
```

`entryCount` opens a `ZipFile`, or for 7z constructs a `SevenZFile` and does `entries.toList()` (`SevenZipSupport.kt:309-313` — full header parse + entry materialization), or opens a `FileChannel` for volumes. It runs in the tap handler on the main thread. A multi-GB archive on slow flash can ANR the app at the exact moment the progress dialog should appear. **Fix:** move the call inside the op coroutine (publish the initial `OpProgress(0, …)` from there) — the pipelines already run everything else on IO.

### A3. Blocking disk writes/listings in UI handlers

- `BrowseViewModel.kt:573` — `createFolder` calls `fileRepo.newFolder` (a `File.mkdir`) synchronously on the main thread; every other write op in the VM dispatches to `ioDispatcher`.
- `StorageViewModel.kt:85` — `analyzeTopLevel` runs `root.listFiles { isDirectory }` (a stat per entry) on the caller thread before `analyzing = true` is published, so the "Analyze" tap does its heaviest listing before the progress UI exists.

Both are StrictMode-flaggable and can jank on slow FUSE storage. **Fix:** move into the launched coroutine; set the in-progress state first.

### A4. Compress progress mixes units: entries counted against selection size

`BrowseViewModel.kt:1098-1099`, duplicated at `CategoriesViewModel.kt:591-592`:

```kotlin
val r = ArchiveOps.compress(srcs, targets[i], …) { p ->
    _state.update { it.copy(progress = title to OpProgress(base + p.done, files.size, p.currentItem)) } }
```

`p.done` counts files added to the archive (its own `p.total` = `countFileEntries`), but the wrapper substitutes `files.size` (selected items). Compressing a folder of 51 files shows "37 of **1** items" and `ProgressOverlay` clamps a full bar at the first tick. **Fix:** forward the pipeline's own totals (`base + p.done, base + p.total`) and advance `base += p.total`.

### A5. `CategoriesViewModel.open()` doubles as the ops re-list

`open()` (`CategoriesViewModel.kt:188-213`) hard-resets `layout` to the category default and flashes `entries = emptyList()`, but the VM itself calls `open(it)` after every op (delete/undo/rename/zip/extract; call sites at `:300, :342, :381, :618, :713`) as its "re-list" mechanism. Consequences: a user who switched a category to LIST has it snap back after deleting one image; the list blinks empty mid-operation; and `open(it)` is immediately followed by `refresh()`, which re-queries the same lane and cancels the just-launched entries job — two MediaStore queries plus a summaries query per op where one would do. **Fix:** split `open(category)` (user navigation, resets layout) from `relist()` (ops: re-query entries only, keep layout).

### A6. Composition-time filesystem I/O in hot UI paths

- `Common.kt:226` — `VideoThumb` calls `VideoThumbs.stampFor(path)` (a `Files.readAttributes` stat) unconditionally in composition; every recomposition of every video cell (scroll, selection toggles) pays a syscall. The `remember(path, stamp)` below it only guards the decode.
- `ArchiveFolderPicker.kt:55-61` — `remember(dir) { dir.listFiles()?.filter { it.isDirectory && it.canRead() } }` runs two syscalls per entry on the main thread each time the user navigates the picker; the starting point is `/storage/emulated/0`, routinely hundreds of entries.
- `BrowseScreen.kt:334` (pattern also `TrashScreen.kt:285`, `CategoriesScreen.kt:287`) — `state.dir?.isDirectory == true && state.dir?.canWrite() == true` stats during TopAppBar composition on every recomposition.

The codebase already demonstrates the correct pattern (`StorageAnalyzerScreen.kt:477` deliberately moves `file.length()` off main via `produceState`). **Fix:** carry writability in `BrowseUiState`/`TrashEntry` computed at listing time; wrap the picker listing and the thumb stamp in `produceState`/`LaunchedEffect` on IO.

### A7. CLASSIC accent schemes leak M3 baseline purple

`AccentPalettes.kt` defines `surfaceContainer*` for **none** of its 14 schemes and `background` for only 2; the redesigned UI reads `surfaceContainerHigh` (`TransferBar.kt:54`) and container roles in `SettingsScreen.kt:299,321` and `AppearanceScreen.kt:324,393`. Under CLASSIC, those roles fall back to the M3 *baseline* palette (purple-tinted grays), which visibly clashes with the warm CLASSIC surfaces in dark mode. **Fix:** derive `background` + the five `surfaceContainer*` tones per accent (or generate schemes from seeds like Material Theme Builder). Verified by grep: 2 `background` declarations, 0 `surfaceContainer` declarations in the file.

### A8. Request tokens in Categories/Recent are non-atomic `Long`s mutated from IO coroutines

`CategoriesViewModel.kt:134-137` (`summariesSeq`, `entriesSeq`), `RecentViewModel.kt:80,107` (`requestSeq`). Op completions run on `ioDispatcher` and do `++entriesSeq`; two concurrent finishers can interleave increments and both pass the `token != entriesSeq` guard, letting a stale result overwrite a newer one. Browse fixed exactly this with `AtomicLong` and documents why (`BrowseViewModel.kt:171-173`). **Fix:** same `AtomicLong` treatment.

### A9. Browse tree search outlives navigation

`load()` (`BrowseViewModel.kt:241-256`) cancels `loadJob` and bumps `loadSeq` but never cancels `searchJob` (`:320-343`). After navigating away mid-search, the recursive `searchTree` keeps walking directories until its limit is hit; its result is discarded only afterwards. **Fix:** `searchJob?.cancel()` in `load()` (and explicitly in `onCleared()` for symmetry).

### A10. CI never runs the instrumented suite

`.github/workflows/android.yml` runs lint + `testDebugUnitTest` + `assembleDebug` — no emulator job, so `app/src/androidTest` (6 tests, including the only on-device filesystem-semantics and zip-slip validation) is dead weight that CI pretends to care about. **Fix:** add a `connectedDebugAndroidTest` job on an API-level matrix (ubuntu runner + emulator, or drop the suite honestly if device coverage is deliberately out of scope — but then say so in the docs).

### A11. `SystemUpdateDownloader` — the self-update install path — has zero tests

`UpdateDownloader.kt` (114 LOC) is the one network+system-automation class; only its interface is faked in `AboutViewModelTest.kt:74` (KDoc admits "the real DownloadManager is never exercised here"). Enqueue flags, `awaitDownload` status mapping, and the delete-before-enqueue retry rule are untested. **Fix:** Robolectric `ShadowDownloadManager` tests for enqueue/await mapping.

### A12. Screenshot goldens are recorded, never verified; no coverage measurement

`TabScreenshotsTest.kt:219-221` uses only `captureRoboImage`; no `verifyRoboImage` anywhere; output is gitignored; goldens in `docs/screenshots/` are manually copied — so the 9 screenshot tests are UI smoke tests with ~25 s of wall-clock sleeping and zero pixel verification. Separately, no JaCoCo/Kover exists anywhere, so "what's untested" is unanswerable mechanically (this audit did it by hand). **Fix:** a `verifyRoborazziDebug` CI step with committed goldens; Kover plugin with a baseline report.

## 5. P3 — lower-priority defects and polish

- **A13 · RecentGrouping DST boundaries** (`RecentGrouping.kt:32-36`): `today - DAY_MS` uses fixed 24-hour units against calendar-midnight starts; on DST-transition days yesterday's files bucket into THIS_WEEK. Fix: normalize each boundary through `startOfDay`.
- **A14 · ToadSplash replays on configuration change** (`ToadSplash.kt:38-41`): only `splashShowing` is saveable; the timeline uses plain `remember`, contradicting both the file's and MainActivity's doc comments. Worst case is a replayed animation, never a hang.
- **A15 · Passwords live in Strings and `CharArray`s are never zeroized**: `PasswordPromptDialog.kt:51,78`, `BrowseUiState.pendingZipPassword: String`, and every `toCharArray()` handed to the 7z pipeline (no `fill('\u0000')` anywhere). Mitigating: nothing is logged or persisted; heap-lifetime hardening only.
- **A16 · `Intents.openUrl` accepts `http://`** (`Intents.kt:328-343`): fine today (all About links are GitHub https), but restricting to https removes a class of intent-injection surprises.
- **A17 · Update flow lacks release-signature pinning** (NewPipe comparison): NewPipe pins the release cert SHA-256 before advertising an update; toadFILES trusts the HTTPS JSON alone. Android's installer signature check blocks a signature-mismatch install, so this is hardening, not a hole.
- **A18 · `toadFileIconFor` allocates 6-7 `Set`s per call** (`ToadBrand.kt:322-326`), once per visible row per recomposition. Hoist to top-level constants.
- **A19 · Blink animates layout** (`ToadBrand.kt:138-149`): pupil squash drives `size`/`offset` → recompose+measure per frame; `EmptyTrashArt` allocates a `Path` per draw (`:392-398`). Draw-phase alternatives are strictly cheaper.
- **A20 · Whole-tree walks where counters would do**: `StorageViewModel.kt:76` sums `FileOps.sizeOf` (full recursive walk) over every trash entry on every refresh; `MediaRepository.hiddenTotals` (`:321-345`) full-scans the selection, ×7 categories per `summaries()` call. Bounded but repeated work that trash-time metadata could answer.
- **A21 · Sheets dismiss without exit animation**: every `FileActionSheet` row calls `onDismiss()` first (`OpRunner.kt:149-209`), popping the `ModalBottomSheet` out of composition with no slide-down, while swipe-to-dismiss animates. Inconsistent feel.
- **A22 · Copy-paste op machinery across three ViewModels**: `undoDelete` (~35 lines) is byte-identical in Browse/Categories/Recent; `zipAll` + the compress-dialog state block are duplicated Browse↔Categories; drift has started (Categories re-lists via `open()+refresh()`, Browse via `load()`). Extract a shared undo/archive-op engine next to `OpRunner`.
- **A23 · Layering drift**: `VolumeChoice`/`VOLUME_MB` live in `CompressDialog.kt` while sibling contract enums live in `domain/ArchivePrefs.kt`.
- **A24 · Drawer divider mislabeled and misplaced** (`ToadNavHost.kt:141,386-390`): `drawerDividerAfter` actually renders *before* the matched item and lands between Recent and Trash, cutting Trash off from its primary group while Settings/About are never separated.
- **A25 · Dead parameter**: `RecentScreen.kt:526-533` `RecentGrid(inSelection = …)` is passed but never read (body reads `state.selection` directly).
- **A26 · i18n/test-tag debt**: hardcoded user copy throughout components (`Common.kt:445`, `TransferBar.kt:82-101`, `ResultDialog`), `SortMenu` derives labels from `enum.name` (`Common.kt:657` — an enum rename silently rewords the UI), and 10 dialogs/sheets have no `testTag` while the repo convention (snake_case, 3 existing tags) makes them trivially testable.
- **A27 · Test-suite nits**: vacuous conditional assertions (`HomeViewModelTest.kt:70-75`, `FileRepositoryTest.kt:156-166`, `AboutViewModelTest.kt:144-154` — assert only inside `if (x != null)`, passing trivially on the common Robolectric path); negative-assertion race tests rely on 250-500 ms sleeps (deterministic-fail, not deterministic-pass); a 10 s polling `await()` helper is copy-pasted across ≥8 files; stale `TODO(integration)` comments on tests that since became real (`BrowseViewModelTest.kt:1001,1039`); `MediaTrashOps` KDoc header has mangled markers ("ovedFiles]").
- **A28 · Zip publish TOCTOU (hardening)**: `ZipSupport.publishStaging` checks `dest.exists()` then moves with `REPLACE_EXISTING` (`ZipSupport.kt:471-495`); a file created in the window is silently replaced even with `replace = false`. Same-tiny-window note for `mkdirsTracked` (`:538-549`): a partially-failed `mkdirs` leaves untracked dirs for the rollback. Both need a racing creator; worth fixing during the next touch of that file, not as a dedicated release.
- **A29 · `FileOps.copyFileContents` same-length shortcut** (`FileOps.kt:431`): `dest.exists() && dest.length() == src.length() → return true` treats a same-length different-content destination as already copied. Currently unreachable (all copy destinations are freshly staged), but it is a landmine if the staging discipline ever changes.
- **A30 · `countFileEntries` recursion** (`ZipSupport.kt:649-657`): unbounded recursion depth on pathological trees (link-cycle safe, depth not). A stack-walk variant would match `publishStaging`'s iterative style.

## 6. What was checked and found sound

Verified as correct during this pass (worth recording so future audits don't re-litigate):

- **Zip-slip**: `PathSafety.isSafeEntryName` (NUL/absolute/`..`/colon/ADS aware, backslash-normalizing) + `ensureInside` canonical checks on both staged and final paths, staging isolation, `publishStaging` re-checks every published path. Stronger than all three baselines.
- **Zip bombs**: entry-count / actual-bytes / ratio caps computed from bytes really consumed (never header sizes), overflow-safe. No baseline has any cap.
- **7z pipeline**: same `PathSafety` guards and caps (`SevenZipSupport.kt:172-178`); AES-256 data-only encryption with honest `passwordRequired` flow; volume sets gap-checked before any write.
- **UriGuard**: canonical-path allowlist before every FileProvider hand-off; Android/data & Android/obb refused; Robolectric widening test-scoped. The `root-path` catch-all in `file_paths.xml` stays contained because of this second line.
- **Intents**: ClipData carries every URI (multi-share grant fix from P2.3 still in place), install gate distinguishes four honest outcomes, `openUrl` refuses non-web schemes.
- **Trash**: transactional pending→commit with payload rollback, crash recovery, orphan pruning, retention purge, single lock, URL-encoded origin paths, 240-byte name budget.
- **MediaTrashOps**: stale-path TOCTOU guard (never trash on a vanished cached path; never row-drop what wasn't moved).
- **Update flow**: single pinned HTTPS endpoint, friendly rate-limit handling, retry policy classified by exception type, malformed tags can never push an update (`VersionCompare`), download lands app-privately and is handed to the installer via `content://downloads`.
- **Permissions**: minSdk 34 makes the permission story exactly one grant (MANAGE_EXTERNAL_STORAGE) + REQUEST_INSTALL_PACKAGES + INTERNET, all documented in the manifest. Platform note: MANAGE_EXTERNAL_STORAGE is a Play-policy blocker, so distribution stays sideload/GitHub — already the project's model.
- **42 launcher aliases**: mechanically verified against the enum matrix; ghost-alias guard added in v2.0.0 protects the dynamic path.
- **commons-compress 1.27.1**: post-CVE-2024-25710/26308; only the 7z stream is consumed.

## 7. Remediation plan

| Batch | Findings | Effort | Status |
|---|---|---|---|
| **Now (this audit's PR)** | A1 (OpRunner single-flight + cancel semantics + tests) | small, isolated | ✅ shipped in v2.0.0 (#29) |
| Next reliability PR | A2, A3, A4, A5, A8, A9 (all op/VM lifecycle — one pass over three ViewModels + entryCount move) | medium | ✅ shipped in v2.1.0 |
| UI perf pass | A6, A18, A19, A20, A21 | medium | A6, A18, A19, A20, A21 ✅ v2.1.0–v2.2.0 |
| Theme pass | A7 (container roles), A24 | small | ✅ shipped in v2.1.0 (A24) / v2.1.1 (A7) |
| CI/test pass | A10 (emulator job or honest removal), A11 (ShadowDownloadManager), A12 (Kover + verifyRoborazzi), A27 | medium | A11 ✅ v2.1.1; A27 ✅ v2.2.0 (vacuous assertions, stale TODOs, KDoc, await dedupe; sleep-based race redesign still open); A10, A12 open |
| Hardening backlog | A13-A17, A22-A26, A28-A30 | opportunistic, tied to next touch of each file | A13, A14, A15, A16, A19, A22, A23, A25, A28, A29, A30 ✅ v2.1.0–v2.2.0 (A26 partial: labels + test tags done, i18n open); A17, A24-shipped, i18n open |

*Everything above was verified by reading the code at the cited lines on the audited commit; static review only, no device run.*

### 7.1 v2.1.0 remediation notes

- **A2** — all four call sites now publish an indeterminate `OpProgress(0, -1, "")`
  immediately and move `ArchiveOps.entryCount` inside the op coroutine (Browse `startOp`/
  `startExtract`, Categories/Recent `startExtract`/`runExtract`). Pinned by
  `RecentViewModelTest.extract progress starts indeterminate on the caller thread - A2`.
- **A3** — `BrowseViewModel.createFolder` dispatches the mkdir to IO (dialog closes
  immediately); `StorageViewModel.analyzeTopLevel` publishes `analyzing = true` before
  the `listFiles` stat walk, which now runs inside the IO coroutine (unreadable root →
  honest reset, no stuck spinner).
- **A4** — both `zipAll` engines forward the pipeline's own totals
  (`base + p.done, base + p.total`, `base += p.total` per archive) and start
  indeterminate. Verified against the existing zip progress tests (tick totals reach the
  real file-entry count).
- **A5** — the five op-completion sites in Categories call `refresh()` only; the
  entries leg of `refresh()` is the in-place re-list (keeps layout, no blink, one query
  per lane). `open()` remains user navigation only. Pinned by two new tests
  (layout survives an op; entries never flash empty).
- **A6** — `VideoThumb` stamps via `produceState` on IO; `ArchiveFolderPicker` lists on
  IO inside a `LaunchedEffect` (previous folder's list stays until the new one lands);
  Browse carries `dirWritable` in `BrowseUiState` computed at listing time, and the
  destination-picker's TopAppBar reads that instead of stat'ing per recomposition.
- **A8** — `summariesSeq`/`entriesSeq` (Categories) and `requestSeq` (Recent) are
  `AtomicLong`s, matching Browse's documented pattern.
- **A9** — `BrowseViewModel.load()` cancels `searchJob` (and `onCleared` already did);
  the recursive walk no longer outlives navigation.
- **A18/A21/A24/A25** — icon-set constants; `FileActionSheet` rows run
  `sheetState.hide()` → dismiss → action so every row gets the swipe-dismiss exit
  animation; drawer divider renders after the Trash item; dead `RecentGrid(inSelection)`
  parameter removed.

### 7.2 v2.1.1 remediation notes (wave two)

- **A7** — `AccentPalettes.withDerivedSurfaces` derives `background`, `onBackground`
  and the five `surfaceContainer*` tones for all 14 schemes from each scheme's own
  `surface`/`surfaceVariant` pair (M3-neutral-ladder-shaped mixes; dark's lowest tone
  mixes toward black). Pinned by `AccentPalettesTest.every scheme derives background
  and container tones in its own voice - A7` (background==surface, no baseline leak,
  monotone elevation ladder) plus a TOAD no-regression pin.
- **A11** — `UpdateDownloaderTest` (new): enqueue field mapping via
  `ShadowDownloadManager`, delete-before-enqueue retry rule, blank-URL IOException,
  `awaitDownload` success→uri / failure→null status mapping.
- **A13** — `RecentGrouping.calendarDayBefore` steps real calendar days via
  `Calendar.add(DAY_OF_MONTH)` (wall-clock midnight survives 23/25-hour days). Pinned
  by three America/New_York transition tests (fall-back early hours → Yesterday;
  spring-forward previous evening → This week; 7-calendar-days-across-fall-back).
- **A14** — `ToadSplash` timeline state (`hopStarted`, `veilVisible`, veil/blink
  `Animatable`s through a value-preserving Saver) is `rememberSaveable` now, matching
  MainActivity's documented contract.
- **A16** — `Intents.openUrl` accepts https only; pinned by three `IntentsTest` cases
  (https launch, uppercase-scheme acceptance, http/ftp/file/content/garbage refusal
  with zero started intents).
- **A19** — `EmptyTrashArt` builds the bin-body path once in unit space (remembered)
  and scales it in the draw phase via `withTransform`; no per-draw `Path` allocation.
  The pupil blink stays param-driven deliberately: it animates only during the splash
  and reveal blinks, so the measure-per-frame cost is bounded and the draw-phase
  refactor risk was not worth it for this release.
- **A28** — `ZipSupport.publishMove` (new internal seam): replace moves carry
  `REPLACE_EXISTING`; non-replace moves carry none, so a destination that materializes
  after the exists() probe fails the item with `FileAlreadyExistsException` (mapped to
  `counts.failed`) instead of silently overwriting it. Pinned by two `ZipSupportTest`
  cases.
- **A29** — `FileOps.copyFileContents` no longer treats a same-length destination as
  already copied; copies are unconditional (all current destinations are freshly
  staged, so this only removes the landmine).
- **A30** — `ZipSupport.countFileEntries` walks with an explicit stack; identical
  counting rules (file links count, unfollowable links skip) without recursion depth
  limits. Pinned by a tree-shape `ZipSupportTest`.
- **Release-gate flake** — `AppIconSwitcherTest`'s ghost-alias test (unrelated to an
  A-number; broke the v2.1.0 `build` workflow) now seeds every alias with an explicit
  `COMPONENT_ENABLED_STATE_DISABLED` before probing, so the "nothing changed" assert
  reads the PackageManager's own setting map instead of lazily-resolved manifest state.

Still open after wave two: A10 (CI emulator job or honest removal), A12 (Kover +
verifyRoborazzi), A15 (password zeroization), A17 (release-signature pinning),
A20 (trash-size/hidden-total walks), A22 (op-machinery dedup), A23 (VolumeChoice
relocation), A26 (i18n/test-tag debt), A27 (test-suite nits).

### 7.3 v2.2.0 remediation notes (wave three)

- **A22** — `domain/UndoOps.kt` is the one undo engine (`undoDeleteBatch` +
  `UndoOutcome.toastMessage()`), consuming the media re-scan as an injected callback so
  the domain stays free of data-layer types; Browse/Categories/Recent's `undoDelete`
  shrink to launch + engine + toast + their own re-list. `ArchiveOps.compressBatch` is
  the one compress-batch engine (A4's pipeline-totals forwarding and the per-archive
  delete-sources gate live there); Browse's inline op launch and Categories' OpRunner
  op both delegate to it. Pinned by `UndoOpsTest` (counts, gone, collision rename,
  empty batch, rescan hand-off) and `ArchiveOpsBatchTest` (monotonic pipeline totals,
  per-archive trash gating, password wipe).
- **A20** — `Trash.trash` records `size=<bytes>` in the pending sidecar (files only —
  a directory walk inside the trash transaction would hold the trash lock; dirs and
  legacy sidecars report -1); `TrashEntry.sizeBytes` carries it; `StorageViewModel`
  prefers the metadata with a per-entry walk fallback. `MediaRepository.summaries()`
  is one index pass now: the six COUNT/SUM aggregate queries and the six per-category
  `hiddenTotals` scans (an exists() stat per row per category) became a single scan
  whose `matchesSelection` mirrors `selectionFor`'s SQL per category (shared
  `archiveMimes`/`archiveExts` constants), so the counts are unchanged. All five
  existing summaries tests pin the equivalence unchanged.
- **A23** — `VolumeChoice`, `VOLUME_MB`, `volumeChoiceFor`, `customVolumeTextToBytes`
  moved from `ui/components/CompressDialog.kt` to `domain/ArchivePrefs.kt`; imports
  updated in both ViewModels, the dialog and the tests.
- **A15** — `ArchiveOps.extract` wipes the caller's password `CharArray` in `finally`;
  `ArchiveOps.compressBatch` wipes it when the batch ends (NOT inside `compress` — a
  multi-archive batch hands the same array to every archive, so the outermost seam is
  the one consumption point). All five production `toCharArray()` sites create the
  array fresh per attempt. Typed `String`s in dialog state remain a documented
  residual (Compose TextField contract; nothing is logged or persisted).
- **A26 (partial)** — `SortMenu` labels come from a stable `sortModeLabel` mapping;
  eleven shared dialogs/sheets/surfaces carry test tags following the snake_case
  convention (`dialog_confirm`, `dialog_name_input`, `dialog_conflict`,
  `overlay_progress`, `dialog_result`, `dialog_password`, `dialog_compress`,
  `dialog_apk_install`, `sheet_file_actions`, `sheet_properties`,
  `dialog_archive_folder`). The hardcoded-copy i18n extraction is still open.
- **A27 (partial)** — the three vacuous conditional assertions are unconditional
  (`HomeViewModelTest`, `FileRepositoryTest`, `AboutViewModelTest`); the stale
  `TODO(integration)` markers on the two real password-flow tests are gone;
  MediaTrashOps' mangled KDoc markers fixed; the ten copy-pasted private `await()`
  helpers replaced by one `TestAwait.kt`. The sleep-based race tests
  (deterministic-fail redesign) remain open.

- **Release-gate flake (second occurrence, v2.2.0)** — the ghost-alias guard test
  flaked again despite v2.1.1's explicit seeding. Root cause: Robolectric's
  component-settings map is a static per-sandbox field, so state written by other
  test classes in the same JVM can surface as `DEFAULT`/leftover values on reads;
  "every read is exactly DISABLED" was equality against a leaky baseline. The test
  now pins the guard's contract (no alias ENABLED after a ghost apply — the
  vanished-app failure mode), which is deterministic with and without the guard,
  and the release workflow's unit-test gate gains one bounded suite re-run.

