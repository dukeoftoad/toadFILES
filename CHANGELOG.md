# Changelog

All notable changes to toadFILES are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are tagged `vX.Y.Z` and produce a signed release APK via CI.

## [Unreleased]

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
