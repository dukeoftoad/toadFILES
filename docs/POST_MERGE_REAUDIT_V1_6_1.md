# toadFILES — consolidated engineering audit and release-readiness report

**Single source of truth:** historical v1.6.0 baseline audit, post-merge v1.6.1 re-audit, API 35/36 platform and feature-scope review, dependency/lightweight review, and manual device/OEM release checklist.

**Current reviewed code:** `main` at `f63a2ea4b7fb9accb43a9e8f14dc039709554c72` (v1.6.1). **Baseline:** `6858540c531b0219ec81cdb347f81623fa5a8286` (v1.6.0). **Audit date:** 2026-10-30.

The baseline audit below is historical context. The post-merge re-audit is authoritative for current findings. Where they differ, use the newer remediation-status table. This is a static/source audit plus recorded CI evidence, not device-level QA sign-off. Product simplification items are recommendations to validate, not proven defects.

## Executive priorities

1. Resolve 3 P1 security/data-recovery findings before the next public release.
2. Resolve 11 P2 reliability/correctness findings and restore green main CI.
3. Decide whether Android 15/API 35 is the actual minimum: current minSdk is 26 despite targetSdk 36.
4. Keep the local-first/no-network product lean; consolidate overlapping navigation and defer speculative expansions.
5. Complete API 35/36 device checks and the manual release checklist.

---

## Part I — Historical full repository audit (v1.6.0 baseline)

# Full codebase audit — toadFILES

**Audit date:** 2026-10-30  
**Audited baseline:** `main` at `6858540c531b0219ec81cdb347f81623fa5a8286` (v1.6.0, versionCode 8)  
**Review type:** repository-wide static source, test, configuration and workflow audit  
**Status:** findings require triage; this document does not claim device-level reproduction.

## 1. Scope and method

I enumerated the complete tracked Git tree (101 files; tree not truncated) and reviewed the application source, test inventory, Android resources, build files, workflows, README and project documentation.

| Area | Files reviewed |
|---|---:|
| Production Kotlin under `app/src/main/java` | 38 |
| JVM/Robolectric test files | 28 |
| Android instrumentation test files | 2 |
| Android resources / manifest | 9 |
| GitHub Actions workflows | 2 |
| Documentation and screenshots | 8 |
| Root/build/other tracked files | 14 |
| **Total** | **101** |

The JVM test tree contains approximately **218 `@Test` methods**; the instrumentation tree contains **6**. The workflow runs lint, JVM unit tests and APK assembly, but does not run the instrumentation suite on an emulator.

The review covered file operations, trash/restore/purge, ZIP create/extract, path safety, MediaStore queries and URI handling, intents/sharing/APK installation, storage roots, settings persistence, all screen ViewModels and major Compose screens/components, thumbnails, navigation, manifest/provider configuration, Gradle and release automation.

This is a static review of the named revision. I did not run an emulator or physical device, inject filesystem failures, or execute the full Gradle suite as part of this audit. Findings are labelled as source-confirmed defects or platform risks requiring runtime validation.

## 2. Executive summary

The codebase has useful separation between data/domain/UI layers and a meaningful JVM regression suite. The principal release risks are concentrated in destructive filesystem paths and provider integration:

- Recursive deletion follows directory symlinks and can delete the symlink target's contents.
- ZIP extraction's aggregate byte and expansion-ratio guards rely on ZIP header sizes that can be unknown.
- REPLACE copy/move deletes the old destination before the replacement is known to be complete.
- Trash metadata is not committed transactionally and can be deleted before the payload.
- Generic MediaStore file URIs are constructed with a path that does not match Android's Files collection API.

**Severity count:** 5 P1 · 16 P2 · 3 P3

### 2.1 Remediation status on this branch (PR #1, 2026-10-30)

Every finding below has been implemented on `fix/toadfiles-reliability-and-ux` in
parallel workstreams (`pr1/a-data`, `pr1/b-zip`, `pr1/c-media`, `pr1/d-state`,
`pr1/d2-intents`, `pr1/e-ci`) with regression tests. Static/IOC-level verification only:
device/OEM behavior follows `docs/RELEASE_CHECKLIST.md`.

| Finding | Status | Resolution |
|---|---|---|
| P1.1 symlink delete | **Fixed** | `FileOps.deleteRecursively` uses `Files.walkFileTree` + no-follow; links unlinked, dangling links removed, cycles impossible; symlink policy applied in `sizeOf`/copy. Tests: external-target survival, dangling link, cycle, nested delete. |
| P1.2 ZIP limits | **Fixed** | Actual emitted bytes drive per-entry/aggregate caps (overflow-safe); ratio guard via `CountingInputStream` never trusts `-1` headers; extraction into staging dir, published only on success. Tests: data-descriptor bombs, byte/ratio caps, no partial tree. |
| P1.3 destructive REPLACE | **Fixed** | `swapReplace`: stage → backup old → swap; backup restored on any failure; same for cross-volume move. Tests: injected failure preserves old destination byte-for-byte; cancellation cleans staging. |
| P1.4 trash ordering | **Fixed** | Sidecar prepared as `.pending`, payload moved, metadata committed atomically; commit failure rolls the payload back (`TrashResult.moved/rolledBack/failed`); `deleteForever` deletes payload first, sidecar only after success. Fault-injection tests. |
| P1.5 MediaStore URIs | **Fixed** | Generic rows built with `ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)` (2-arg overload is API 29+; minSdk 26). Test compares against the platform API, not a hardcoded string. |
| P2.1 stale rows | **Fixed** | `list()`/`recent()` collect ghost rows and reconcile (`resolver.delete`) after the cursor closes, capped at 50/query — Home, Recent and categories share the behavior; ViewModels keep their explicit calls. |
| P2.2 `.tfmeta` payloads | **Fixed** | Sidecars moved to `<trash>/.meta/`; legacy sidecars migrate on first listing; orphan sidecars pruned; a user file named `*.tfmeta` lists/restores/purges normally. Tests. |
| P2.3 share grants | **Fixed** | `grantClip()` puts EVERY uri in ClipData on the send intent and the chooser wrapper. Test asserts all URIs in clip + extra. |
| P2.4 categories actions | **Fixed** | Overflow affordance on list rows and grid cells wired to the existing `MediaActionSheet`. |
| P2.5 recent filter tabs | **Fixed** | Filter row rendered independently of entries; empty state shows beneath it. |
| P2.6 async overwrites | **Fixed** | `MediaQuerySource` interface + per-VM job cancellation/request tokens in Recent, Categories, Home, Storage, Trash. Deterministic tests with delayed fakes. |
| P2.7 folder-use race | **Fixed** | Hydration serialized behind a Mutex; `persistFolderUses` awaits hydration; `usesLoaded` set only after the read. Race test proves prior counts survive. |
| P2.8 quick-access remap | **Fixed** | `SettingsRepository.remapPaths()` remaps favorites, quick pins, hidden paths and folder-use counts (persisted + in-memory) atomically; rename/move wired to it. Tests for exact + nested paths. |
| P2.9 media scale | **Fixed** | ARCHIVES/APKS match by MIME + extension `LIKE` fallback; `summaries()` uses COUNT/SUM aggregates (true, uncapped totals). Truncation disclosure: drill-in/recent lists show a "first N" footer at the cap. |
| P2.10 cancellation | **Fixed** | Checkpoints per buffer/directory in FileOps copy/move/delete/size, ZIP extract/create loops, tree search and storage analysis; partial outputs cleaned on cancel. |
| P2.11 ZIP partial output | **Fixed** | Per-entry temp files promoted after CRC-close; failed creation aborts and deletes the archive; failed/cancelled extraction leaves the destination untouched. Tests: truncated stream, IO failure, cancel. |
| P2.12 provider scope | **Fixed** | `UriGuard` path denylist (/data, /proc, /sys, /dev, /system, /vendor, /apex, /product) enforced in `Intents.contentUri` before a URI exists; root-path catch-all kept for OEM removable volumes, comment documents the policy. Tests reject system paths. |
| P2.13 open-with contract | **Fixed** | Docstrings state the real platform contract (resolver appears only with 2+ handlers and no saved default); "Open with" is a one-time chooser that never writes a default; chooser carries the same read grants. |
| P2.14 installer outcomes | **Fixed** | New `SETTINGS_FAILED` distinct outcome when both settings handoffs fail; `openInstallPermissionSettings` Boolean honored; pending APK retained across the settings round-trip; BrowseScreen handles the new state. Tests with an always-throwing application. |
| P2.15 removable volumes | **Documented** | Pre-R hidden-getter limitation documented in `FileRepository`; device validation moved to `docs/RELEASE_CHECKLIST.md` §4 (cannot be verified statically). |
| P2.16 emulator CI | **Fixed** | `instrumented` job runs `connectedDebugAndroidTest` on API 29/33/36 emulators behind the build job; advisory (`continue-on-error`) until green per the checklist promotion policy. |
| P3.1 ZIP-slip names | **Fixed** | `isSafeEntryName` rejects only whole `..` components (plus absolute/`:`/NUL); `release..notes.txt` now extracts. Tests. |
| P3.2 thumbnail staleness | **Fixed** | Cache keys include file identity + mtime + size (`VideoThumbKeys`); composables re-key on the stamp. Tests. |
| P3.3 diagnostics no-op | **Fixed** | Switch relabelled "Diagnostics (planned)" with honest copy ("nothing is collected or transmitted today"). |

