# Spike S3 — scan performance (P-050, P-051, P-052, F-010)

Date: 2026-10-31. Device: AVD `Medium_Phone` (adb serial `emulator-5554`), spike app `spike.foliate` with
All-files access granted (`appops … MANAGE_EXTERNAL_STORAGE allow`). Code: `ScanSpike.kt`; driver
`spikes/tools/s3_scan.sh`; placement `spikes/tools/s3_place_folders.sh` (corpus.md §4).
Raw data: `raw-s3-scan.txt` (warm), `raw-s3-cold.txt` (after reboot), `raw-s3-many.txt` (+5 000 files),
`raw-s3-digest.txt`.

**Decision: go.** First full scan **≈ 0.3 s cold** (Must ≤ 7 s), first book **73–107 ms** (Must ≤ 500 ms), unchanged
rescan **44–78 ms** (Must ≤ 300 ms) on the corpus + folder set. One design change for M4 (streamed walk, below)
because a folder with thousands of files can push "first book visible" past 500 ms.

## Data set on the device

63 `.epub` names under `/storage/emulated/0`: the 42 corpus books of corpus.md §1–3 + the folder set (16
placements incl. `fake.epub`, `UPPER.EPUB`, `dup-copy.epub`, `.hidden/`, `NoMedia/.nomedia`, `Android/media/…`,
`Livres Été 2026/`) = 58, plus 5 extra files already on the device (`Books/generated/fake.epub`,
`Books/local/script-test.epub`, `Documents/Books/moved.epub`, `Download/Live/livetest.epub`,
`Download/renamed-NEW.epub`). The leftovers make the run
slightly heavier than the spec's 58 files; they were not removed. Of 63 files, 59 are zips (central-directory
digest OK) and 58 have a readable `container.xml` → OPF → title (the "minimal metadata" step).

Page cache: `/proc/sys/vm/drop_caches` is not writable (production image, `adb root` refused), so "cold" = right
after an emulator reboot (the host's own page cache for the emulator disk image is still warm).

## Results (ms)

Per-file work in "full" mode: `length()` + `lastModified()`, SHA-256 of the zip central directory (read from the end
of the file), and `ZipFile` → `container.xml` → OPF → `dc:title` regex. "stat" mode = walk + `length()` +
`lastModified()` only (models the unchanged rescan, P-051).

| Walk strategy, per-file workers | Cache | full: total (3 runs) | full: first book | stat (rescan) total |
|---|---|---|---|---|
| recursive `listFiles`, 1 | warm | 560, 100, 420 → **420** | 76, 15, 72 | 78, 67, 70 → **70** |
| parallel by top-level dir (4 threads), 1 | warm | 387, 106, 277 → **277** | 26, 14, 60 | 52, 47, 11 → **47** |
| `Files.walkFileTree`, 1 | warm | 413, 395, 425 → **413** | 87, 85, 75 | 76, 64, 59 → **64** |
| parallel walk, 4 workers | warm | 208, 182, 49 → **182** | 55, 47, 14 | — |
| **parallel walk, 4 workers** | **cold (after reboot)** | 287, 300, 299 → **299** | 73, 80, 107 → **80** | 44, 49, 60 → **49** |

Walk alone: 10–90 ms. Largest single-file cost: 66 ms (warm, first run), typically 6–40 ms.

**127 MB book** (`stress-200-images.epub`, 3 runs): central-directory digest **1–3 ms**, container/OPF/title
**1–4 ms**. Identity never reads the image data.

### Review Focus 4: 5 000 dummy files in `Android/media/com.example.test/many/`

| Config | Total | First book | Walk |
|---|---|---|---|
| parallel walk, 4 workers, full | 249, 676, 696 → **676** | 229, 576, 597 → **576** ❌ | 227, 567, 588 |
| parallel walk, stat (rescan) | 204, 180, 205 → **204** | — | 203, 177, 204 |
| recursive, 1 worker, full | 867, 219, 330 → **330** | 630, 157, 276 | 621, 155, 274 |

Listing one directory of 5 000 entries (plus `isDirectory`/`isFile` per entry) dominates. Because the spike walks
**everything before** processing the first file, "first book visible" equals the walk time and exceeds the
500 ms Must in 2 of 3 runs. The dummy directory was removed afterwards.

## Projection for P-050 / P-052

- P-050 (≤ 7 s): measured 0.3 s cold with minimal metadata. M2's full `PackageLoader` (OPF + nav/NCX + cover locate)
  is budgeted at 2× the `ZipFile` open per plan; even at 10× the per-file cost (≈ 40 ms/file, 4 workers) the scan
  stays ≈ 1 s. Cover thumbnails are a second phase (M4 Task 4.4) and are not on this path; if they were, ≈ 59
  downsampled decodes at an assumed 30–80 ms on 4 threads add ≈ 0.5–1.2 s (not measured).
- P-051 (≤ 300 ms): 44–78 ms here; 180–205 ms with 5 000 extra files. Cost scales with the number of directory
  entries on shared storage, not with books.
- P-052 (≤ 1.2 s, 14 new small books): not measured separately (C-015: lean). Projection from these numbers:
  stat walk (≈ 50 ms) + 14 × full per-file work (≈ 4 ms cold, `(full − stat) / 59`) ≈ 0.1 s.

## Decision and design for M4 Task 4.2

1. **Walker:** `java.io.File.listFiles()`, parallel by top-level directory (4 threads); `Files.walk` is not used
   (aborts on the first unreadable directory such as `Android/data`; `walkFileTree` with `visitFileFailed → CONTINUE`
   works but was not faster).
2. **Design change (from the 5 000-file run):** the walker **streams** paths into a channel and per-file work starts
   immediately on 4 workers (producer/consumer), instead of walking first. "First book visible" then depends on the
   first `.epub` found, not on the largest directory. Additionally, walk `Books/`, `Download/`, `Documents/` first
   (where books usually live) and everything else after.
3. **Identity:** central-directory SHA-256 confirmed cheap for any file size (≤ 3 ms for 127 MB).
4. **Rescan:** (path, size, mtime) unchanged → no zip I/O (as planned). Optional if P-051 ever gets tight on real
   devices: remember directory mtimes and skip re-listing unchanged directories (not needed for the Must now).

## Questions for the gatekeeper

None blocking. Note: P-050's "scan complete" is read as "all books listed with metadata"; covers arrive
afterwards (the master plan's Task 4.4). If "scan complete" must include cover thumbnails, the projection above
still fits in 7 s.
