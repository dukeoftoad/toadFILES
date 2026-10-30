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
