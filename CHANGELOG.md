# Changelog

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
