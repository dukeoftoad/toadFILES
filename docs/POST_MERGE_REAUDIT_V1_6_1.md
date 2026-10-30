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
