# Changelog

All notable changes to toadFILES are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are tagged `vX.Y.Z` and produce a signed release APK via CI.

## [Unreleased]

The first cut of **toadFILES Lite** (docs/LITE_EDITION_PLAN.md): a second APK built
from the same repo via Gradle product flavors.

### Added
- **`lite` product flavor (PR-1 + PR-2 of the plan)**: `applicationIdSuffix .lite`
  installs side-by-side with the full app; `versionNameSuffix -lite`; the app label
  reads "toadFILES Lite" via a flavor res override. Full is today's app unchanged.
  The build gate is `BuildConfig.EDITION` (a compile-time constant per flavor), so
  every `IS_LITE` branch constant-folds and R8 strips the dead side in release
  builds — Lite's dex genuinely loses the stripped features instead of hiding them.
- **Lite resource diet**: the 24 bundled Google-Fonts files (1.9 MB — 41% of the
  APK) move to `src/full/res/font`; lite ships the Toad font and the system
  typeface only, and the Appearance tab lists those two. The 7z pipeline
  (commons-compress + xz, ~1 MB of dex) and coil-video are `fullImplementation`
  deps — off the lite classpath entirely.
- **Lite runtime diet**: Categories and Storage/Analyzer screens, the What's New
  route, the update checker (MainActivity never runs `StartupUpdateCheck`; the
  About screen hides the Updates and What's-new cards) and the app-icon switcher
  UI are all compile-time-gated off. The lite manifest strips the INTERNET
  permission at merge time — the app physically cannot phone home — removes the
  42 launcher aliases for one `LauncherLite` entry, and advertises ZIP-only
  VIEW filters. `ArchiveOps.canExtract` answers ZIP-only; a stray .7z reports an
  honest "7z support is not included in toadFILES Lite".
- **Lite motion**: `MotionStyle.NONE` pinned at the theme call site (the same
  early-return splash and snap specs as the full app's "None"), and
  `LocalPerformanceMode` reads as ON app-wide so video thumbnails resolve to the
  generic icon instead of spinning up MediaMetadataRetriever.
- **CI builds both editions**: Android CI lints and assembles `full` and `lite`
  debug APKs and uploads both; the Release workflow gates on the full unit suite
  (`testFullDebugUnitTest`), signs and attaches both APKs —
  `toadfiles-<tag>.apk` and `toadfiles-lite-<tag>.apk`. The full-flavor 7z
  fixtures now live in `src/testFull` (AGP's flavor test source set — the
  earlier `src/full/test` placement was silently never compiled or run).

### Fixed
- **Release gate (v2.3.0, run #33)**: the ghost-alias test failed the v2.3.0
  release gate twice in one job while Android CI passed the same suite on the
  same commit — Robolectric's component-settings state carries ENABLED leftovers
  written by other tests in the same JVM, which no per-test seeding can wall
  off. The test now asserts the before/after delta of `PackageManager` state
  across the ghost apply (a healthy guard writes nothing, so the snapshots must
  be identical; a broken guard ENABLES the target and shows up as a delta), and
  the sibling write test resets every alias it touched back to DEFAULT.

## [2.3.0] — 2026-11-06

The release-hardening wave: the "441 of 2 items" extraction bug dies at the root,
the ratio guard stops punishing honest archives, and the UI gains a zero-cost
motion setting plus a one-toggle Performance mode.

### Fixed
- **Ratio guard false-positives on ultra-compressible entries (caught by CI,
  runs #161/#163/#164)**: the central-directory rewrite judged the expansion
  ratio against the compressed size the central directory records with no
  slack — but deflate-9 of "toad " × 5000 produces csize = 61 for 25,000 bytes
  (410:1 from a 61-byte block), so the honest MAXIMUM-level round-trip test
  tripped "possible zip bomb" deterministically. The stream-based guard never
  fired there because it measured consumed bytes plus a 64-byte slack. The
  header-based guard now pads the recorded csize with a 1 KiB
  RATIO_OVERHEAD — deflate spends a few hundred bytes on Huffman/block headers
  before any payload, so the first KiB has no honest denominator — which the
  ZipSupportTest fixtures pin from both sides: the benign 410:1 round-trip
  passes with an 8.7× margin (threshold 217,000 ≥ 25,000) while the 200 KiB
  zero-run bomb at a 50× cap (csize ~245) and the descriptor/5 MB bombs still
  abort with nothing published. Verified 6/6 in the out-of-repo fixture
  battery plus the original 9-scenario loop battery.
- **ZIP extraction walks the central directory — nested archives can never explode
  ("441 of 2 items")**: extracting a zip that holds an APK + its .sha256 sidecar
  could produce 441 files instead of 2. The extractor streamed with ZipInputStream,
  which trusts LOCAL file headers; an outer zip written by a streamed tool can carry
  an entry whose local header lies about its sizes, and the stream then re-synced
  into the APK's OWN local file headers — publishing res/…, META-INF/… as if they
  were top-level entries ("441 of 2 items — res/drawable-hdpi-v4/…"). Extraction now
  iterates the central directory (java.util.zip.ZipFile) — the entries every honest
  unzip tool sees — and copies each one verbatim: a nested zip's structures are
  payload, and an extracted file is never re-opened or sniffed. The entry-count cap
  is enforced before anything is written, the CRC/size guard moved from closeEntry
  into the copy loop (ZipFile streams don't self-verify), and the progress overlay
  gains byte-level progress ("42.9 MB of 76.2 MB — …", "File 1 of 2") so archives of
  few huge files no longer jump the bar in two strides. Regression:
  ZipSupportNestedResyncTest hand-builds the hostile archive byte by byte.
- **Release gate — the ghost-alias test can no longer flake**: Robolectric's
  component-settings map is static per sandbox, so the ghost test's
  "every read is exactly DISABLED" assertion could read leftover state written by
  other test classes in the same JVM and fail CI-only, at a low rate (v2.1.0's
  gate and again v2.1.1's fix wasn't enough). The assertion now pins the guard's
  actual contract — no alias may end up ENABLED after a ghost apply — which is
  deterministic in both directions, and the release workflow's test gate gains a
  single bounded suite re-run as a second belt.

### Added
- **Motion "None" (Appearance tab)**: a fourth MotionStyle that turns every
  animation off — nav transitions become `EnterTransition.None`/`ExitTransition.None`
  (no transition nodes are created at all), every animated value settles in one frame
  via snap specs, and the launch splash is skipped entirely (`splashHoldMs` 0 + a
  zero-node early return). Zero animation work on the UI thread for low-end or
  battery-starved devices. The Appearance tab renders the enum automatically, so the
  card lists four choices now; the persisted enum-name round-trip already tolerated
  unknown values, so older installs upgrade cleanly.
- **Performance mode (Settings)**: one toggle that removes the recurring overload the
  motion choice can't cover — it forces `MotionStyle.NONE` app-wide regardless of the
  Appearance pick and publishes `LocalPerformanceMode`, which `VideoThumb` reads to
  skip the `MediaMetadataRetriever` stat/decode pipeline entirely (generic videocam
  icon instead). Lower CPU, RAM and battery; persisted as `performance_mode` in
  DataStore (defaults off; pinned by a SettingsRepositoryTest round-trip).
- **docs/LITE_EDITION_PLAN.md**: the lightweight-edition proposal — a `lite` Gradle
  flavor (separate APK, `applicationIdSuffix .lite`) that strips fonts (1.9 MB = 41%
  of the APK), the 24 extra launcher aliases, video thumbnails + coil-video,
  Categories/Analyzer screens, the update checker and 7z (commons-compress + xz),
  targeting ~2.5–3.0 MB APK, instant cold start and zero animation frames, with a
  three-PR sequencing plan.

### Changed
- **CI cleanup**: the Android CI unit-test step gains the same bounded suite re-run
  the release gate already had (Nov 2026 saw 4+ green commits painted red by a
  DIFFERENT random Robolectric/Compose flake each time); the Release workflow keys
  its concurrency group on the resolved tag, so a tag push and a UI-published
  release for the same tag cancel each other instead of double-building in parallel
  (v2.2.0 built twice 1s apart, the loser failing its gate); 163 stale workflow runs
  deleted from the Actions history.

## [2.2.0] — 2026-11-06

The wave-three release: the third remediation batch of the v2.0.0 full-repo audit
(docs/audits/AUDIT-v2.0.0.md) — the duplicated op machinery finally becomes one
engine, the repeated whole-tree walks become metadata, and the password bytes stop
outliving the operations that use them.

### Changed
- **A22 — one undo engine, one compress engine** (was copy-paste across three
  ViewModels): the byte-identical `undoDelete` loop (honest counts, collision-rename
  notes, media re-scan) that lived in Browse, Categories and Recent is now the shared
  `undoDeleteBatch` in domain/UndoOps.kt, and the per-archive compress loop
  (monotonic pipeline-totals progress, per-archive delete-sources gating) is the
  shared `ArchiveOps.compressBatch` — Browse and Categories can no longer drift
  apart. Pinned by the new UndoOpsTest and ArchiveOpsBatchTest.
- **A20 — trash size and media totals stop re-walking the world**: a trashed file's
  size is recorded in its sidecar at trash time, so the Storage tab's trash-usage
  card answers from metadata instead of walking every payload tree on every refresh
  (directory payloads and legacy sidecars keep the walk fallback); and the
  Categories/Storage media totals are computed in ONE pass over the media index —
  six aggregate queries plus six per-category row scans (an exists() stat per row,
  per category) become a single scan with the identical per-category counts.
- **A23 — the volume-split contract moved to its siblings**: `VolumeChoice`,
  `VOLUME_MB` and the byte mappers now live in domain/ArchivePrefs.kt next to
  `ArchiveFormat`/`CompressLevel` instead of inside the compress dialog's file, so
  ViewModels stop importing a ui.components type for a pure domain value.
- **A26 — the sort menu can no longer be reworded by an enum rename**: labels come
  from a stable mapping instead of `enum.name`; and the shared dialogs/sheets carry
  test tags now (`dialog_confirm`, `dialog_name_input`, `dialog_conflict`,
  `overlay_progress`, `dialog_result`, `dialog_password`, `dialog_compress`,
  `dialog_apk_install`, `sheet_file_actions`, `sheet_properties`,
  `dialog_archive_folder`), following the repo's snake_case convention.

### Fixed
- **A15 — password bytes never outlive the operation**: the `CharArray` handed to
  the 7z pipeline is wiped (`fill('\u0000')`) at the ArchiveOps seam when the
  extract ends and when a compress batch ends — success, failure or cancellation.
  Every production caller creates the array fresh per attempt, so nothing reuses it
  after the wipe. The typed `String`s in dialogs remain an accepted, documented
  residual (heap-lifetime hardening only).
- **A27 — the test suite tells the truth again**: the three vacuous
  conditional assertions (asserting only inside `if (x != null)`, which passed
  trivially when the stats were missing) are unconditional now; the stale
  `TODO(integration)` markers on the two password-flow tests that became real long
  ago are gone; the mangled `[movedFiles]`/`[movedEntries]` KDoc markers in
  MediaTrashOps are fixed; and the 10-second polling `await()` helper that was
  copy-pasted as a private member into ten test files is now one shared definition.

Still open after wave three: A10 (CI emulator job or honest removal), A12 (Kover +
verifyRoborazzi), A17 (release-signature pinning), i18n extraction of the hardcoded
component copy (the rest of A26), and the sleep-based race tests (the rest of A27).

## [2.1.1] — 2026-11-05

The come-back-where-you-were release: Browse finally survives a trip to
another tab, its re-tap actually resets, and the second remediation wave of
the v2.0.0 audit lands (nine more findings from docs/audits/AUDIT-v2.0.0.md)
plus a fix for the flaky test that broke the v2.1.0 release gate.

### Fixed
- **The Browse tab keeps your place across tab switches** (owner report):
  going Browse → (drill into a folder) → Home → Browse used to drop you back
  at the tab's start folder every time — the open folder now travels in saved
  UI state, which is exactly what a tab switch preserves (Navigation pops the
  tab with `saveState`, which keeps saved state but rebuilds ViewModels).
  Re-tap's "back to the top" is untouched: it still resets to the folder the
  tab first opened, and re-tap Browse works again at all (the v2.1.0 signal
  was compared against the full `"browse?dir={dir}&…"` route pattern and
  silently never matched).
- **A11** — the self-update installer path (`SystemUpdateDownloader`) has
  tests now: enqueue request fields, the delete-before-enqueue retry rule,
  the blank-URL guard, and the awaitDownload success/failure status mapping
  (Robolectric `ShadowDownloadManager`).
- **A13** — Recent's time buckets survive DST: "Yesterday"/"This week"
  boundaries step real calendar days (`Calendar.add(DAY_OF_MONTH)`) instead
  of fixed 24-hour subtractions, so a 23- or 25-hour transition day no
  longer files yesterday's recordings under "This week".
- **A14** — the launch splash no longer replays from the top on a
  configuration change mid-animation: hop/veil flags and the animation
  progress are saved with the activity state, as its own docs always claimed.
- **A16** — the About screen's URL handoff is https-only; a non-https link
  can no longer be talked into an ACTION_VIEW launch.
- **A19** — the empty-trash illustration builds its bin shape once and scales
  it at draw time instead of allocating a fresh `Path` on every draw pass.
- **A28** — zip publication no longer silently overwrites a file that
  appears between the exists-check and the move when replace is off: the
  move runs without `REPLACE_EXISTING`, so the race surfaces as an honest
  failure instead of data loss.
- **A29** — the copy engine's "same length → already copied" shortcut is
  gone; a same-length different-content destination is now copied
  unconditionally.
- **A30** — the archive entry-count walk is iterative, so a pathologically
  deep folder tree can no longer overflow the stack while publishing a
  progress total.
- **Flaky release gate** — `AppIconSwitcherTest`'s ghost-alias test now seeds
  every alias with an explicit PackageManager setting before probing; the
  bare before/after snapshot it used could read Robolectric's lazily-resolved
  manifest state differently across calls and fail the v2.1.0 gate spuriously.

### Changed
- **A7** — every accent scheme now derives `background` and the five
  `surfaceContainer*` tones from its own surface pair. Under CLASSIC accents
  those roles used to fall through to the M3 baseline palette (purple-tinted
  grays), which visibly clashed with warm surfaces in dark mode — the
  transfer bar, settings cards and appearance cards now stay in the accent's
  own voice. The default TOAD look is byte-identical to before.

## [2.1.0] — 2026-11-05

The checksum-and-time release: SHA-256 on every file's Properties sheet,
timestamps that finally show the time, extraction for the app bundles that
hold APKs — and the first remediation wave of the v2.0.0 full-repo audit
(fourteen findings from docs/audits/AUDIT-v2.0.0.md).

### Added
- **File times now include the time** (owner request): every file row and
  list subtitle — Browse, Home, Categories, Recent and the action-sheet
  header — shows "Nov 4, 2025, 14:32" instead of a bare date, so files
  modified on the same day are distinguishable at a glance. Trash keeps its
  date-only "deleted …" label; Recent's tiny grid-cell stamp stays a date to
  fit the cell.
- **SHA-256 checksums on the Properties sheet** (owner request): files get a
  "Checksum (SHA-256)" row — streamed on IO (1 MiB chunks, flat memory),
  cancellable with the sheet, with a "Calculating…" state for big files and
  the lowercase hex digest in a selection container so one long-press copies
  it into a verifier.
- **Tab re-tap jumps back to the top** (owner request): tapping the
  already-active tab means "take me back up", the standard Android re-tap
  contract. Browse returns to the folder its tab first opened (and drops any
  in-tree search), Categories closes the drill-in onto the category grid,
  Recent drops its type filter back to All, Trash clears the selection.
  Switching tabs still restores each tab's state exactly as before — the
  drill-in you left open is waiting when you come back; re-tap is the
  explicit "un-nest me". Works for the drawer's active item too.

### Fixed
- **App bundles (.xapk/.apkm) are now extractable** (owner request): they are
  ZIP containers holding the real .apk files, but every surface routed them
  to a useless external viewer with no way to pull the APKs out. Tapping a
  bundle now opens the same extract-or-open dialog as a zip, the action
  sheet offers Extract here / Extract to…, and the extraction yields the
  APKs inside whole — byte-identical, never unpacked (pinned by an
  end-to-end test). A plain .apk deliberately keeps going to the package
  installer.
- **Audit wave one — reliability** (docs/audits/AUDIT-v2.0.0.md):
  - **A1** — the shared op engine behind Recent's and Categories' ops is
    single-flight (fixes: double-tapping an archive operation no longer runs
    two at once; a new op cancels the previous one and waits for it to
    unwind, a cancelled op can no longer publish a result or resurrect the
    overlay after it is cleared).
  - **A2** — archive header parsing (entry counts) moved off the main
    thread: a multi-GB archive can no longer ANR the app at the exact
    moment the progress dialog should appear; the overlay starts
    indeterminate and the determinate total arrives from inside the op.
  - **A3** — "New folder" and the storage analyzer's top-level listing no
    longer do blocking disk I/O on the main thread.
  - **A4** — compress progress no longer mixes units: entries counted
    against selected items showed "37 of 1 items" and clamped the bar at
    the first tick; the pipeline's own totals are forwarded now.
  - **A5** — Categories ops re-list in place: deleting one image no longer
    snaps a LIST layout back to the category default, the list no longer
    blinks empty mid-operation, and the redundant second MediaStore query
    per op is gone.
  - **A8** — Categories/Recent request tokens are `AtomicLong`s now: two
    concurrent op completions could interleave non-atomic increments and
    both pass the staleness guard, letting a stale result overwrite a
    newer one.
  - **A9** — navigating the Browse tree cancels an in-flight recursive
    search instead of letting it walk directories to its limit for a
    result that can never apply.
- **Audit wave one — UI polish**:
  - **A6** — composition-time filesystem I/O removed from hot paths: video
    thumbnails no longer stat during every recomposition of every cell,
    the archive-folder picker lists directories on IO, and the extract
    destination picker's writability is carried in state instead of being
    re-stat'd in every TopAppBar recomposition.
  - **A18** — the file-type icon picker no longer rebuilds 6-7 sets per
    visible row per recomposition (hoisted to constants).
  - **A21** — action-sheet rows slide down like a swipe-to-dismiss instead
    of popping out of composition with no animation.
  - **A24** — the drawer divider sits between Trash and Settings, where it
    was meant to be, instead of cutting Trash off from its group.
  - **A25** — dead `RecentGrid(inSelection = …)` parameter removed.

## [2.0.0] — 2026-11-04

The full visual-identity release: five themes, three motion styles, six font
choices — plus the flaw fixes that make opening files honest again. Manrope
and the redesign stay the default look, so upgrading changes nothing until
you pick something new yourself.

### Added
- **A complete visual redesign** — the 2.0 look: bundled Manrope typography, a
  softer and rounder shape scale, a hand-drawn toad brand kit (custom duotone
  file-type icons for images/video/audio/docs/archives/APKs, a two-tone folder
  icon, a blinkable mascot), illustrated toad empty states for empty folders,
  the trash and no-results, and an animated launch splash where the toad hops
  in and blinks.
- **Theme picker on the Appearance tab** — five full identities: Dark forest
  (the new default: moss on near-black with amber highlights), Material You
  (wallpaper colors), Material 3 (Google's reference palette), Slate + Neon
  (graphite on OLED black) and Classic toad (the original palette, keeping the
  seven accent colors). Light/Dark/System stays per theme; upgraders who had
  customized their look keep it, everyone else gets the redesign.
- **Motion picker on the Appearance tab** — Springy toad (springs with a soft
  bounce), Calm & precise (quick eased fades) and Cinematic (longer arcs with
  scale). The choice drives nav transitions, the transfer bar's slide, the FAB
  hop and the splash, app-wide.
- **Font picker on the Appearance tab** — the device's **System** font, the
  bundled **Manrope**, the unique chunky **Toad** font (Baloo 2 under the SIL
  OFL, instanced, Latin-subsetted and renamed per the license — see
  docs/licenses), **Inter**, **Space Grotesk** and **JetBrains Mono**. Every
  entry previews in its own letterforms; the pick applies app-wide through the
  tuned 2.0 type scale (Manrope stays the default, so nothing changes until
  you choose).
- **New launcher icon**: the "Toad mark" design joins the icon picker and is
  now the shipped default, in all six background tints.

### Changed
- File rows on Home and Recent show the toad file-type icons instead of plain
  extension monograms.
- Cold start no longer flashes a light background in dark themes
  (values-night window background).
- Test reliability: HomeViewModel tests cancel their ViewModel scopes so eager
  jobs can no longer straddle a test boundary and crash the shared test Main
  dispatcher under CI load.

### Fixed
- **APK taps open the Android package installer** (owner report: an APK was
  "recognised as doc" and tapping it jumped straight into a notes app). On
  Recent, Home's recents and favorites and the Categories drill-in, an .apk now
  routes to the installer behind the same confirm dialog and "Install unknown
  apps" gate Browse's zip rows use — never to a generic VIEW resolver that
  whatever note-taking app happens to claim the type can hijack. `.xapk`/
  `.apkm` bundles stay with external viewers (they are not installable
  packages).
- **Archive taps ask — they never redirect** (owner report: picking toadFILES
  from the system "Open with" sheet for a zip dropped the user in a folder; a
  stream-copied archive even auto-extracted into Download with no say at all).
  The extract-or-open dialog now ALWAYS appears, with three choices: **Extract
  here** (for handed-over stream copies: into Download, labeled as such),
  **Extract to…** (navigate anywhere and pick the folder) and Open. Tapping an
  archive inside toadFILES (Recent, Home, Categories, their action sheets)
  routes into the same dialog instead of firing an ACTION_VIEW that, now that
  toadFILES registers as an archive handler, bounced back into the app or off
  into another archiver.
- The "Open with → toadFILES" handoff lands Browse on a folder the user can
  actually see — the archive's own folder, or Download for stream copies that
  only exist in the app cache — instead of the invisible cache directory.
- **A ghost icon choice can no longer blank the app from the launcher**: the
  launcher-icon switcher now verifies the chosen alias exists in the installed
  build before touching any component state. A persisted choice naming an
  alias the running build doesn't ship (a downgrade or a restored backup)
  used to disable all 42 real launcher entries while "enabling" nothing —
  the app vanished from the home screen until the next install.

## [1.10.2] — 2026-11-04

A one-flaw patch release: toadFILES never showed up in the system
"Open with" sheet, so handing a ZIP to the file manager that can
actually unpack it was impossible.

### Fixed
- **toadFILES now appears in the system "Open with" sheet for ZIP and
  7z archives** (owner report: opening a .zip from Recent offered
  everything but toadFILES itself). The filter is deliberately narrow —
  only the archive types the app can really extract; no catch-all.
- **"Open with → toadFILES" now works end to end**: the handed-over
  file is resolved to its real path (or copied out of a stream when it
  has none), then opens the same extract-or-open dialog a tap in Browse
  shows; extraction keeps the full conflict/password/progress flow and
  post-extract redirect.
- **Stream-only archives** (e-mail attachments, cloud previews) extract
  into a folder named after the archive under Download instead of an
  invisible cache folder.
- **Picking toadFILES while it is already open** no longer stacks a
  second copy of the app on the back stack (single-task launch).


## [1.10.1] — 2026-11-04

A small patch release that rounds off the 1.10 transfer-UX overhaul: the
Appearance dropdowns match across screens, the file action sheet shows the
full path, and archive extraction gained a proper Browse destination picker.

### Fixed
- **Consistent Appearance dropdown styling on the main Settings screen**:
  rounded 20dp menu, tonal elevation, Material body typography, a
  primary-colored selected option and a trailing checkmark — the same
  treatment the navigation-style dropdown on the Appearance screen already
  had.
- **Full file path in the file action sheet**: the path row now shows the
  complete path, scrolls horizontally when it is too long, and can be tapped
  to copy it.
- **"Extract to…" uses a Browse destination picker** instead of a bare name
  prompt: navigate folders, confirm the current folder, or cancel — "Extract
  here" remains the quick action, and extraction still reuses the existing
  conflict/password/progress flow.

## [1.10.0] — 2026-11-03

A transfer-UX overhaul release: a redesigned move/copy transfer bar, real
progress with cancel everywhere, conflict flows that no longer dead-end,
the show-in-folder pinpoint, and a long sweep of wording, pluralization
and icon honesty fixes across the app.

### Added
- **Redesigned move/copy transfer bar**: one calm M3 surface replaces the old
  PasteBar + arming-Snackbar double feedback — intent-shaped icon (move vs
  copy), action-first title ("Move to here" / "Copy to here"), properly
  pluralized item count, an explicit Cancel button and a filled Paste button.
  Paste is disabled with an inline reason when a MOVE would nest a folder
  under itself; copy stays always allowed.
- **Extract-here conflict flow**: extracting an archive onto an existing
  destination folder no longer dead-ends with a refusal toast — Browse,
  Categories and Recent now open the shared conflict dialog (Keep both /
  Replace / Skip), the same flow "Extract to…" already used.
- **Progress overlays that say what they are doing**: the shared progress
  overlay now reports an animated determinate bar with percent, "N of M
  items" (proper plural) and the live current entry name ("Extracting
  pack.zip"); unknown totals stay indeterminate with the entry name shown.
- **Cancel for the trash batch flows**: empty trash, batch delete-forever
  and batch restore all run through one shared runner rendering the same
  progress overlay with a working Cancel — cancellation stops at the next
  item boundary, keeps everything already processed, still rescans
  restored files, and fires an honest "Stopped — X of Y" summary before
  refreshing the list to the truth.
- **Show-in-folder pinpoint**: "Show in folder" no longer just opens the
  parent folder — the listing scrolls the file into view and flashes its
  row/cell twice (list and grid layouts); a vanished or filtered target
  degrades to a plain folder open.
- **Operation-titled result dialogs**: "Copy finished", "Move finished",
  "Extract finished" etc., derived from the running operation instead of
  the generic "Operation finished".

### Changed
- **Honest trash wording app-wide**: every red row and icon that actually
  moves items to the 30-day recoverable trash now says "Move to trash";
  "Delete forever" stays exclusive to the Trash page's permanent actions.
- **Proper item pluralization everywhere**: "1 item / 2 items" across
  trash confirms, compress/zip menus, conflict dialog, trash toasts,
  storage and category subtitles — the "item(s)" pattern is gone.
- **Sort/filter menus mark selection with a Material 3 trailing check
  icon** (announced as its own word by TalkBack) instead of a "✓" text
  glyph glued onto the label.
- **Sentence case sweep**: the archive folder picker ("Archive folder",
  "Up one level", "Same folder as source", "Use this folder") and the
  Settings General block rows and choice labels follow the app-wide
  sentence-case convention; ZIP/7z acronyms stay upper.
- **File icons match the friendly type names**: .heif shows the image
  icon and .htm/.html the document icon, matching what the properties
  sheet promises.

### Fixed
- **New-folder FAB no longer overlays the armed transfer bar** (and the
  same bottom-edge dock conflict in selection mode); the FAB returns
  after Paste or Cancel.
- **Category drill-in dead band removed**: the entries list consumed the
  Scaffold insets twice, pushing the whole list a full top-bar height
  down; insets are consumed once, matching Browse.
- **A vanished source is a reported failure, not a silent "skip"**:
  pasting/moving a batch whose file was deleted elsewhere (or whose
  removable storage is gone) now shows "N failed" in the result summary
  and names the missing file in Details; a user-chosen conflict Skip is
  still a skip.
- **Empty trash acts on the trash at execution time**, not on a stale
  screen snapshot that could miss items trashed from elsewhere after the
  last refresh.

## [1.9.1] — 2026-11-03

The final release of toadFILES — the app now ships an in-app What's New
changelog, a rebuilt About page and relocated navigation settings, plus
trash and pull-to-refresh reliability fixes. Supersedes v1.9.0 as the
latest version.

### Added
- **What's New page**: a full in-app changelog fed by this file, which is now bundled
  into every APK at build time (Gradle `syncChangelog` copies the repo-root
  CHANGELOG.md to `app/src/main/assets/changelog.md` before `preBuild`) and parsed at
  runtime by a tolerant Keep-a-Changelog reader — release notes are no longer
  hardcoded in the app. The installed version's release leads as an expanded card with
  per-section icons (Added / Changed / Fixed / Removed / Deprecated / Security),
  older releases are expandable, and a build with no entry — or an unreadable asset —
  says so honestly.
- **About: Capabilities card** — only features that actually ship: file operations on
  internal/SD/OTG storage, ZIP & 7z (including AES-256 and split volumes), 30-day
  trash with restore/undo, media browsing and the storage analyzer, search (including
  content search), share/open-with/APK install, and theming.
- **About: Permissions card** — each manifest permission with a plain-language reason:
  INTERNET (update check only), All files access (a file manager must read and change
  the files you act on), Install unknown apps (system-installer handoff).
- **About: Source & project card** — repository and releases links (the page had no
  URL before) plus the GPL-3.0 statement.
- **Tests**: a Keep-a-Changelog parser suite (structure, Unreleased/footers skipping,
  missing dates, markdown flattening, unparsable-input fallback, and a bundled-asset
  ↔ `BuildConfig.VERSION_NAME` consistency check) and Appearance screen tests for the
  relocated navigation preferences.

### Changed
- **Settings**: the Back Key Action and Navigation Style preferences moved from the
  General section into the Appearance tab as a Navigation card between Theme and App
  icon; same setters, same DataStore keys (`nav_style`, `back_key_action`), no
  behavior change. The Settings → Appearance entry now reads "Theme, colors,
  navigation, app icon".
- **About rebuilt around verified facts**: version/build/Android-compatibility rows
  come from `BuildConfig` (`VERSION_NAME`, `VERSION_CODE`, new `MIN_SDK_VERSION` and
  `TARGET_SDK_VERSION` fields), the open-source credits were cross-checked against the
  actual Gradle implementation dependencies (test-only Robolectric dropped; Apache
  Commons Compress and XZ for Java added), and the privacy and update-check wording
  now describes the real behavior consistently everywhere: local file operations only;
  one silent `api.github.com` update check per app open plus the user-initiated check;
  nothing downloads without a tap; no telemetry, ads or accounts; nothing ever
  uploaded.
- **What's new on the About page** now previews the latest parsed release (up to four
  bullets) with a "See all changes" jump to the full page; the duplicated hand-written
  changelog list was deleted.

### Fixed
- **Trash & Recent**: deletes route through one shared MediaTrashOps pipeline, and
  a `CURRENT-DATA` guard in `deleteStale` stops a stale maintenance pass from
  clobbering live data; Recent reaches undo parity with Browse, reports honest
  delete buckets, and refreshes with a single query.
- **Categories**: pull-to-refresh with a category open re-queries the open drill-in
  in place — removed entries are pruned without a spinner swap, and the refresh
  survives hiccup/stale-result races.

## [1.9.0] — 2026-11-02

The personalization & housekeeping release.

### Added
- **App icon picker**: 5 new launcher icon designs (toad face, document, crowned
  toad, pixel toad, SD card) in the app's flat toad style, each with 6
  background colorways — 36 launcher aliases in total, all switchable from a
  new **App icon** section in Settings → Appearance with live-preview tiles and
  color swatches. The choice persists via DataStore and is re-applied at
  startup; upgraders keep the classic look until they pick one.
- **Recent filter chips**: the Home Recent section gains All / Images / Videos
  / Audio / Docs / Archives / APKs chips mirroring the Recent page, with a
  filter-aware empty state.

### Changed
- **Home**: compact layout — the status-bar inset is applied once (it was
  double-stacked since v1.8.1), a unified 16 dp gutter via a shared section
  header, and reduced card/footnote paddings.
- **Quick Access** rebuilt as non-lazy rows: no more clipped 100 dp rows, cut-off
  names or wasted placeholder space; editor labels are Pinned/Automatic and the
  default quick links can no longer be starved by pins.
- **Browse**: the search bar and the hidden-files toggle moved into a new
  overflow menu in the top bar. The search field is hidden by default
  (reclaiming a row of vertical space), auto-focuses when opened, and Back
  closes it after clearing the query.

### Fixed
- **Trash**: restored/undone files are re-indexed into MediaStore so they
  reappear in Recent, Categories and galleries; the undo snackbar is one-shot
  (navigating away can no longer resurrect a stale offer or claim the file
  stayed in Trash); restore is disabled for unknown-origin entries with honest
  reason messaging; collisions, partial empty-trash failures and dangling
  symlinks are reported truthfully; sidecar files use URL-encoded origins and
  255-byte name truncation.

## [1.8.2] — 2026-11-01

The analyzer-hardening release — final version for now. No new features; it
makes the storage analyzer correct under concurrency and honest on screen.

### Fixed
- **Analyzer**: a scan superseded by a newer one (Refresh, another volume,
  "Change volume") can no longer clobber the live scan's progress — the stale
  walk's finalizer used to reset the counters and hide the progress card after
  its replacement had started (scan-generation guard, with a regression test).
- **Analyzer**: the tapped file's size is no longer read on the main thread
  (StrictMode-clean properties sheet).

### Changed
- **Analyzer**: one metadata read per file during scans instead of two (faster
  walks on large trees); the deep-link auto-scan survives rotation and
  back-navigation without restarting the walk; the volume picker stays
  reachable after "Change volume" on a deep-linked entry (the placeholder could
  previously deadlock the screen); volume rows have a single labelled TalkBack
  target instead of a nested button; the folders' sort menu no longer offers a
  no-op "Recently modified" choice; zero-size categories no longer paint a
  phantom bar; the Top-N chips scroll instead of overflowing on narrow
  screens; Refresh is disabled while a scan is running.

## [1.8.1] — 2026-11-01

The UX polish release: navigation, spacing, Settings and the minimum
Android version.

### Changed
- **Trash as a Quick access tile**: the top-right Trash toolbar icon on Home
  is gone (it crowded the status bar / storage card). A permanent Trash tile
  now leads the Quick access grid, so every destination is reachable the
  same way. Side-menu mode keeps its drawer entry.
- **Top spacing fixed at the source**: the navigation shell now applies the
  status-bar inset exactly once and every screen TopAppBar zeroes its own
  insets — no screen can double-pad (blank band) or under-pad (content
  under the clock) anymore. Home card/margin sizes tightened throughout;
  Categories headers and tiles slimmed down.
- **Settings redesigned**: tonal icon chips per row, grouped surface cards,
  value labels instead of "Change" links, chevrons for navigation rows, and
  `animateContentSize` transitions. Screens navigate with a gentle
  cross-fade + micro-slide instead of an abrupt swap.
- **Recent layout toggle**: list ⇄ grid from the toolbar, persisted across
  launches (`recentLayout` setting), with thumbnails, group headers,
  multi-select and the per-cell ⋮ sheet.
- **About page trimmed**: "What's new" shows the latest two updates (full
  history stays in CHANGELOG.md); the "Storage on this device" and
  "Files on this device" data cards were removed (that information lives on
  the Home storage card and the Storage page), along with the now-pointless
  toolbar Refresh.
- **Minimum Android version is now 14 (API 34)**: minSdk raised from 26,
  legacy `READ/WRITE_EXTERNAL_STORAGE` permissions and
  `requestLegacyExternalStorage` removed, and the pre-R permission branch
  in MainActivity simplified to the single "All files access" flow. Docs
  and the About "Min Android" row updated to match.

## [1.8.0] — 2026-10-31

The ZArchiver-parity archive release: encryption, volumes and the full
"Create archive" dialog.

### Added
- **Password-protected 7z archives (AES-256)**: the Create archive dialog
  offers "Data (AES-256)" encryption for 7z — password + confirm fields with
  a show/hide toggle. Encrypted archive names stay readable (data-only
  encryption, like ZArchiver's "Data" option). Extracting an encrypted 7z
  prompts for the password and retries; a wrong password says so and never
  publishes anything. Encrypted ZIP stays unsupported (honest message) —
  only 7z encryption is offered.
- **Split into volumes**: create archives as "name.001, name.002, …" sets
  (No / 5 MB / 10 MB / 50 MB / 100 MB / 700 MB / 1 GB / custom). Volume
  sets are extracted by tapping the ".001" file — zip volumes and 7z volumes
  both work; a set with a missing volume fails before anything is written.
- **Six ZArchiver compression levels**: No compression, Fastest, Fast,
  Normal, Maximum, Ultra (7z Ultra uses a 128 MiB dictionary; zip's Ultra
  equals Maximum because deflate caps at level 9).
- **Full ZArchiver-style Create archive dialog**: format and level
  dropdowns, encryption selector, password pair, volume size selector, a
  "Delete source files" checkbox (pre-filled from the setting) and
  "Create separate archive for each item" for multi-select.
- Volume sets created by other apps (ZArchiver, 7-Zip) are detected and
  extracted too.

### Fixed
- Tapping a ".7z.001" volume start now honors the 7z open-action setting
  instead of falling into the zip branch.

## [1.7.5] — 2026-10-31

Closing the v1.7.4 audit's known gaps.

### Added
- **Trash multi-select**: long-press to enter selection mode, then batch-restore
  or batch delete-forever (with confirmation) — matching Browse and Recent.
- **Categories compress dialog is now Browse's full dialog**: archive name,
  destination preview, format (ZIP / 7z) and compression level — shared
  component, same overwrite-safe hardened pipeline.

### Fixed
- **Storage uses pull-to-refresh** like Recent/Trash/Categories; the leftover
  toolbar Refresh button is gone (the last one in the app).

## [1.7.4] — 2026-10-31

Audit release: the navigation-style switch from v1.7.3 now works as designed,
and a full UX + logic audit fixed the flaws it uncovered.

### Fixed
- **The two navigation styles are actually separate now.** Bottom-bar mode no
  longer wraps the app in a side drawer (no hamburger icons, no duplicate
  Settings entry), and side-menu mode drops the bottom bar entirely. The side
  menu now lists every destination: Home, Browse, Categories, Recent, Trash,
  Settings and About (Home and Categories were unreachable in drawer mode).
- **Removed the dead band at the top of the app**: the branding header on Home
  is gone and the status-bar inset is applied once by the shell instead of being
  doubled by every screen, so content starts directly under the status bar.
- **Removed duplicate navigation icons** (hamburger on top-level screens in
  bottom-bar mode, the extra settings/menu buttons on Home).
- **Delete-after-extract never fires on a partial failure or abort**: the archive is
  only moved to the trash when the extraction completed with zero failures and no
  cancellation (all surfaces: Browse, Categories, Recent). Previously an archive
  with one corrupt entry was trashed even though the extraction was incomplete.
- **Categories now honors delete-after-compression**: successful compressions from
  the Categories drill-in move the sources to the trash when the setting is on,
  matching Browse.
- **Categories "Extract to…" and Compress now default to the archive-folder
  setting** (when set), matching Browse's destinations.
- The Recent delete dialog no longer claims the Trash lives "in the side menu"
  when the bottom-bar navigation style is active.

## [1.7.3] — 2026-10-31

Big UX release: 7z creation, a redesigned Settings with Appearance page and
General preferences, Browse-level feature parity in Categories, a MI Files-style
storage analyzer, pull-to-refresh everywhere and the two-navigation-styles switch.

### Added
- **7z archive creation** (Browse): "Compress" now offers ZIP *and* 7z. The 7z
  pipeline mirrors the hardened zip one — staged temp file with atomic rename,
  no-follow symlink walk, per-file determinate progress, cancellation checkpoints,
  duplicate-name and entry-count caps. STORE uses the uncompressed COPY codec;
  Fast/Normal/Maximum map to LZMA2 presets 1/6/9 (xz defaults).
- **Compression level chooser** for both formats: Store / Fast / Normal / Maximum
  (zip → Deflater levels, 7z → LZMA2 presets) in the new compress dialog, which
  also shows the destination folder (the archive-folder setting when set).
- **New General settings** (image-inspired): per-format archive open actions
  (ZIP and 7z separately: show menu / extract immediately / open with), back-key
  action (parent folder / exit app), navigation style (bottom bar / side menu),
  delete-source-after-compression, delete-archive-after-extraction, abort
  confirmation, overwrite confirmation, and an **archive folder** picker used
  as the default destination for both compression output and "Extract to…".
- **Appearance subpage** in Settings: theme mode, dynamic color and accent
  color moved to a dedicated page.
- **Browse-level file actions in Categories**: every category drill-in row
  now opens the exact same action sheet Browse uses — Extract here / Extract
  to… for `.zip`/`.7z` (with the shared conflict dialog on a name clash),
  Rename, Compress, Properties (the full properties sheet, not a summary
  dialog), Share, Delete-to-trash with Undo, Copy path, Open with… and Show
  in folder; plus Browse's sort menu and list/grid toggle, and batch
  operations (compress the selection, extract, properties, rename) in
  multi-select.
- **Storage analyzer** (MI Files style): "Analyze storage" on the Storage page
  scans a volume with live progress and shows a per-category size breakdown,
  the biggest files and the largest folders (non-nested, MI Files style) with
  tap-through to properties or the folder.
- **Pull-to-refresh** on Browse, Recent, Categories and Trash; the toolbar
  refresh buttons are gone.
- **Archive tap actions** in Browse honor the per-format settings above.

### Changed
- **One action sheet implementation**: Categories and Recent now render the
  shared `FileActionSheet` (and the shared `FileRow`/`FileGridCell` rows)
  that Browse uses, instead of a trimmed-down media variant; the Recent
  sheet's "Details" row is now labeled "Properties".
- **Trash removed from Settings**: the side-menu Trash page is the one entry
  point (the Settings card is gone).
- **Delete after compress / delete after extract**: successful operations move
  the sources to the toadFILES trash (30-day retention, undoable) — the app's
  delete convention, never a silent permanent erase.
- **Overwrite confirmation** when the target archive already exists (honors the
  overwriteConfirm setting; decline returns to the dialog for a new name).

## [1.7.2] — 2026-10-31

### Fixed
- **App remains usable after granting storage access**: the main navigation
  is now gated only by the storage permission. The optional startup update
  banner no longer controls whether Browse and the rest of the app render.
  This also fixes the onboarding screen reappearing when no update is
  available, the update check fails offline, or the banner is dismissed.

All notable changes to toadFILES are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are tagged `vX.Y.Z` and produce a signed release APK via CI.

## [1.7.1] — 2026-10-31

UX and reliability release driven by user feedback: archive handling
fixes, 7z support, progress bars, a startup update check, a Google
Files-style side menu, and Recent improvements.

### Added
- **7z archive support**: `.7z` files extract through the hardened
  archive pipeline (entry-count and byte caps, path-traversal defence,
  staged atomic publish) via Apache Commons Compress. Extract-only;
  password-protected archives are detected and declined with a friendly
  message.
- **Progress bars for ZIP and 7z**: zip extraction prescans the central
  directory, zip creation pre-walks sources, and 7z totals come from the
  header — a determinate `LinearProgressIndicator` with indeterminate
  fallback, cancellable, all work off the main thread.
- **Silent auto update check on app open**: once per process, no
  periodic polling, never auto-downloads; a dismissible banner appears
  when a newer version is available and deep-links into the existing
  Download & install flow.
- **Side navigation drawer** (Browse / Recent / Trash / Settings /
  About) in the Google Files style; Trash is reached from the drawer as
  its own page.
- **Recent grouping**: entries grouped under Today / Yesterday / This
  week / Older headers.
- **Missing changelog entries** for v1.6.0–v1.6.2 added to the in-app
  What's New list.

### Fixed
- **Archive tap no longer triggers "open with"**: tapping a `.zip`/`.7z`
  in Browse opens the extract actions instead of firing an ACTION_VIEW
  intent.
- **After extraction the app now navigates into the extracted folder**
  (both "Extract here" and "Extract to…" paths).
- **GitHub update-check connection hardening**: redirects are followed,
  one retry on transient IOException/5xx, a distinct friendly message on
  rate-limit (403), and human-readable connection errors.

## [1.7.0] — 2026-10-31

Feature and cleanup release: archive extraction everywhere, an in-app
update check, a properties sheet, and removal of dead controls and
duplicated plumbing.

### Added
- **Check for updates (OTA)** on the About page: a user-initiated check
  against GitHub Releases with semantic version compare. When a newer
  signed APK is published, it is downloaded via the system
  DownloadManager and handed to the existing install flow. The new
  `INTERNET` permission exists for this one purpose (commented as such in
  the manifest): nothing runs in the background, there is no telemetry,
  and the onboarding copy states the updated contract.
- **Unzip surfaced everywhere**: Browse gains "Extract to…" with the
  existing conflict dialog (Replace / Keep both / Skip), and Recent and
  Categories archive rows get "Extract here" — all through the hardened
  archive pipeline (size/entry quotas, path-traversal rejection, staged
  atomic publish) with shared progress, cancel and result reporting.
- **File/folder Properties sheet** in Browse: type, location, recursive
  size and contents count, modified time, hidden flag — computed on IO
  and cancellable on dismiss.

### Fixed
- P2 reliability pass (PR #4): hardened recursive search, transactional
  ZIP replace with per-entry backup and rollback, cancellation checkpoints
  that clean partial output, crash-safe trash metadata handling, stale
  browse listings, and honest cross-volume move errors; the flaky CI unit
  test that intermittently failed main was fixed.

### Removed
- Non-working "Diagnostics (planned)" toggle (About card, Settings
  plumbing, DataStore key, tests) — the app collects no diagnostics.
- ~180 lines of duplicated Recent/Categories action and sheet plumbing
  consolidated behind a shared OpRunner.
- CI no longer builds or uploads an unsigned release APK on pushes and
  PRs; signed APKs come exclusively from tag-triggered release builds.

## [1.6.1] — 2026-10-30

Reliability and security pass driven by the full codebase audit
(`docs/MAIN_REPO_AUDIT.md`). Six remediation streams merged: data integrity,
MediaStore integration, intents/handoffs, state handling, archive hardening,
and CI.

### Fixed
- **Stale Recent entries**: MediaStore rows are cleaned up repo-side, queries
  carry request tokens so a slow outdated query can never overwrite a newer
  result, and category/home hydration is guarded by a mutex with ViewModel
  cancellation. Video thumbnail cache keys are stamped with file identity so
  renamed/replaced files no longer show old thumbnails.
- **APK installation**: distinct installer outcomes instead of silent
  failures — the pending APK is retained across the
  "install unknown apps" settings round-trip (no re-pick), and if the system
  refuses the settings handoff the app says so instead of pretending a retry
  is possible.
- **Share/open handoffs**: sharing 2+ files now carries *every* URI in
  ClipData with grants — receivers could previously only read the first item.
  Open-with behavior is documented honestly (the system resolver offers
  Just once / Always; a single handler opens directly — platform behavior).
- **Data safety**: recursive traversal is symlink-safe (cannot escape the
  starting tree), move/replace operations are transactional with trash
  metadata preserved, and the trash metadata (`tfmeta`) payload encoding was
  fixed.
- **State races**: categories screen overflow, quick-access tiles no longer
  break after the underlying folder is moved or renamed, and folder listings
  can no longer be poisoned by cancelled loads.

### Security
- **Archive extraction hardening**: byte-size and entry-count/ratio quotas
  (zip-bomb protection), staged extraction into a temporary directory with
  atomic publishing, and path-traversal entries rejected.
- **UriGuard denylist**: files under system/private locations are never
  offered to external apps; FileProvider gained a catch-all root so files on
  removable SD/OTG storage can still be shared.
- **MediaStore row URIs**: documents, archives and APKs now use proper
  `content://media/external/file/<id>` URIs, with archive MIME coverage
  extended — open/share/delete work for generic file types.

### CI
- Instrumentation suite runs on API 29/33/36 emulators (advisory), with
  userdata sizing fixed for hosted runners and `continue-on-error` at the
  job level.

### Docs
- Full codebase audit, concrete remediation plan, and release checklist added
  under `docs/`; README gained CI badge and instrumentation notes.

## [1.6.0] — 2026-10

### Added
- Long-press on any file/folder starts multi-select in Browse, Categories and
  Recent (Google-Files style); per-row ⋮ overflow menu replaces the old
  action sheet.
- Recent filter tabs: All / Images / Videos / Audio / Docs / Archives / APKs,
  backed by MediaStore per-category queries.
- Customizable Quick Access on Home: pin/unpin folders from Browse's action
  sheet, edit the row via the ＋ dialog, long-press a tile to remove it, and
  most-used folders (2+ visits, never pinned/removed) surface automatically.
  Visit counts are buffered in memory and flushed to DataStore on app stop.
- ZIP creation always asks for the archive name (suggested from the
  selection, `.zip` appended, path separators rejected); tapping a ZIP offers
  "Extract here" or "Open".

### Changed
- Browse's "Recently deleted" chip removed; the trash stays reachable from
  Home and Settings.
- Settings changes that don't affect the listing (pins, favorites, …) no
  longer reload the current folder.
- Regenerated README screenshots for the updated Home / Browse / Recent.

---
Releases before 1.6.0 predate this changelog; see the GitHub releases page.