---

## 3. P1 — Fix before a reliability release

### P1.1 Recursive delete follows directory symlinks

**Files:** `app/src/main/java/com/toadfiles/app/domain/FileOps.kt` (especially `deleteRecursively`, around lines 245–250); callers include trash permanent deletion and retention purge.

The implementation tests `file.isDirectory` and then calls `listFiles()`. Both operations follow a symbolic link to a directory. If a user deletes or permanently purges a symlink to a directory, recursion can walk into and delete the linked directory's contents rather than unlinking only the symlink. A dangling symlink has the opposite problem: `exists()` is false, so the method returns success without unlinking it.

**Impact:** potential deletion of files outside the selected tree; incorrect success reporting for dangling links. This also affects `Trash.deleteForever`, `Trash.empty` and automatic purge.

**Fix:** use `java.nio.file.Files.isSymbolicLink(path)` / no-follow link checks before directory traversal. Delete the link itself without descending. Establish one explicit symlink policy and apply it consistently to list, copy, move, size, search, ZIP and trash. Add tests for a directory symlink to an external sibling, a symlink cycle and a dangling link. If symlinks cannot be created on a target Android filesystem, keep a host-JVM test for the domain code.

### P1.2 ZIP extraction limits can be bypassed when entry sizes are unknown

**File:** `app/src/main/java/com/toadfiles/app/domain/ZipSupport.kt`, extraction loop.

The total uncompressed byte counter is incremented only when `entry.size > 0`. The ratio guard runs only when `entry.compressedSize > 0`. ZIP entries whose sizes are unknown when read (for example, data-descriptor entries) therefore bypass both checks. The loop already tracks `written`, but does not add actual output bytes to the aggregate `totalBytes`.

**Impact:** an archive can write much more than `Limits.maxTotalBytes`, consuming storage and potentially exhausting the device. The current ZIP tests cover ordinary extraction, ZIP-slip, replacement and entry count, but not unknown-size entries, actual byte caps or ratio enforcement.

**Fix:** enforce per-entry and aggregate limits using bytes actually emitted, with overflow-safe `Long` arithmetic. Treat unknown compressed size conservatively; never disable the ratio policy because a header field is `-1`. Extract into a staging directory and publish only after the archive succeeds. Delete partial entry files on exceptions and cancellation. Add data-descriptor, oversized-stream, ratio-bomb and partial-cleanup tests.

### P1.3 REPLACE copy/move can destroy the previous destination on failure

**File:** `app/src/main/java/com/toadfiles/app/domain/FileOps.kt`, `copy()` and `move()`.

When the destination exists and the strategy is REPLACE, the code deletes the destination before copying or moving the replacement. Recursive copy writes directly into the final path. If a read/write error, full disk, permission failure or cancellation occurs, the previous good destination is already gone and a partial new tree may remain.

**Impact:** user data loss during an operation explicitly labelled Replace.

**Fix:** stage the replacement in a unique sibling path, verify the copy, move the old destination to a temporary backup, then swap the staged item into place. Restore the backup if the swap fails. Add fault-injection tests for a failure halfway through a file and halfway through a directory tree, and cancellation tests. Keep the original untouched until the replacement is complete.

### P1.4 Trash sidecar writes and deletion are not transactional

**File:** `app/src/main/java/com/toadfiles/app/domain/Trash.kt`.

Two unsafe orderings exist:
- `trash()` renames the original item into the hidden trash folder and only then writes its `.tfmeta` sidecar. If the sidecar write fails, the payload has moved but the operation omits it from the returned success list; the original path and restore metadata are lost.
- `deleteForever()` deletes the sidecar before deleting the payload. If payload deletion fails, the item remains in trash without its restore metadata.

**Impact:** items can become stranded in the hidden trash directory and cannot be restored normally. Browse's success/failure count can also be misleading because it checks whether the original source path still exists rather than whether metadata was committed.

**Fix:** write metadata to a temporary file and atomically finalize it; if metadata commit fails, move the payload back. For permanent deletion, remove the payload first and remove metadata only after payload deletion succeeds. Return a structured per-item result (moved, metadata failure, restore failure) rather than only a list of successful paths. Add failure-injection tests.

### P1.5 Generic MediaStore row URIs are malformed

**Files:** `app/src/main/java/com/toadfiles/app/data/MediaRepository.kt`, `ContentUrisHelper.uriFor()`; `app/src/test/java/com/toadfiles/app/ContentUrisHelperTest.kt`.

The generic branch constructs `content://media/external/files/media/$id`. Android's documented `MediaStore.Files.getContentUri(volumeName, rowId)` is built from the Files collection URI `content://media/{volume}/file` with the row ID appended (for external: `content://media/external/file/{id}`). The current unit test asserts the locally hard-coded URI rather than comparing it with the Android API, so it locks in the wrong value.

**Impact:** document/archive/APK entries that use the generic branch can fail to open, share or delete through their MediaStore URI. `deleteStale()` also deletes using the entry URI, so generic rows may not be removable.

**Fix:** use `MediaStore.Files.getContentUri("external", id)` for generic rows (and preserve the actual volume when available). Replace the URI string assertion with a comparison to the platform API and add provider-level open/delete tests for a document, archive and APK.

