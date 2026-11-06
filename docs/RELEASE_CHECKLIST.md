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
- [ ] (v1.8.1: minSdk raised to API 34 — the old API 26–29 legacy-permission range is
      gone.) API 34+ device: removable SD card appears in storage roots. If the hidden
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

## 6. Instrumentation on a real device
CI does NOT run `connectedDebugAndroidTest` — hosted-runner emulators proved slow and
flaky, so the emulator matrix was removed. Instrumentation is a local, on-device step:
- [ ] On a physical device (or a locally booted emulator of your choice):
      `./gradlew :app:connectedDebugAndroidTest`.
- [ ] Keep device/OEM-only checks (sections 1–5) manual — emulators cannot cover them.

## 7. Release mechanics
- [ ] Update `versionCode` / `versionName` in `app/build.gradle.kts` (both editions
      share them; the lite APK carries a `-lite` versionName suffix automatically).
- [ ] Tag `vX.Y.Z` — `release.yml` builds and publishes BOTH signed APKs
      (`toadfiles-<tag>.apk` and `toadfiles-lite-<tag>.apk`;
      `TOADFILES_KEYSTORE_FILE/_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD` secrets required).
- [ ] Confirm the README screenshots still match the shipped UI (Robolectric/Roborazzi
      capture: `./gradlew :app:testFullDebugUnitTest --tests '*TabScreenshotsTest*'`).
