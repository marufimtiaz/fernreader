# Spike S4 — FTS5 search index (P-040..P-044, F-050)

Date: 2026-10-31. Device: AVD `Medium_Phone` (adb serial `emulator-5554`), spike app `spike.foliate` with
`androidx.sqlite:sqlite-bundled:2.7.1` (Apache-2.0; SQLite itself public domain). Code: `FtsSpike.kt`.
Raw data: `raw-s4-info.txt`, `raw-s4-build.txt`, `raw-s4-query.txt`.

**Decision: go — FTS5 as designed** (no FTS4 fallback needed). Every query is ≤ 15 ms on a built index; index build
is 0.7–2.8 s per book, so the only way to miss P-04x is searching before the index exists.

## Availability

`SELECT sqlite_version()` → **3.50.1**; `PRAGMA compile_options` includes **`ENABLE_FTS5`** (and FTS3/FTS4,
`THREADSAFE=2`). `CREATE VIRTUAL TABLE seg USING fts5(text, spine UNINDEXED, start UNINDEXED,
tokenize = 'unicode61 remove_diacritics 2')` works.

## Index build (3 runs; ms, from ZIP to committed index)

Text extraction in the spike is a regex approximation (split at block end tags, strip tags, decode entities), not
M2's `TextExtractor`; one row per block, one transaction, `journal_mode=OFF`, `synchronous=OFF`.

| Book | Rows | Text chars | Build ms (3 runs) | Median | Index size |
|---|---|---|---|---|---|
| War and Peace | 12 551 | 3.6 M | 1251, 832, 685 | **832** | 6.0 MB |
| Linear Algebra | 5 974 | 18.0 M | 2832, 2623, 1407 | **2623** | 19.9 MB |
| Huge single chapter | 25 078 | 10.3 M | 1506, 1062, 908 | **1062** | 15.4 MB |

(A single earlier W&P build in the same session: 826 ms.) Index size ≈ 1.2–1.7 × the text (bytes vs characters).

## Queries (built index; 3 runs; `first_ms` / `done_ms`, results capped at 5 000 hits)

Time includes opening the database file and reading `highlight()` markers for every returned row.

| Spec | Query (FTS5 syntax) | Hits | first ms | done ms | Must |
|---|---|---|---|---|---|
| P-040 | `"the"*` (W&P) | 5 000+ (capped; 1 089 rows) | 12, 11, 2 | 78, 43, 11 | first ≤ 500 ✅ |
| P-041 | `"borodino"` (W&P) | **108** | 2, 2, 2 | 9, 8, 7 | ≤ 1500 ✅ |
| P-041 | `"borodinó"` (as typed) | **108** | 2, 2, 1 | 8, 8, 2 | ✅ |
| C-001 default (prefix) | `"borodino"*` | 108 | 2, 2, 2 | 7, 7, 6 | — |
| P-042 | `"zygote"` / `"zygote"*` | 0 | — | 0–2 | ≤ 1200 ✅ |
| P-043 | `"augmented matrix"` (Linear Algebra, phrase) | 150 (136 rows) | 1, 0, 1 | 15, 4, 15 | first ≤ 3000 ✅ |
| P-044 | `"markerwordhuge"` (huge chapter) | **138** | 1, 2, 3 | 5, 6, 7 | first ≤ 2000, all ≤ 5000 ✅ |

"108 hits" for Borodino agrees with an independent count of the word `borodino` (diacritics folded) in the book
text (108).

### Review Focus 5: apostrophes and hyphens

- `unicode61` treats `’` and `'` as separators: "Prince’s" is indexed as `prince` + `s`. A search for "prince" (or
  `"prince"*`) therefore finds possessives; the phrase `"prince s"` finds 37 hits. W&P contains no "Borodino’s",
  so the `"borodino s"` test returns 0 correctly.
- `"well-known"` and `"well known"` both return the 24 phrase hits; `"well" "known"` (AND) returns 35 rows.
- Consequence for M6 Task 6.2: the **query builder must split the user's text with the same separator rules**
  (letters/digits vs everything else) and send multi-token input as one phrase, e.g. "prince's" → `"prince s"*`
  in default (prefix, C-001) mode and `"prince s"` in whole-word mode. Typing an apostrophe or hyphen then behaves
  as users expect. FTS5 syntax characters in user input never reach `MATCH` unquoted.

## Decision and design for M6

1. FTS5 + `unicode61 remove_diacritics 2` in the bundled driver, one DB per book, as planned.
2. **Build timing (the only risk to P-040..P-044):** queries are milliseconds, builds are 0.7–2.8 s. Build the index
   in the background right after the first page of a book is painted (as planned) **and** the search UI streams
   results from sections already indexed while the build continues (planned "single code path"); with
   sections built in reading order, results appear progressively. Measured cold numbers here imply: if a user
   searches W&P within ≈ 1 s of first opening it, "0 results" for `zygote` can only be final after the build
   (≈ 0.8 s) — still inside P-042's 1.2 s; Linear Algebra's first results need the section containing the first
   hit indexed (well under the 3 s P-043 budget unless the hit is near the end).
3. Query builder per Review Focus 5 above; whole-word mode = quoted tokens without `*`.
4. Index size (≈ 1.2–1.7 × text; 20 MB for Linear Algebra) is a disk cost per opened book; the index is a rebuildable
   cache (excluded from backups, deletable under storage pressure). No design change needed for the MVP.

## Questions for the gatekeeper

None. (Bench P-040..P-044 as planned measure with the index built — the book open for 6 s before searching, as in
the bake-off; the cold-index case is reported separately, not gated.)