Platform reference: [Android MediaStore.Files API](https://developer.android.com/reference/android/provider/MediaStore.Files) and [AOSP MediaStore implementation](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/%2B/refs/heads/main/android-35/android/provider/MediaStore.java).

---

## 4. P2 — Important correctness, UX and platform issues

### P2.1 Recent and Home still surface stale MediaStore rows on main

**Files:** `MediaRepository.recent()`, `MediaRepository.list()`, `HomeViewModel.refreshRecent()`, `RecentViewModel.refresh()`.

The main-branch queries hide paths inside the toadFILES trash folder but do not filter records whose non-null backing `DATA` path no longer exists. MediaStore can retain rows after files are moved/deleted, so Home and Recent can display stale items until the media index reconciles.

**Fix/status:** filter missing paths when reading, then best-effort delete stale rows using a valid row URI. PR #1 includes a first-pass stale-row filter/cleanup on its branch; this finding describes the audited main baseline. Verify the Home feed and all category queries are covered by the final implementation.

### P2.2 A real file named with the `.tfmeta` suffix is invisible in Trash

**File:** `app/src/main/java/com/toadfiles/app/domain/Trash.kt`, `entries()`.

The listing skips every child whose name ends with `.tfmeta` to hide metadata sidecars. A user's actual file ending in `.tfmeta` is renamed into trash with a timestamp prefix but still ends in `.tfmeta`, so the UI mistakes the payload for a sidecar. It is hidden from restore and permanent-delete lists and is not purged through normal entry enumeration.

**Fix:** use a dedicated metadata subdirectory or an unambiguous sidecar naming/record format; do not identify sidecars by suffix alone. Add a trash/restore/purge test for files and directories ending in `.tfmeta`.

### P2.3 Multi-file sharing grants ClipData access only to the first URI

**File:** `app/src/main/java/com/toadfiles/app/util/Intents.kt`, `shareUris()`.

For `ACTION_SEND_MULTIPLE`, all URIs are placed in `EXTRA_STREAM`, but ClipData is created from only `uris.first()`. Receivers that rely on URI grants carried by ClipData may be able to read the first item but not the rest.

**Fix:** build ClipData with every URI (first item plus appended items) and retain `FLAG_GRANT_READ_URI_PERMISSION`. Extend tests to assert that all stream URIs are present in ClipData and verify reads from a receiving test activity.

### P2.4 Categories action sheet is wired but has no reachable trigger

**File:** `app/src/main/java/com/toadfiles/app/ui/categories/CategoriesScreen.kt`, `MediaRow()` and `MediaGridCell()`.

The drill-in list passes `onMore = { onEntryMore(e) }` to `MediaRow`, but `MediaRow` never renders a more/overflow button or invokes `onMore`. The image/video grid has no equivalent overflow callback at all. Therefore `actionSheetFor` and `MediaActionSheet` are unreachable from the Categories entries UI.

**Impact:** users cannot reach per-item Open with, details, copy path, show in folder and single-item share/delete actions from Categories. Some actions remain available through multi-select, but that is not equivalent.

**Fix:** render an overflow IconButton in the row and a discoverable item action affordance for visual grid cells; add Compose tests that open the action sheet for every category type.

### P2.5 Recent filter tabs disappear when the selected filter has no results

**File:** `app/src/main/java/com/toadfiles/app/ui/recent/RecentScreen.kt`.

The screen's top-level `when` renders an EmptyState whenever `state.entries.isEmpty()`. The filter chips are only inside the non-empty LazyColumn branch. If a user selects Images and has no images, all filter tabs disappear, leaving no way to switch to Videos/All without leaving the screen.

**Fix:** render the filter row independently of the entries state; show the empty state below it. Add a UI test that selects an empty category and switches back to All.

### P2.6 Asynchronous query results can overwrite newer screen state

**Files:** `RecentViewModel.refresh()`, `CategoriesViewModel.open()/refresh()`, `HomeViewModel.refreshRecent()/refreshFavorites()/rebuildQuickAccess()`, `StorageViewModel.refresh()`, and `TrashViewModel.refresh()`.

These methods launch independent IO jobs and apply results without a request-generation token or cancellation of the previous job. For example, quickly switching Recent from Images to Videos can let the slower Images query finish last and replace the Videos result while the selected filter still says Videos. Rapid category changes can similarly display the wrong category's entries. Rebuilds of Quick access/favorites can apply an older settings snapshot after a newer one.

**Fix:** cancel superseded jobs or attach a monotonically increasing request ID and only apply results for the latest request. For category drill-in, also check the requested category still equals `state.selected`. Add deterministic tests using controllable dispatchers and delayed fake repositories.

### P2.7 Most-used folder history can be overwritten before it loads

**Files:** `SettingsRepository.reloadFolderUses()/persistFolderUses()`; `MainActivity.onStop()`.

`reloadFolderUses()` sets `usesLoaded = true` before awaiting `store.data.first()`. `persistFolderUses()` does not ensure loading has completed and writes the current in-memory map as-is. If the activity stops during startup before the DataStore read completes, the initially empty map can overwrite the previous session's saved history. Concurrent first calls to `reloadFolderUses()` can also observe the flag before the first read finishes.

**Fix:** serialize initial hydration and updates with a Mutex/deferred state; do not persist until hydration completes. Add a test where persist races the initial read and prove previous counts survive.

### P2.8 Quick access paths are not remapped after rename/move

**Files:** `SettingsRepository.remapFavorites()`; `BrowseViewModel.remapFavorites()/remapMovedFavorites()`; Quick access keys `quickPins`, `quickHidden`, and `folder_uses`.

Rename/move only remaps favorites. Quick access pins, hidden paths and most-used folder-use keys are separate persisted path sets/maps and remain tied to the old path.

**Impact:** a renamed or moved folder can disappear from Quick access, reappear as a default, or lose its usage history.

**Fix:** implement one atomic subtree path-remap covering favorites, pins, hidden paths and usage counts; test exact paths and nested descendants for both rename and move.

### P2.9 Media category results are incomplete or misleading at scale

**File:** `app/src/main/java/com/toadfiles/app/data/MediaRepository.kt`.

- `ARCHIVES` queries only four MIME types (ZIP, ZIP-compressed, 7z, RAR), while Browse's archive filter also recognises TAR, GZ, BZ2 and XZ. These formats can appear in Browse but be absent from Categories/Recent archive tabs.
- `summaries()` counts only the first 2,000 rows per category, but presents that capped number as the category total.
- Category drill-in defaults to 1,000 rows and Recent uses a fixed 400-row cap without paging or a "more results" indication.

**Fix:** query archive MIME types comprehensively and use extension fallback where MediaStore MIME metadata is inconsistent. Use count/aggregate queries for totals and paging (or visibly disclose truncation) for drill-in/recent lists.

### P2.10 Cancellation is not cooperative inside large filesystem operations

**Files:** `FileOps.copyRecursively()/deleteRecursively()/sizeOf()`, `ZipSupport.addToZip()` and the inner ZIP extraction byte loop, `BrowseViewModel.searchTree()`, `StorageViewModel.analyzeTopLevel()`.

Cancellation is checked between top-level sources/entries, not inside long recursive traversals or a single large file's read/write loop. A user can press Cancel while a huge directory/file is being copied, deleted, sized, searched or zipped and see the UI dismiss the progress overlay while the blocking operation continues.

**Fix:** thread a cancellation/checkpoint callback through recursive/streaming helpers, check coroutine activity per buffer and directory, and clean up staging output on cancellation. Test a deliberately slow stream/tree.

### P2.11 ZIP extraction can leave partial or corrupt output

**File:** `app/src/main/java/com/toadfiles/app/domain/ZipSupport.kt`.

If a stream fails or the expansion-ratio guard throws after an output file has been opened, the partially written file is left in the destination. Extraction continues to later entries and returns a result containing both failures and successful files. ZIP creation similarly catches per-source failures inside the same ZipOutputStream; an exception while an entry is open can leave the archive structure unusable.

**Fix:** write each entry to a temporary file and atomically rename only after its stream closes successfully. On a failed ZIP source, close/abort the archive safely or fail the entire archive and remove it. Test truncated input, IO failure and cancellation.

### P2.12 FileProvider exposes a broad root mapping

**File:** `app/src/main/res/xml/file_paths.xml`.

The provider includes `<root-path name="device_root" path="."/>`, which allows FileProvider URI generation for any path reachable from the filesystem root, not just shared-storage volumes. The provider is non-exported and grants are explicit, which reduces exposure, but the mapping is broader than needed for ordinary files.

**Fix:** use narrowly scoped, volume-aware roots for primary and removable shared storage. If a root mapping is required for OEM removable volumes, add explicit path validation in the URI helper and tests that reject system/internal paths such as `/data` and `/proc`.

### P2.13 File opening does not guarantee the promised “Just once / Always” chooser

**File:** `app/src/main/java/com/toadfiles/app/util/Intents.kt`.

Direct `ACTION_VIEW` can bypass the resolver when only one handler exists or a default is already selected. `openWith()` wraps the intent in `Intent.createChooser()`, which forces a one-time chooser and does not provide the same persistent default-selection UI. The comments and README overstate what the native resolver guarantees.

**Fix:** implement a deliberate app-picker flow with supported per-MIME/file-type persistence, or accurately describe the platform-dependent resolver behavior. Test zero, one and multiple handlers and an already-saved default on API 26, 30, 33 and 36.

### P2.14 APK install failures can be misreported as missing permission

**File:** `app/src/main/java/com/toadfiles/app/util/Intents.kt`, `installApk()`.

If the installer intent throws after `canRequestPackageInstalls()` returned true, the catch block opens unknown-app settings and returns `NEEDS_PERMISSION`. It also ignores the Boolean result from `openInstallPermissionSettings()`, so callers can tell the user settings opened even if both settings intents failed.

**Fix/status:** PR #1 contains a correction on its branch to report post-permission launch exceptions as FAILED and retain the pending APK selection across the settings handoff. Verify the settings-open failure path and retry lifecycle on devices/OEMs before merge.

### P2.15 Removable-volume discovery on pre-Android 11 depends on a hidden API

**File:** `app/src/main/java/com/toadfiles/app/data/FileRepository.kt`, `volumeDir()`.

For API levels below 30, the implementation reflectively calls `StorageVolume.getPath()`, a hidden method. Hidden-API enforcement/OEM differences can make this fail, after which removable volumes are silently omitted and the code may fall back to primary storage only.

**Fix:** validate API 26–29 removable-volume support on representative devices. Prefer supported APIs/SAF for removable volumes where possible; surface an explicit unsupported/unavailable state rather than silently implying all volumes were found.

### P2.16 Instrumented Android tests are not run by CI

**Files:** `.github/workflows/android.yml`; `app/src/androidTest/**`.

The repository contains 6 instrumentation tests, but the Android workflow runs lint, `:app:testDebugUnitTest`, and debug/release assembly only. It does not start an emulator or run `:app:connectedDebugAndroidTest`.

**Impact:** actual Android provider/permission/intent behavior is not covered by the green CI gate. This matters for MediaStore URIs, FileProvider grants, package installation, removable volumes and Android-version differences.

**Fix:** add an emulator matrix (at minimum API 29, 30/33 and 36) for the critical instrumentation suite. Keep device/OEM checks as a documented manual release checklist.

**Status (2026-10-30):** implemented on `pr1/e-ci` — `android.yml` gained an `instrumented` job running `connectedDebugAndroidTest` on emulators for API 29, 33 and 36, gated behind the lint/unit-test build job. Device/OEM-specific storage behavior remains a manual release checklist.

---

## 5. P3 — Lower-priority defects and product polish

### P3.1 ZIP-slip name validation rejects harmless filenames containing two dots

**File:** `PathSafety.isSafeEntryName()`.

The guard rejects any name containing the substring `..`, not only a path component equal to `..`. A legitimate filename such as `release..notes.txt` is skipped. Validate path components after normalisation instead.

### P3.2 Video thumbnail cache does not invalidate changed files

**File:** `app/src/main/java/com/toadfiles/app/util/VideoThumbs.kt`.

The cache key is only the file path. Replacing a video in place keeps the old cached frame until LRU eviction. Include file modification time/size in the cache key or invalidate after file operations.

**Status (2026-10-30):** implemented on `pr1/e-ci` — `VideoThumbs` now keys entries by path + mtime/size (`VideoThumbKeys`), and the `VideoThumb` composable re-keys its state on that stamp, so replaced videos re-decode instead of serving stale frames. Key math is covered by `VideoThumbKeysTest`.

### P3.3 Diagnostics preference is a no-op

**Files:** `SettingsRepository`, `SettingsScreen`, `AboutScreen`.

The UI correctly states that no diagnostic data is currently collected or transmitted, but the switch only stores a preference and has no diagnostic behavior. Consider labelling it as a future preference or removing it until a real local diagnostics feature exists.

---

## 6. Test coverage gaps mapped to findings

Existing tests cover normal file copy/move/delete, conflict strategies, path traversal, basic ZIP round-trips, trash restore/purge, DataStore persistence, navigation, onboarding and UI screenshots. The gaps that matter most:

| Area | Missing regression coverage |
|---|---|
| Recursive filesystem operations | Directory symlink, dangling symlink, symlink cycle, deep nesting, partial copy rollback, cancellation mid-stream |
| REPLACE semantics | Injected write/read failure proving old destination is preserved |
| ZIP | Unknown-size/data-descriptor entry, actual byte cap, ratio cap, truncated stream, partial-file cleanup, output archive failure |
| Trash | Metadata write failure, payload delete failure, `.tfmeta` payload name, sidecar orphan cleanup |
| MediaStore | Platform-generated generic Files row URI, query/paging behavior, stale-row cleanup, category MIME coverage |
| Intents | Every URI in ClipData, app-picker behavior with zero/one/multiple handlers, saved defaults, installer settings fallback failure |
| ViewModels | Out-of-order responses for filters/category changes/refresh; folder-use hydrate/persist race |
| Android compatibility | API 26/28 removable storage, API 29 scoped-storage behavior, API 30/33/36 media permissions and provider grants |

The existing `ContentUrisHelperTest` currently validates the hard-coded generic URI instead of validating against `MediaStore.Files.getContentUri`; update this test before treating the MediaStore path as covered.

---

## 7. Existing PR #1 changes vs this audit

PR #1 is a separate feature branch based on the audited main revision. It currently contains:
- Recent missing-path filtering and best-effort stale-row cleanup.
- APK install handoff state retention and corrected failure classification.
- A guard against extracting into an existing destination folder.
- This audit report.

These changes are **not in main** until reviewed and merged. The report findings describe the audited main baseline; verify the PR's implementation and tests before treating any finding as resolved. In particular, the stale-row fix needs to cover Home and category paths as well as the standalone Recent flow.

## 8. Recommended fix order

1. **Protect user data:** symlink-safe delete/traversal, transactional REPLACE, failure-safe trash metadata.
2. **Harden archive handling:** actual-byte limits, staged extraction, partial-output cleanup and cancellation.
3. **Repair platform integration:** generic MediaStore URIs, all-URI share grants, installer error paths and Android 26–36 storage/volume tests.
4. **Fix state and UI correctness:** Recent empty-filter tabs, Categories overflow actions, request-generation guards, folder-use hydration and Quick access remapping.
5. **Correct counts and coverage:** archive MIME coverage, paging/true counts, emulator CI, regression tests.

## 9. Proposed solution and implementation plan

The safest approach is to treat filesystem integrity as the first milestone, then correct Android provider/intent behavior, and finally close UI/state/scale gaps. Avoid broad refactors until the destructive-operation invariants below are covered by tests.

### 9.1 P1 remediation designs

| Finding | Proposed implementation | Required acceptance checks |
|---|---|---|
| Symlink traversal during delete | Centralize path classification with NIO no-follow checks. If the selected node is a symlink, unlink the link itself; recurse only into confirmed real directories. Make the same no-follow policy explicit in size/copy/ZIP/trash helpers. | External target contents survive deletion; dangling links are removed; cycles terminate; ordinary nested directory deletion still works. |
| ZIP limits bypassed by unknown sizes | Count every emitted byte from the extraction buffer, per entry and across the archive. Enforce limits regardless of ZIP header sizes; use overflow-safe arithmetic. Extract into a fresh staging directory and publish only after complete validation. | Data-descriptor/unknown-size entries hit byte and ratio limits; oversized archives leave no published partial tree; cancellation cleans staging. |
| Destructive REPLACE | Copy to a unique sibling staging path; verify completion; move the old destination to a backup; atomically promote staging where supported; restore backup on promotion failure. Keep same-volume operations atomic where possible and document fallback semantics. | Injected read/write/full-disk/cancellation failures preserve the old destination byte-for-byte; successful replace leaves exactly one complete new destination. |
| Trash sidecar ordering | Use a transaction-like sequence: prepare metadata temp file, move payload, atomically commit metadata; on commit failure move payload back. For permanent deletion, delete payload first and metadata only after success. Return structured per-item outcomes. | Metadata-write failure restores original path; payload-delete failure retains metadata; retry is safe and idempotent. |
| Generic MediaStore URI | Generate generic row URIs through `MediaStore.Files.getContentUri(volumeName, id)`; carry the actual volume name rather than hard-coding external where available. Correct the unit test to compare with the platform API. | Instrumented tests can open, share and delete representative document/archive/APK rows on supported API levels. |

### 9.2 P2/P3 implementation proposals

| Area | Proposed implementation |
|---|---|
| Stale MediaStore rows | Put existence validation and best-effort stale-row cleanup in the shared repository query path so Home, Recent and category queries use the same behavior. Do not perform filesystem/provider work on the main thread. |
| Trash sidecar naming | Move metadata into a dedicated metadata directory keyed by a stable item ID, or use an explicit record format. Never infer that a payload is metadata from its filename suffix. Add migration handling for existing sidecars. |
| Multi-file sharing | Build ClipData containing every URI and preserve read-grant flags on the chooser and target intent. Verify each URI from a receiving test activity. |
| Categories actions | Add a visible overflow affordance to list rows and grid cells, wire it to the same action sheet, and cover each category presentation with Compose tests. |
| Recent empty state | Keep filter controls rendered independently of result content; show empty/loading/error states beneath the persistent filter row. |
| Stale asynchronous results | Track and cancel the active query job per screen, or use a monotonically increasing request token. Apply results only when the token and selected category/filter still match. |
| Folder-use persistence | Serialize initial DataStore hydration and writes with a Mutex or shared deferred initialization. Do not persist the in-memory map until hydration has completed. |
| Quick access path remapping | Implement one subtree-aware remap transaction covering favorites, pins, hidden paths and usage counters for rename and move. |
| Media category completeness and scale | Expand archive MIME/extension coverage; separate true aggregate counts from capped previews; add Paging or explicit truncation affordances to long lists. |
| Cooperative cancellation | Thread cancellation checkpoints through recursive directory walks and every streaming buffer loop. Ensure cancellation closes streams and removes temporary outputs before returning. |
| ZIP partial outputs | Write each extracted entry to a temporary sibling and rename only after successful close. If ZIP creation fails while an entry is open, abort and delete the incomplete archive rather than returning it as valid. |
| FileProvider scope | Replace the root-wide mapping with the narrowest supported shared-storage roots. Where OEM removable volumes require a broad mapping, enforce canonical-path allowlists before generating a URI. |
| Open-with semantics | Choose and document one supported contract: platform resolver behavior, or an in-app picker with app/file-type preference persistence. Do not promise a persistent “Always” option from a forced chooser. |
| APK installer fallback | Treat permission-state, settings-launch and installer-launch failures as distinct outcomes. Check whether settings actually opened and retain pending selection only while a permission retry is valid. |
| Removable volumes | Prefer supported APIs/SAF; isolate any legacy reflection behind a compatibility adapter and report unavailable volumes explicitly. Test API 26–29 and representative OEM devices. |
| Instrumentation CI | Add emulator-backed `connectedDebugAndroidTest` jobs for representative platform versions (at least API 29, 33 and 36); keep device/OEM-only checks in a release checklist. |
| ZIP filename validation | Normalize entry paths and reject only absolute paths, drive prefixes and actual parent-traversal components; allow harmless names containing `..`. |
| Video thumbnail cache | Include stable file identity plus size/modified time in the cache key, or invalidate affected paths after replace/move/delete. |
| Diagnostics preference | Remove the inert switch or label it explicitly as reserved until a real local diagnostics capability exists. |

### 9.3 Delivery sequence

1. **Data safety patch:** symlink-safe traversal, transactional REPLACE, trash metadata ordering, and regression/fault-injection tests.
2. **Archive hardening patch:** actual-byte quotas, staging extraction, partial-output cleanup, safe ZIP creation failure handling and cancellation.
3. **Android integration patch:** MediaStore URI correction, complete ClipData grants, FileProvider scope, installer outcomes and removable-volume compatibility tests.
4. **State and UI patch:** request cancellation/tokens, DataStore hydration serialization, Quick access remapping, persistent Recent filters and reachable Categories actions.
5. **Scale and release confidence:** MIME coverage, paging/count correctness, emulator instrumentation CI, then a device/OEM release checklist.

Keep each milestone independently reviewable. Do not mark a P1 resolved based only on a code change: require its regression test and, for provider/permission behavior, an instrumented or device-level check. The existing PR's current fixes should be revalidated against these acceptance checks; this proposal does not claim the remaining findings are implemented.

---

## 10. Audit limitations


This is a repository-wide **static audit**, not formal verification or a complete runtime QA sign-off. A source-level finding can be confirmed from code, but device-specific behavior (especially storage permission enforcement, removable volumes, MediaStore grants and package installer behavior) still needs emulator/device reproduction. The proposals above are a remediation plan, not implemented fixes. Only the separately listed PR changes are present on this branch.


---

## Part II — Current post-merge re-audit and API 35/36 feature/lightweight review (v1.6.1)

# Post-merge re-audit — toadFILES v1.6.1

**Audit date:** 2026-10-30  
**Audited branch:** `main`  
**Audited commit:** `f63a2ea4b7fb9accb43a9e8f14dc039709554c72` — merge of PR #1 / v1.6.1  
**Previous baseline:** `6858540c531b0219ec81cdb347f81623fa5a8286` — v1.6.0  
**Review type:** post-remediation static security, correctness, reliability, test and CI re-audit  
**Status:** findings are source-based; no device-level QA sign-off is implied.

## 1. Scope and verification

The complete tracked tree was enumerated (157 files; GitHub reported `truncated=false`). It contains:

- 39 production Kotlin files.
- 36 JVM/Robolectric test files.
- 2 Android instrumentation test files.
- Android manifest/resources, Gradle configuration, two GitHub Actions workflows, changelog, README and 12 documentation files.

The re-review concentrated on every remediation stream from PR #1: recursive filesystem operations, trash metadata/migration, ZIP creation/extraction, FileProvider/URI security, MediaStore queries, ViewModel concurrency, settings persistence, removable-volume discovery, tests and CI.

### CI evidence at the audited commit

- [Android CI run for main at `f63a2ea`](https://github.com/dukeoftoad/toadFILES/actions/runs/41021525107): **failed**. Debug and release lint passed; JVM tests completed with **279 tests, 1 failure**. The failure was `TrashViewModelTest > refresh lists trashed items newest first`, reported at `TrashViewModelTest.kt:61` inside `seedTrash()`.
- The instrumented job was **skipped** because it depends on the failed build job. It did not run on this commit.
- [v1.6.1 Release workflow](https://github.com/dukeoftoad/toadFILES/actions/runs/41021541134): **success**, including its own lint/unit-test gate and signed APK assembly.

The difference between the red main CI run and the successful release run means the unit-test failure may be order-dependent or flaky; it must be reproduced and fixed rather than ignored. The emulator job is configured with `continue-on-error: true`, so even a green emulator matrix is currently advisory, not a required merge/release gate.

No local Gradle run, emulator session or physical-device/OEM validation was performed during this re-audit. The CI evidence above is from GitHub Actions.

## 2. Previous audit findings — remediation status

| Previous finding | Re-audit status |
|---|---|
| Recursive delete followed directory symlinks | **Fixed in the reviewed delete path.** `Files.walkFileTree` uses the default no-follow behavior and top-level symlinks are explicitly unlinked. |
| ZIP extraction trusted unknown header sizes | **Substantially fixed.** The aggregate uncompressed-byte cap counts bytes actually written. The ratio calculation still has a read-ahead accounting defect (P2 below). |
| REPLACE copy/move deleted the old destination too early | **Improved for FileOps.** Copy stages before swapping and move keeps a backup. ZIP extraction's publish phase still has a non-transactional replace path (P2 below). |
| Trash sidecar ordering could strand payloads | **Improved for ordinary failures, not crash-safe yet.** A process-death window leaves a pending sidecar that normal restore cannot read (P1 below); pending cleanup also races active trash operations. |
| Generic MediaStore row URI was malformed | **Fixed.** Generic rows use `ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)`. |
| Multi-file share grants covered only first URI | **Fixed in source.** ClipData now includes all URIs on the send and chooser intents. |
| Categories actions and empty Recent filters were unreachable | **Fixed in source.** Categories has an overflow action and Recent keeps its filter controls visible. |
| Home/Recent/Categories stale query results | **Improved.** Request tokens/jobs were added there; Browse still has a same-directory stale-load race. |
| Folder-use hydration race | **Hydration is fixed.** Persistence and path remapping still do not share one lock, leaving a narrower write-order race. |
| Quick Access paths were not remapped | **Fixed in source.** Favorites, pins, hidden paths and folder-use keys are remapped. |
| Archive MIME coverage and capped category counts | **Partially fixed.** Archive extension fallbacks and aggregate counts exist, but aggregate counts include stale/trash rows excluded from visible listings. |
| ZIP partial output/cancellation | **Partially fixed.** Extraction stages entries and ZIP creation stages the archive; publication and FileOps traversal still lack complete rollback/cancellation behavior. |
| FileProvider root mapping was broad | **Not fully fixed.** The catch-all root remains and the new denylist is lexical, not canonical; symlink paths can bypass it (P1 below). |
| Instrumentation tests absent from CI | **Partially fixed.** API 29/33/36 jobs were added, but they are advisory and were skipped on the audited main run. |
| Pre-Android 11 removable-volume reflection | **Still an acknowledged limitation.** API 26–29 relies on hidden `StorageVolume.getPath()`; device validation remains on the release checklist. |
| ZIP filename false-positive rejection | **Fixed in source.** Validation rejects parent-traversal components rather than every occurrence of `..`. |
| Video thumbnail cache staleness | **Fixed in source.** Cache keys include file identity/mtime/size and have unit coverage. |
| Diagnostics toggle no-op | **UI wording improved** to indicate the feature is planned; it remains non-functional by design. |

## 3. New and remaining findings

### P1 — Fix before the next public release

#### P1.1 FileProvider denylist can be bypassed with symlinks

**Files:** `app/src/main/java/com/toadfiles/app/util/UriGuard.kt:25-30`; `app/src/main/java/com/toadfiles/app/util/Intents.kt:67-73`; `app/src/main/res/xml/file_paths.xml`.

`UriGuard.isShareablePath()` checks the caller-provided string against a denylist of raw prefixes. `Intents.contentUri()` passes `file.absolutePath`, which is a lexical path and does not resolve symlinks. The FileProvider configuration still includes `<root-path path="."/>`, which covers the filesystem root.

A path under shared storage that is a symlink to a denied target (for example, a link under `/storage/emulated/0` pointing into `/data`) passes the lexical check. FileProvider canonicalizes paths when resolving configured roots, so the canonical target can then be covered by the root-path mapping and handed to the receiving app. Dot-segment aliases are another reason a raw prefix check is insufficient.

**Impact:** the new guard does not reliably enforce its stated security boundary; a crafted link can cause a private/system target to be shared externally.

**Fix:** canonicalize/resolve the target before policy evaluation and use a positive allowlist of canonical mounted shared-storage roots rather than a denylist of a few system prefixes. Explicitly reject app-private/system paths and restricted `Android/data` / `Android/obb` trees. Keep the provider mapping as narrow as removable-volume support permits.

**Required tests:** a symlink under shared storage pointing to a denied target; dot-segment paths; removable SD/OTG paths; valid primary-storage paths. Test the actual URI generation and that a receiving app cannot open a denied target.

#### P1.2 ZIP creation follows directory symlinks

**File:** `app/src/main/java/com/toadfiles/app/domain/ZipSupport.kt:385-400`.

`addToZip()` decides whether to recurse using `file.isDirectory` and then calls `listFiles()`. Both follow a symbolic link to a directory. Unlike `FileOps.copyRecursively()`, this ZIP traversal does not call `Files.isSymbolicLink()` or keep a visited-path set.

If a selected folder contains a symlink to another folder, the archive can include files outside the selected tree. A symlink cycle can recurse until stack exhaustion because this helper is recursive and has no cycle guard.

**Impact:** unintended inclusion of external/private files in a user-created archive, plus a possible app crash on cyclic links.

**Fix:** apply an explicit no-follow symlink policy before checking `isDirectory`. Prefer skipping/rejecting directory symlinks with a reported per-item failure. If file symlinks are supported, define that separately and never recurse through directory links. Add canonical visited-node protection as defense in depth.

**Required tests:** symlink to an external directory, symlink cycle, dangling symlink, and ordinary nested directory ZIP round-trip. Assert that no target files outside the selected tree appear in the archive.

#### P1.3 Trash is not recoverable after a crash between payload move and metadata commit

**File:** `app/src/main/java/com/toadfiles/app/domain/Trash.kt:93-105, 151-190, 212-220`.

The transaction writes `.tfmeta.pending`, renames the payload into the trash directory, and then renames the pending sidecar to the committed `.tfmeta` name. If the process is killed after the payload rename but before the sidecar rename, the payload remains in trash with only a pending metadata file.

On the next listing, `entries()` can see the payload and `pruneOrphanMeta()` keeps the matching pending file, but `readMeta()` only reads the committed sidecar path. The UI therefore lists an item with an empty origin path, and `restore()` returns null because it cannot read the pending record.

**Impact:** a crash at a small but real transaction window strands the user's item in trash without a working restore path. This is not equivalent to a committed transaction.

**Fix:** make startup/list recovery recognize a valid pending record when its payload exists and atomically promote it to the committed sidecar before exposing the item. Validate the metadata schema and make recovery idempotent. Do not discard the only metadata copy until either rollback or commit has succeeded.

**Required tests:** inject process-death-equivalent state after each transaction step; especially payload moved + pending sidecar present. Verify the next `entries()` call recovers the original path and `restore()` succeeds.

### P2 — Correctness, reliability and release confidence

#### P2.1 Browse tree search follows symlink directories and has no cycle guard

**Files:** `app/src/main/java/com/toadfiles/app/ui/browse/BrowseViewModel.kt:220-251`; `app/src/main/java/com/toadfiles/app/data/FileRepository.kt:96-108`.

`FileRepository.list()` sets `isDir = f.isDirectory`, which follows directory symlinks. `BrowseViewModel.searchTree()` pushes every `isDir` child onto a stack and has no canonical visited set. If a browsed tree contains a symlink back to an ancestor and the search term does not match enough files to hit the result cap, the same directories can be revisited indefinitely.

The search traversal also has no cancellation checkpoint inside its directory/file loops.

**Impact:** unbounded IO/CPU and potentially memory exhaustion from a local symlink cycle; Cancel may not stop a long search promptly.

**Fix:** make traversal symlink-aware and track canonical visited directories; add `ensureActive()` checkpoints per directory and file. Keep the visible symlink policy explicit in Browse rather than letting `File.isDirectory` decide it implicitly.

#### P2.2 ZIP extraction's replace publication can partially destroy the destination

**File:** `app/src/main/java/com/toadfiles/app/domain/ZipSupport.kt:301-360`.

Extraction stages and validates entries before publication, which is a meaningful improvement. However, `publishStaging()` publishes files one by one. With `replace=true`, it deletes an existing destination directory or file before the staged replacement is safely installed. If a later move/create fails, or cancellation arrives during publication, earlier destination items have already changed and the operation leaves a partially updated tree. The publish loop is synchronous and has no cancellation checkpoint.

The normal Browse extraction path currently passes `replace=false` and refuses an existing destination folder, reducing the everyday exposure, but the `replace=true` path remains supported and is covered by tests.

**Fix:** publish into a second sibling tree and swap at directory level where possible, or keep per-item backups and roll back all changes on any failure. Add cancellation checks during publication and make the returned result describe any partial commit precisely.

#### P2.3 ZIP expansion-ratio accounting counts buffered read-ahead, not current-entry compressed bytes

**File:** `app/src/main/java/com/toadfiles/app/domain/ZipSupport.kt:105, 142-163`.

The code wraps an already-buffered stream in `CountingInputStream`:
`CountingInputStream(FileInputStream(zipFile).buffered(BUFFER_SIZE))`.
The counter therefore advances when the buffer prefetches bytes from the file, not when those bytes are consumed by the current ZIP entry. `compressedAtStart` is captured after `nextEntry`; for a small archive already sitting in the buffer, the subsequent count delta can be near zero even while entry compressed data is being consumed.

That makes the ratio denominator unreliable and can reject legitimate entries whose actual ratio is below the configured limit (the 64-byte overhead can effectively become the only denominator). The aggregate actual-output-byte cap still provides an independent hard ceiling, so this is primarily a ratio-policy correctness defect rather than evidence that the total-byte cap is bypassed.

**Fix:** measure compressed bytes at the ZIP-entry/Inflater boundary or use a counting arrangement that measures bytes consumed for the active entry, not bytes prefetched by an outer buffer. Keep the aggregate actual-output cap as the hard stop.

**Required tests:** small archive fully contained in one buffer, multiple entries in one buffer, a legitimate entry just below the configured ratio, and a high-ratio bomb above it.

#### P2.4 FileOps and Browse traversal are not cooperatively cancellable end-to-end

**Files:** `app/src/main/java/com/toadfiles/app/domain/FileOps.kt:326-391, 396-420`; `BrowseViewModel.kt:220-270`; `ZipSupport.kt:301-360`.

Cancellation is checked between top-level FileOps sources, but not inside `copyRecursively()`, `copyFileContents()` buffer loops or `deleteRecursively()`'s `walkFileTree`. Browse tree search similarly has no checkpoints. ZIP creation checks per file buffer, but directory recursion can traverse many directories without checking; ZIP publication has no checks.

**Impact:** the progress UI can disappear after Cancel while a large copy/delete/search/publish continues doing work. For destructive delete, the user may believe the operation stopped when it has not.

**Fix:** use suspend traversal helpers or pass a cancellation checkpoint into every recursive and streaming loop. Do not clear the progress UI until the operation has actually stopped or clearly report that cancellation is pending. Clean staging/partial outputs on cancellation.

#### P2.5 Category summary totals include rows hidden from the visible list

**Files:** `app/src/main/java/com/toadfiles/app/data/MediaRepository.kt:110-120, 204-229`; `app/src/test/java/com/toadfiles/app/MediaRepositoryTrashPathTest.kt:253+`.

`list()` and `recent()` hide trashed paths and rows whose backing DATA path no longer exists. `totalsFor()` aggregates the raw MediaStore selection only; it does not apply either visibility rule. The regression test explicitly expects a summary count that includes a ghost row even though `list()` hides that row.

**Impact:** category cards can display counts/bytes that do not match the entries users can open. Trashed files and stale index rows can continue inflating totals indefinitely if users only view the category grid.

**Fix:** define whether card totals mean visible files or raw MediaStore index rows. If they mean visible files, reconcile/exclude trash and stale paths before displaying counts; if raw index totals are intentional, label them as indexed totals and avoid implying they equal visible entries. Add tests for trash and vanished rows in every category.

#### P2.6 Trash orphan cleanup can delete an in-flight pending sidecar

**File:** `app/src/main/java/com/toadfiles/app/domain/Trash.kt:93-105, 151-190, 253-264`.

`trash()` creates the pending sidecar before moving the payload. Concurrently, `entries()` calls `pruneOrphanMeta()`, which deletes any pending/sidecar file whose corresponding payload is not yet present in the trash directory. If listing/purge runs between the pending write and the payload rename, it can delete the active transaction's pending file. The subsequent pending-to-sidecar rename fails and the operation rolls the payload back.

This is a real race between two public Trash operations. It is also a plausible contributor to the current `TrashViewModelTest` failure, but the CI log does not prove that is the specific root cause.

**Fix:** serialize trash mutations and orphan cleanup with one repository-level lock, or only prune pending files older than a conservative recovery window after verifying no transaction is active. Add a deterministic barrier test that pauses `trash()` between pending write and payload rename while `entries()` runs.

#### P2.7 Legacy sidecar migration can misclassify a real user file

**File:** `app/src/main/java/com/toadfiles/app/domain/Trash.kt:230-249`.

`migrateLegacySidecar()` treats any legacy-folder file ending in `.tfmeta` as metadata if a sibling file with the suffix removed exists. It does not validate that the candidate file actually contains the expected `origin=...` and `trashedAt=...` metadata schema before moving it into `.meta`.

A user's real payload named `<name>.tfmeta` can therefore be moved out of the payload list if a sibling named `<name>` also exists.

**Fix:** validate the complete legacy metadata format and timestamp before migration. If validation fails, preserve the file as a payload. Add a collision test with both a real `.tfmeta` payload and a sibling basename file.

#### P2.8 Browse loads can still apply stale results for the same directory

**File:** `app/src/main/java/com/toadfiles/app/ui/browse/BrowseViewModel.kt:127-158`.

`load(dir)` launches a new IO job for every refresh and only checks that the current directory path still equals `dir.path` before applying the result. Two loads for the same path can overlap (for example, a sort/hidden-setting change or rapid refresh). A slower older result can therefore overwrite the newer listing because the directory equality check still passes. There is no per-load sequence token or stored load job in this path.

**Fix:** cancel the prior load job and/or attach a monotonically increasing request token; apply only the latest result for the current directory and settings snapshot. Add a deterministic same-directory out-of-order test.

#### P2.9 Folder-use persistence can race path remapping

**File:** `app/src/main/java/com/toadfiles/app/data/SettingsRepository.kt:192-200, 236-270`.

Hydration is now protected by `usesMutex`, but `persistFolderUses()` does not hold that mutex while writing the current in-memory map. `remapSavedPaths()` first rewrites the persisted DataStore set and only afterwards takes the mutex to rewrite the live map. A concurrent persist can write the old in-memory paths after the DataStore remap, undoing the path rewrite on disk until another remap/session update occurs.

**Fix:** serialize record, remap and persist operations under one mutex or make all three operate on a single immutable snapshot/transaction. Add a deterministic test interleaving remap and persist.

#### P2.10 Cross-volume move can report success even when source deletion fails

**File:** `app/src/main/java/com/toadfiles/app/domain/FileOps.kt:168-205`.

After a cross-volume copy is published, `move()` calls `deleteRecursively(src)` but ignores its Boolean result and increments `ok` anyway. The REPLACE cross-volume path does the same. If source deletion fails, both source and destination remain while the operation is reported as a successful move.

**Fix:** count the operation as a partial failure when source cleanup fails, preserve the destination (it is already a valid copy), and tell the user that the original remains. Add a deletion-failure injection test for both ordinary and REPLACE moves.

#### P2.11 Main CI is red and emulator coverage did not execute

**Evidence:** [Android CI run 41021525107](https://github.com/dukeoftoad/toadFILES/actions/runs/41021525107).

The audited main commit has a failing JVM test (`TrashViewModelTest` at line 61). Since the instrumented job has `needs: build`, it is skipped when the build job fails. The emulator job is also `continue-on-error: true`, so it is not a blocking signal while advisory.

**Fix:** reproduce and stabilize the failing Trash test; isolate each test's trash root instead of sharing the app's external-storage root, and clean it in teardown. Then run the API 29/33/36 matrix and promote it to blocking only after the documented stability criterion is met. Do not treat the successful signed release workflow as proof that the main Android CI run is green.

## 4. Recommended next implementation order

1. **Security boundary:** canonical FileProvider allowlisting and ZIP symlink policy.
2. **Trash recovery:** recover valid pending metadata after process death; protect active pending records from orphan cleanup; validate legacy sidecars.
3. **Traversal safety:** no-follow + visited-set Browse search; cancellation checkpoints across FileOps and ZIP publication.
4. **Atomic publication:** make ZIP replace all-or-rollback; report cross-volume move source-delete failures.
5. **State/data consistency:** visible category totals, Browse request tokens, serialize folder-use remap/persist.
6. **Release confidence:** fix the failing Trash test, make emulator instrumentation run and pass, then promote the matrix from advisory to required.

## 5. Release checklist additions

- [ ] Share/open a symlink under shared storage that points to a denied canonical target; URI creation must reject it.
- [ ] ZIP a folder containing a directory symlink and a symlink cycle; archive must not include external target contents or hang/crash.
- [ ] Kill the app after payload rename but before pending-sidecar commit; next Trash listing must recover and restore the item.
- [ ] Run Trash listing/purge concurrently with a trash operation paused after pending metadata creation.
- [ ] Search a directory tree containing a symlink to an ancestor; search must terminate and Cancel must stop it.
- [ ] Inject a failure during ZIP replace publication; pre-existing destination data must remain recoverable.
- [ ] Cancel a large recursive copy/delete/search; verify no work continues after cancellation is reported.
- [ ] Compare category summary counts with visible entries after stale rows and trashed rows exist.
- [ ] Force source deletion failure after cross-volume move; UI must not report a complete move.
- [ ] Confirm Android CI and all API 29/33/36 instrumentation jobs are green on main.

## 6. Limitations

This report is a focused post-merge static re-audit plus inspection of the latest GitHub Actions results. It is not formal verification, a full device compatibility matrix, or a substitute for runtime reproduction. Findings marked P1/P2 are based on source control flow and should be converted into regression tests before being marked resolved. The audit branch contains documentation only; no production code was changed by this re-audit.


## 7. Product scope, feature gaps and lightweight review

**Platform baseline requested for this review:** target API 36 (Android 16), minimum Android 15 (API 35). **Repository mismatch:** app/build.gradle.kts currently has targetSdk = 36 but minSdk = 26 (Android 8.0); the README likewise advertises Android 8.0+. This report therefore distinguishes the requested API 35 minimum from the actual shipped configuration. Do not claim Android 15 is the enforced minimum until minSdk and the compatibility code are deliberately changed and tested.

### 7.1 High-value gaps (not automatically release blockers)

| Gap | Why it matters | Lean implementation direction |
|---|---|---|
| Reliable Android 15/16 storage and removable-volume validation | API 35/36 behavior, permission flows, USB/SD mount/unmount and provider URI behavior are not proven by JVM tests. | Add focused API 35/36 instrumentation for permission return, volume discovery, hot-unplug, rename/move and share/open. Avoid adding a broad abstraction until device tests expose a real OEM difference. |
| SAF/document-provider support (optional) | Current product is built around direct filesystem paths and all-files access; cloud/document providers and apps exposing only content:// trees are not equivalent to mounted folders. | Decide explicitly whether this app is a local mounted-storage manager only. If broader provider support is wanted, add a narrow SAF adapter for user-granted trees rather than replacing the working filesystem layer wholesale. |
| Operation recovery and transparent partial results | Existing P1/P2 findings show crash recovery, cancellation and cross-volume move reporting need stronger guarantees. | Prioritize recovery, truthful per-item results and undo/retry before adding more file actions. |
| Accessibility and large-screen validation | The README/screenshots establish the main screen set, but this audit did not establish TalkBack semantics, keyboard/focus behavior, tablet layouts or font-scale behavior. | Add Compose accessibility checks and a small set of API 35/36 UI tests at large font scale, landscape and tablet widths. Treat this as a validation gap, not a claim that every screen currently fails. |
| Batch copy/move consistency | README explicitly describes multi-select batch share/delete/ZIP, while batch copy/move is not listed as supported in that interaction summary. | Consider batch copy/move only if user demand warrants it; reuse the existing per-item FileOps result model and do not build a second operation engine. |

Intentional non-features should remain intentional: no network permission, cloud sync, ads, analytics, account system or automatic startup filesystem scan. Those absences support the app's privacy and fast-startup positioning; adding them would increase attack surface, dependencies and maintenance without strengthening the core file-manager job. An encrypted vault is already described as planned, not delivered; do not advertise it as an existing capability.

### 7.2 Existing features with overlapping value

These are product simplification opportunities, not defects by themselves. Confirm usage/feedback before removing them.

1. **Three overlapping Home discovery mechanisms — simplify.** Manual pinned Quick Access, Favorites and automatically ranked Most Used can all become competing ways to reach the same folders. Keep one compact Quick Access surface with user-pinned entries first and an optional small “frequent” subsection. Preserve file favorites if users rely on favoriting individual files; avoid duplicating folder shortcuts in multiple sections.
2. **Home storage hero vs. Storage screen — avoid duplicate dashboards.** Keep a concise free/used summary on Home and leave detailed per-volume/media/trash/biggest-folder analysis in Storage. Do not add a second detailed breakdown to Home.
3. **Categories vs. Browse filters vs. Recent — define distinct jobs.** Browse should remain the authoritative filesystem view; Recent is a time-ordered activity/discovery view; Categories is useful mainly for media thumbnail galleries. Avoid implementing separate file-operation logic or duplicate sorting/filter state in each. If Categories usage is low, make it a lightweight filtered view over shared repository/actions rather than a parallel media manager.
4. **Theme choice surface — keep one source of truth.** Dynamic color and seven hand-tuned accents are user-visible polish, but expand test/maintenance combinations. Retain system/dynamic color plus a small curated palette; remove individual palette options only if feedback shows they are unused. Do not create separate theme engines per screen.
5. **Search-inside-text — keep opt-in and bounded.** This is a niche, potentially expensive feature. Keep disabled by default, enforce strict file-size/encoding/time limits, and expose cancellation; do not run content indexing in the background.

### 7.3 Remove, defer or consolidate low-value implementation

| Candidate | Recommendation | Reason / guardrail |
|---|---|---|
| Diagnostics placeholder/toggle | Remove or hide until diagnostics actually work. | A non-functional control creates false expectations and dead UI state. Reintroduce only with a defined export, privacy review and tests. |
| android:requestLegacyExternalStorage="true" | Remove. | It is obsolete/ignored for modern target levels and misleading in a target-36 app. Verify legacy API behavior before removal if minSdk remains 26. |
| Legacy storage permission and API 26–29 compatibility paths | Conditional on adopting minSdk 35. Remove READ/WRITE_EXTERNAL_STORAGE declarations, pre-R permission flow and hidden StorageVolume.getPath() reflection only after minSdk is actually raised to 35 and old-device support is intentionally dropped. | Do not remove these while minSdk = 26; doing so would break the currently declared support range. Re-test SD/USB volumes on API 35/36. |
| Broad <root-path path="."> FileProvider mapping | Replace with the narrowest canonical roots that support the intended primary/removable volumes. | This is a security fix, not merely cleanup; coordinate with P1.1 and test every share/open flow. |
| Duplicate screen-specific file action implementations | Consolidate behind the existing shared action sheets/repository operations. | Avoid parallel implementations that drift in permission checks, trash behavior and result reporting. Do not add a new generic framework unless it removes demonstrable duplication. |
| Large extended icon dependency | Measure release APK and method/resource impact before changing. If meaningful, replace with a small curated icon set. | R8/resource shrinking may already remove unused code; removing the dependency without measuring could save little while increasing custom asset maintenance. |

### 7.4 Dependencies and weight — measured approach

- Keep Coil Compose while thumbnail grids exist; keep Coil video only while video-frame thumbnails are used. Do not replace a working image loader with custom bitmap/cache code merely to chase theoretical size savings.
- Keep DataStore for persisted settings and coroutines for IO/state. They support core app behavior and are not obvious dead weight.
- Keep Roborazzi, Robolectric and AndroidX test dependencies in test configurations; they do not belong in the release runtime. They provide value through visual and behavior regression coverage.
- material-icons-extended is a plausible APK/build-weight optimization candidate, but quantify the minified release APK and dependency graph first. Do not call it a proven bloat issue from source inspection alone.
- The app's no-network design is a concrete lightweight/security advantage. Avoid adding analytics, crash-upload SDKs, remote config, cloud backup or ad SDKs unless the product direction changes explicitly.

### 7.5 API 35 minimum decision and migration checklist

If Android 15 is the intended minimum, make the support contract real in a separate implementation change:

- [ ] Change minSdk from 26 to 35; update README/install documentation and release metadata consistently.
- [ ] Remove obsolete pre-Android-11 permission and volume-discovery branches only after confirming they are unreachable under the new minSdk.
- [ ] Remove requestLegacyExternalStorage and re-check manifest permission declarations.
- [ ] Test fresh install, upgrade, denied/revoked all-files access, permission return, primary storage, SD/USB volumes, share/open/install intents, trash and restore on API 35 and 36.
- [ ] Update CI emulator matrix to focus on API 35 and 36 (retain older API jobs only if they serve a deliberate compatibility target); make the required device checks blocking after stable runs.
- [ ] Publish the loss of Android 8–14 compatibility prominently; do not silently strand existing users.

**Recommendation:** do not add more headline features before closing the P1 security/data-loss risks and getting main CI green. For a lean release, retain the core local file workflow, media thumbnails, Recent, Trash and essential storage overview; consolidate overlapping Home shortcuts, remove the non-working diagnostics control, and defer vault/SAF/cloud-like expansions until there is a clear product requirement. This section is a source-informed product review; it is not evidence of measured user adoption or APK-size profiling.


---

## Part III — Manual device/OEM release checklist

# Release checklist (manual, device/OEM)

CI (lint, JVM unit tests, emulator instrumentation) cannot reproduce every
device-specific behavior of a file manager. Work through this list before tagging a
release. Every item exists because the static audit flagged it as a real-world risk.

## 1. Package installation (highest risk)
- [ ] Install an APK from Browse with the "Install unknown apps" toggle OFF: the app must
      land on toadFILES' toggle screen; after enabling, tapping Install again must start
      the installer (the pending APK is retained — no re-pick).
- [ ] Repeat on at least one OEM ROM (Xiaomi/HyperOS, Samsung/One UI, Pixel) — several
      installers fail silently without the grant.
- [ ] Verify the new `SETTINGS_FAILED` outcome: on a device where the settings handoff is
      refused, the app must say "could not open settings" instead of pretending a retry
      is possible.

## 2. Open / share handoffs
- [ ] "Open" on a file with several handlers: system resolver offers Just once / Always.
      With exactly one handler and with a saved default: opens directly (no prompt) —
      this is platform behavior, not a bug.
- [ ] "Open with" always shows a one-time picker and never writes a default.
- [ ] Share 2+ files (Browse multi-select and Recent multi-select): the receiver must be
      able to read EVERY item, not only the first (ClipData now carries all URIs).
- [ ] Share/open a file on a removable SD card / OTG drive (FileProvider root-path catch-all
      + `UriGuard` denylist). Confirm files under system locations are never offered.

## 3. MediaStore integration
- [ ] Documents, archives and APKs open, share and delete correctly (generic Files row
      URIs are now `content://media/external/file/<id>`).
- [ ] After moving files to trash, Home / Recent / Categories drop the ghost rows instead
      of showing stale entries (repo-side reconciliation, capped at 50 rows per query).
- [ ] Archives tab lists .tar/.gz/.bz2/.xz files without MIME metadata; category card
      counts are true totals (COUNT/SUM aggregates), not capped at 2000.

## 4. Storage and volumes
- [ ] API 26–29 device: removable SD card appears in storage roots. If the hidden
      `StorageVolume.getPath()` getter fails on an OEM ROM, the volume is omitted and
      primary storage still works — record the device result here.
- [ ] API 30+: `StorageVolume.directory` path works; SD-card browsing, copy/move across
      volumes.

## 5. Destructive-operation spot checks (covered by unit tests, but verify on device)
- [ ] Delete a folder that contains a symlink to an external folder: the link is
      unlinked, the target's contents survive.
- [ ] Replace-conflict copy over a large folder: cancel halfway — the old destination
      must survive intact (staging/backup swap).
- [ ] Trash → restore after a metadata failure is not reproducible manually; instead
      verify normal trash → restore → permanent delete round-trips on both internal
      storage and SD card (sidecars live under `<trash>/.meta/` now; legacy sidecars
      migrate on first listing).
- [ ] Extract a large ZIP and cancel halfway: no partial files published, no leftover
      staging directories.

## 6. Emulator instrumentation gate
The CI workflow runs `connectedDebugAndroidTest` on API 29 / 33 / 36 emulators as an
**advisory** job (`continue-on-error: true`) until it has been green for two consecutive
releases. Promotion to blocking:
- [ ] Confirm all three API levels pass consistently on `main`.
- [ ] Remove `continue-on-error` from the `instrumented` job in `.github/workflows/android.yml`.
- [ ] Keep device/OEM-only checks (sections 1–5) manual — emulators cannot cover them.

## 7. Release mechanics
- [ ] Update `versionCode` / `versionName` in `app/build.gradle.kts`.
- [ ] Tag `vX.Y.Z` — `release.yml` builds and publishes the signed APK
      (`TOADFILES_KEYSTORE_FILE/_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD` secrets required).
- [ ] Confirm the README screenshots still match the shipped UI (Robolectric/Roborazzi
      capture: `./gradlew :app:testDebugUnitTest --tests '*TabScreenshotsTest*'`).


---

## Consolidated disposition

- **P1:** 3 security/data-recovery findings; address before next public release.
- **P2:** 11 correctness, reliability, state-consistency and CI findings; add regression tests.
- **Platform:** target API 36; API 35 minimum remains a decision, not the current configuration (minSdk 26).
- **Product:** preserve local-first/no-network behavior; consolidate overlapping Home shortcuts and reuse shared file-action paths.
- **Weight:** measure APK/dependency impact before removing libraries; test-only tooling is not release runtime weight.
- **Validation:** static review and CI evidence do not replace API 35/36 emulator and physical-device/OEM checks.
