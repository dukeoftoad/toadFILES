# Changelog

All notable changes to toadFILES are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are tagged `vX.Y.Z` and produce a signed release APK via CI.

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
