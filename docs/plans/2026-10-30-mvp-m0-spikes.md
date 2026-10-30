# M0 — Risk Spikes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure, on the reference emulator, whether the four riskiest designs can meet their Must targets
before production code depends on them, and record a decision for each.

**Architecture:** All spikes run in `spikes/foliate-spike/`, a copy of `prototypes/foliate` (the bake-off app,
which already opens books in place, serves zip entries through a path handler, and has the debug command receiver
and Probe events the bake-off tools use). Spike code is throwaway: production modules (M1+) never depend on it.
Each spike ends with a report in `docs/spikes/` containing raw medians, the decision, and the design to carry
into M3/M5/M6.

**Tech Stack:** as the prototype (AGP 9.1.0, Kotlin 2.4.20, androidx.webkit 1.15.0, foliate-js `78914ae`);
S4 adds `androidx.sqlite:sqlite-bundled`.

**Spec:** master plan `docs/plans/2026-10-30-mvp-plan.md`; performance.md (P-021, P-026, P-050..P-052,
P-040..P-044); engine-evaluation.md §5–§8.

## Global Constraints

See the master plan. In particular: medians of ≥ 3 runs on AVD `Medium_Phone`; taps and recordings through
`$AD` (from `device_open` with host `local`, device `Medium_Phone`); adb only for install, logcat, `am`, dumpsys.
Never open `~/Projects/epub-reader-dirty`. Commit messages end with
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Counting pages in a hidden frame with CSS that differs subtly from the visible paginator (margins, gap,
   `max-column-count`, injected font size) → counts disagree. S1 compares per section, exact equality expected.
2. Counting work stealing the renderer main thread → page turns stutter while counting. S1 measures turn time
   during counting.
3. Preloaded neighbour sections leaking memory across many boundary turns. S2 samples PSS after 30 turns.
4. `/sdcard` trees with thousands of files in app media folders → walk time dominated by one directory. S3 seeds
   5 000 dummy files in `Android/media/com.example.test/` for one run.
5. FTS5 tokenization of apostrophes/hyphens ("Borodinó's", "well-known") → missed hits. S4 checks both.

---

### Task S0: Spike workspace and device setup

**Files:**
- Create: `spikes/foliate-spike/` (copy of `prototypes/foliate` with `applicationId = "spike.foliate"`)
- Create: `spikes/README.md`

- [ ] **Step 1: Copy the prototype and rename the package id**

```bash
cd ~/Projects/epub-reader
mkdir -p spikes
rsync -a --exclude build --exclude .gradle prototypes/foliate/ spikes/foliate-spike/
rsync -a prototypes/common/ spikes/common/      # own copy: the prototypes stay the bake-off record
sed -i 's/applicationId = "proto.foliate"/applicationId = "spike.foliate"/' spikes/foliate-spike/app/build.gradle.kts
sed -i 's/rootProject.name = "proto-foliate"/rootProject.name = "spike-foliate"/' spikes/foliate-spike/settings.gradle.kts
```
(`app/build.gradle.kts` already points at `../../common/kotlin`, which now resolves to `spikes/common/kotlin`.)

- [ ] **Step 1b: Add a command extension hook to the spike's shell**

In `spikes/common/kotlin/proto/common/BaseReaderActivity.kt` add `open fun onExtraCommand(cmd: String, arg: String) {}`
next to the other `open` functions, and in the receiver's `when (cmd)` add the branch
`else -> onExtraCommand(cmd, arg)` after `"back" -> finish()`. Spike commands (`count`, `sample`, `scan`,
`ftsbuild`, `ftsquery`) are implemented by overriding `onExtraCommand` in `spikes/foliate-spike/.../ReaderActivity.kt`.

- [ ] **Step 2: Write `spikes/README.md`**

```markdown
# Spikes (M0) — throwaway

Experiments for the MVP plan's M0. Nothing here is a dependency of production modules. Reports live in
docs/spikes/. Build: `cd spikes/foliate-spike && ./gradlew :app:installRelease`.
```

- [ ] **Step 3: Build, install, and check the corpus is on the device**

Run (device tools: call `device_list`, then `device_open` with hostId `local` and the `Medium_Phone` device id;
export the returned command as `AD`):
```bash
cd ~/Projects/epub-reader/spikes/foliate-spike && ./gradlew :app:installRelease
adb shell ls /sdcard/Books/gutenberg/war-and-peace-epub3.epub /sdcard/Books/generated/stress-huge-single-chapter.epub
```
Expected: install succeeds; both files listed. If missing, push the corpus as in corpus.md §"Pushing to an emulator".

- [ ] **Step 4: Commit**

```bash
git add spikes/README.md spikes/foliate-spike spikes/common
git commit -m "spikes: add throwaway spike app copied from the foliate prototype

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task S1: P-021 page totals (time box: 3 days) — RISKIEST

**Question:** After a layout change in War and Peace (368 sections, ≈2200 pages), can we (a) show an estimate
within ±5 % of the final total immediately and (b) compute the exact total within 5 s, without making page
turns visibly slower?

**Files:**
- Create: `spikes/foliate-spike/app/src/main/assets/host/count.js`
- Modify: `spikes/foliate-spike/app/src/main/assets/host/host.js` (commands `countAll`, `sampleVisible`)
- Modify: `spikes/foliate-spike/app/src/main/java/proto/foliate/ReaderActivity.kt` (commands → JS; Probe events)
- Create: `spikes/tools/s1_pagecount.sh`
- Create: `docs/spikes/S1-page-totals.md`

- [ ] **Step 1: Write the counter with three strategies**

`count.js` exports `countAll(book, opts)` where `opts = { strategy: 'sequential' | 'batched' | 'pipelined',
width, height, gap, margin, css }`. Each strategy reports `{ index, pages }` per section and a final total.

```js
// Hidden-layout page counter. The column CSS must match paginator.js `columnize()` exactly.
const frameFor = (w, h) => {
  const f = document.createElement('iframe')
  Object.assign(f.style, { position: 'absolute', left: '-20000px', top: '0', width: w + 'px', height: h + 'px',
    border: '0', visibility: 'hidden' })
  document.body.append(f)
  return f
}
const loadInto = (frame, src) => new Promise(res => { frame.onload = () => res(frame.contentDocument); frame.src = src })
const columnize = (doc, { width, height, gap, margin, css }) => {
  const style = doc.createElement('style')
  style.textContent = css
  doc.head.append(style)
  const h = doc.documentElement
  // Same properties paginator.js View.columnize sets for a single column of width `width - 2*margin`.
  const colW = width - 2 * margin
  Object.assign(h.style, { boxSizing: 'border-box', columnWidth: colW + 'px', columnGap: gap + 'px',
    columnFill: 'auto', height: height + 'px', padding: `0 ${margin}px`, overflow: 'hidden' })
  return Math.max(1, Math.round(h.scrollWidth / (colW + gap)))
}
export async function countAll(book, opts, onSection) {
  const t0 = performance.now()
  const n = book.sections.length
  const results = new Array(n)
  if (opts.strategy === 'sequential' || opts.strategy === 'pipelined') {
    const lanes = opts.strategy === 'pipelined' ? 2 : 1
    const frames = Array.from({ length: lanes }, () => frameFor(opts.width, opts.height))
    let next = 0
    await Promise.all(frames.map(async frame => {
      while (next < n) {
        const i = next++
        const s = book.sections[i]
        if (s.linear === 'no' || !s.load) { results[i] = 0; continue }
        const doc = await loadInto(frame, await s.load())
        results[i] = columnize(doc, opts)
        s.unload?.()
        onSection?.(i, results[i])
        await new Promise(r => setTimeout(r, 0)) // yield so page turns can run
      }
    }))
    frames.forEach(f => f.remove())
  } else {
    // batched: one document per batch of sections that share the same stylesheet links; each section body in
    // its own multi-column box; read each box's scrollWidth after one layout pass.
    const frame = frameFor(opts.width, opts.height)
    for (let start = 0; start < n; start += opts.batch ?? 24) {
      const idx = [...Array(Math.min(opts.batch ?? 24, n - start)).keys()].map(k => start + k)
      const docs = await Promise.all(idx.map(i => book.sections[i].createDocument()))
      const html = docs.map((d, k) => `<div class="er-box" data-i="${idx[k]}">${d.body?.innerHTML ?? ''}</div>`).join('')
      const links = [...(docs[0]?.querySelectorAll('link[rel=stylesheet]') ?? [])].map(l => l.outerHTML).join('')
      const doc = await loadInto(frame, URL.createObjectURL(new Blob([
        `<!doctype html><html><head>${links}<style>${opts.css}
         .er-box{box-sizing:border-box;height:${opts.height}px;column-width:${opts.width - 2 * opts.margin}px;
         column-gap:${opts.gap}px;column-fill:auto;padding:0 ${opts.margin}px;overflow:hidden}</style></head>
         <body style="margin:0">${html}</body></html>`], { type: 'text/html' })))
      for (const box of doc.querySelectorAll('.er-box')) {
        const i = +box.dataset.i
        results[i] = Math.max(1, Math.round(box.scrollWidth / (opts.width - 2 * opts.margin + opts.gap)))
        onSection?.(i, results[i])
      }
      await new Promise(r => setTimeout(r, 0))
    }
    frame.remove()
  }
  return { total: results.reduce((a, b) => a + (b ?? 0), 0), results, ms: performance.now() - t0 }
}
```

Note for the executor: the batched strategy changes document structure (sections become `div`s inside one body),
so selectors like `body > p:first-child` and per-section stylesheets can change layout. That is exactly what the
agreement check in Step 3 measures; do not "fix" counts by fudging.

- [ ] **Step 2: Wire commands and events**

In `host.js` add:
```js
import { countAll } from './count.js'
window.countAll = async (strategy, batch) => {
  const r = view.renderer
  const rect = r.getBoundingClientRect()
  A.log(`count_start strategy=${strategy}`)
  const res = await countAll(book, { strategy, batch, width: rect.width, height: rect.height,
    gap: Math.round(rect.width * 0.06), margin: 24, css: css() },
    (i, p) => { if (i % 50 === 0) A.log(`count_progress i=${i}`) })
  A.log(`count_done strategy=${strategy} total=${res.total} ms=${Math.round(res.ms)}`)
  window.__lastCounts = res.results
}
// Visible paginator page count for one section (agreement check).
window.sampleVisible = async index => {
  await view.goTo(index)
  await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))
  A.log(`visible_pages index=${index} pages=${view.renderer.pages - 2} counted=${window.__lastCounts?.[index]}`)
}
```
In `ReaderActivity.kt` (`onExtraCommand`) route debug commands `count <strategy> [batch]` → `js("countAll('$s', $b)")` and
`sample <index>` → `js("sampleVisible($i)")`, each logged with `Probe.ev("cmd", ...)`.
(Probe events and the broadcast receiver come from the copied shell; `arg` arrives as one string, split on spaces.)

- [ ] **Step 3: Measure time-to-exact and per-section agreement**

Create `spikes/tools/s1_pagecount.sh`:
```bash
#!/usr/bin/env bash
# Usage: spikes/tools/s1_pagecount.sh <strategy> <fontScale> <runs> [batch]
set -u
S=$1; F=$2; RUNS=$3; B=${4:-24}; PKG=spike.foliate
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
for r in $(seq 1 "$RUNS"); do
  $A shell am force-stop $PKG
  $A shell am start -W -n $PKG/.ReaderActivity --es path /sdcard/Books/gutenberg/war-and-peace-epub3.epub >/dev/null
  sleep 6
  $A shell am broadcast -a $PKG.CMD --es cmd font --es arg "$F" >/dev/null; sleep 2
  $A logcat -c
  $A shell am broadcast -a $PKG.CMD --es cmd count --es arg "'$S $B'" >/dev/null
  for _ in $(seq 1 90); do sleep 1; $A logcat -d -s PROTO:I | grep -q count_done && break; done
  $A logcat -d -s PROTO:I | grep -o 'count_done.*' | sed "s/^/run=$r font=$F /"
done
```
Run, for each strategy in `sequential pipelined batched` and font scales `1.0 1.3 1.8`:
```bash
chmod +x spikes/tools/s1_pagecount.sh
for s in sequential pipelined batched; do for f in 1.0 1.3 1.8; do spikes/tools/s1_pagecount.sh $s $f 3; done; done | tee docs/spikes/raw-s1-times.txt
```
Expected: lines `run=N font=F count_done strategy=S total=T ms=M`. Record median `ms` per (strategy, font).

Then agreement: after a `count` run, sample 20 sections spread over the book (indices 5, 23, 41, …, 365) with
`am broadcast … --es cmd sample --es arg <i>` and collect `visible_pages` lines. Expected for adoption:
`pages == counted` for all 20 under the chosen strategy.

- [ ] **Step 4: Measure page-turn impact while counting**

Start a `count` and, 1 s later, run `tools/bench_action.sh spike.foliate s1-turn-during-count 3 next` (from the
repo `tools/`, AD exported) three times; compare with the same without counting. Record video/log ms.

- [ ] **Step 5: Evaluate the estimator offline from the logged counts**

Estimator definition (to carry into M5 if adopted): for the sections already laid out visibly (current ± 1),
`cpp = Σ textChars / Σ pages`; estimate = `Σ_counted pages + Σ_uncounted ceil(textChars_i / cpp) + images_i`,
where `images_i` = number of `<img>`/`<svg>` elements in section i that are block-level (1 page each when the image
height > 50 % of the page, else 0). Section text lengths: compute with a quick Python script over the EPUB
(`python3 -I`, zipfile + html.parser, count text inside `<body>`), saved as `docs/spikes/raw-s1-textlen.json`.
Compute the estimate seeded from section 0..2 and from a middle section, compare with the exact total at each font
scale; record the error %. Expected for adoption: |error| ≤ 5 %.

- [ ] **Step 6: Write the report and decide**

`docs/spikes/S1-page-totals.md` must contain: a table of median ms per strategy × font scale; agreement result
(n/20 equal) per strategy; turn-time impact; estimator error table; **Decision** — one of:
(A) strategy X meets ≤ 5 s exact with 20/20 agreement → M5 Task 5.3 uses it;
(B) exact > 5 s but estimate within ±5 % → M5 ships "≈ N" + background exact; escalate P-021 to the gatekeeper with
the numbers (add to master plan Open questions);
(C) neither → propose alternatives (e.g. per-layout persistent cache warmed on idle at likely settings) with numbers.

- [ ] **Step 7: Commit**

```bash
git add spikes/foliate-spike spikes/tools/s1_pagecount.sh docs/spikes/S1-page-totals.md docs/spikes/raw-s1-*.txt docs/spikes/raw-s1-*.json
git commit -m "spike S1: P-021 page totals measurements and decision

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task S2: Neighbour preloading (time box: 2 days)

**Question:** Can a patched foliate paginator keep the previous/next sections laid out so that a boundary turn in
War and Peace takes ≤ 400 ms with no blank frame, at acceptable memory, and is continuous scrolling across
sections feasible with the same mechanism?

**Files:**
- Modify: `spikes/foliate-spike/app/src/main/assets/foliate-js/paginator.js`
- Create: `docs/spikes/S2-preloading.md`

- [ ] **Step 1: Baseline the unpatched boundary turn**

Navigate to the last page of a War and Peace chapter (`toc` to "BOOK ONE: 1805" then `next` until
`relocate` shows `page == pages`), then:
```bash
OUT=docs/spikes/raw-s2 tools/bench_action.sh spike.foliate s2-base-1 3 next
python3 -I tools/blank_frames.py docs/spikes/raw-s2/foliate-s2-base-1.mp4 | tail -1
```
Repeat 5 times. Expected (bake-off): ≈630 ms video, `min_ink` ≈ 0 (blank frame).

- [ ] **Step 2: Patch the paginator to keep neighbour views**

In `paginator.js` (class `Paginator`), add a small view cache:
- `#views = new Map()` from section index → `{ view, ready: Promise }`.
- After `#display` resolves for index `i`, call `#preload(i - 1)` and `#preload(i + 1)` (skipping non-linear
  sections), each creating a `View` in an off-screen container with the same size and `render(layout)`, loading the
  section's `load()` URL, and running the same `beforeRender`/column setup used for the current view.
- In `#turnPage(dir)` when the turn crosses the section boundary and `#views.get(target)` is ready: detach the
  current view element, attach the preloaded one in its place, scroll it to the first page (forward) or last page
  (backward) **before** it becomes visible, then run the normal slide animation; evict views farther than ±1.
- Keep everything else unchanged; record each changed function in a comment block at the top of the file
  (`// SPIKE PATCH:` lines) for S2's report.

- [ ] **Step 3: Measure the patched boundary turn**

Same commands as Step 1, 10 boundary turns (tag `s2-pre-N`). Expected for adoption: median video ms ≤ 400 and every
`min_ink` > 0.01 (a text page has a few percent ink).

- [ ] **Step 4: Memory and correctness checks**

- 30 page turns crossing ≥ 10 boundaries, then `PKG=spike.foliate tools/mem_total.sh` (3 samples). Compare with
  the bake-off P-060 (212 MB). Expected: ≤ 215 MB (Must).
- `relocate` CFIs after a boundary turn equal those of the unpatched app for the same page (compare logs).
- Text selection and a search-hit highlight still work in a preloaded-then-shown section (manual check via
  `$AD` long-press screenshot).

- [ ] **Step 5: Scrolled-mode feasibility (no implementation)**

Read `paginator.js` scrolled mode (`scrolled()`, `#scrollNext`) and write half a page on how the same cache would
stack previous/current/next views vertically for continuous scroll (F-031), including how `relocate` and CFIs would
be computed, and the estimated effort. No code.

- [ ] **Step 6: Report, decide, commit**

`docs/spikes/S2-preloading.md`: before/after table, blank-frame result, memory, correctness notes, scrolled-mode
design, and the patch description that M3 Task 3.4 will re-implement cleanly in `reader-web/vendor/foliate-js`.
```bash
git add spikes/foliate-spike docs/spikes/S2-preloading.md
git commit -m "spike S2: neighbour-section preloading measurements and design

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task S3: Scan performance (time box: 1 day)

**Question:** Which walk strategy and which per-file work let the first full scan of the corpus + folder set
(58 files, ~225 MB) finish in ≤ 7 s with the first book visible ≤ 500 ms, and an unchanged rescan in ≤ 300 ms?

**Files:**
- Create: `spikes/foliate-spike/app/src/main/java/proto/foliate/ScanSpike.kt`
- Modify: `spikes/foliate-spike/app/src/main/AndroidManifest.xml` (add `MANAGE_EXTERNAL_STORAGE`)
- Create: `docs/spikes/S3-scan.md`

- [ ] **Step 1: Place the folder-discovery set**

Use the placement table in corpus.md §4 (generate with `python3 -I ~/Projects/epub-reader-spec/tools/gen_folder_books.py corpus/folders download documents nested3 unicode hidden nomedia androidmedia dup upper livetest renamed moved deletedfolder outsidecustom insidecustom` and `printf 'not an epub at all\n' > corpus/folders/fake.epub`), pushing each file with `adb push` to its folder (create `NoMedia/.nomedia`, save `upper.epub` as `UPPER.EPUB`, `dup.epub` also as `Documents/Books/dup-copy.epub`). Leave `livetest.epub` unpushed.
Grant: `adb shell appops set spike.foliate MANAGE_EXTERNAL_STORAGE allow`.

- [ ] **Step 2: Implement three walkers and the per-file work**

```kotlin
package proto.foliate

import android.os.Environment
import android.os.SystemClock
import proto.common.Probe
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ScanSpike {
    private fun isEpub(f: File) = f.isFile && f.name.endsWith(".epub", ignoreCase = true)

    fun walkRecursive(root: File, out: MutableList<File>) {
        val kids = root.listFiles() ?: return
        for (k in kids) if (k.isDirectory) walkRecursive(k, out) else if (isEpub(k)) out += k
    }

    fun walkParallel(root: File): List<File> {
        val out = ConcurrentLinkedQueue<File>()
        val pool = Executors.newFixedThreadPool(4)
        root.listFiles()?.forEach { top ->
            if (top.isDirectory) pool.execute { val l = ArrayList<File>(); walkRecursive(top, l); out += l }
            else if (isEpub(top)) out += top
        }
        pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS)
        return out.toList()
    }

    fun walkNio(root: File): List<File> {
        val out = ArrayList<File>()
        java.nio.file.Files.walk(root.toPath()).use { s -> s.forEach { p -> val f = p.toFile(); if (isEpub(f)) out += f } }
        return out
    }

    /** SHA-256 of the zip central directory (read from the end of the file), or null if not a zip. */
    fun cdDigest(f: File): String? = RandomAccessFile(f, "r").use { raf ->
        val len = raf.length(); if (len < 22) return null
        val tailLen = minOf(len, 65_557L).toInt()
        val tail = ByteArray(tailLen); raf.seek(len - tailLen); raf.readFully(tail)
        var i = tailLen - 22
        while (i >= 0 && !(tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte())) i--
        if (i < 0) return null
        fun u32(o: Int) = (tail[o].toLong() and 0xff) or ((tail[o + 1].toLong() and 0xff) shl 8) or
            ((tail[o + 2].toLong() and 0xff) shl 16) or ((tail[o + 3].toLong() and 0xff) shl 24)
        val cdSize = u32(i + 12); val cdOff = u32(i + 16)
        if (cdOff + cdSize > len) return null
        val cd = ByteArray(cdSize.toInt()); raf.seek(cdOff); raf.readFully(cd)
        MessageDigest.getInstance("SHA-256").digest(cd).joinToString("") { "%02x".format(it) }
    }

    fun run(strategy: String) {
        val root = Environment.getExternalStorageDirectory()
        val t0 = SystemClock.uptimeMillis()
        val files = when (strategy) {
            "recursive" -> ArrayList<File>().also { walkRecursive(root, it) }
            "parallel" -> walkParallel(root)
            else -> walkNio(root)
        }
        val tWalk = SystemClock.uptimeMillis()
        var ok = 0; var first = -1L
        for (f in files) {
            f.length(); f.lastModified() // the stat an unchanged rescan needs
            if (cdDigest(f) != null) { ok++; if (first < 0) first = SystemClock.uptimeMillis() - t0 }
            runCatching { java.util.zip.ZipFile(f).use { z -> z.getEntry("META-INF/container.xml") } }
        }
        Probe.ev("scan_spike", "strategy=$strategy files=${files.size} ok=$ok walk_ms=${tWalk - t0} total_ms=${SystemClock.uptimeMillis() - t0} first_ms=$first")
    }
}
```
Add a debug command `scan <strategy>` in `onExtraCommand` that runs `ScanSpike.run` on a background thread.
Also add a `stat-only` mode (walk + `length()`/`lastModified()` only) to model the unchanged rescan.

- [ ] **Step 3: Measure**

For each strategy, 3 runs with `am force-stop` between runs and `adb shell 'echo 3 > /proc/sys/vm/drop_caches'`
where permitted (note whether caches could be dropped). Record `walk_ms`, `total_ms`, `first_ms`, and `stat-only`
`total_ms`. Repeat once with 5 000 dummy files in `Android/media/com.example.test/many/` (Review Focus 4).

- [ ] **Step 4: Report, decide, commit**

`docs/spikes/S3-scan.md`: table per strategy; time for the 127 MB book's digest (expected: milliseconds, CD only);
projected P-050 including metadata parse (add 2× the ZipFile open time as a budget); decision on walker + parallelism
for M4 Task 4.2. Commit with message `spike S3: scan walk and identity cost`.

---

### Task S4: FTS5 search index (time box: 1 day)

**Question:** Does the bundled SQLite driver provide FTS5 with `remove_diacritics 2`, and are index build and query
times compatible with P-040..P-044?

**Files:**
- Modify: `spikes/foliate-spike/app/build.gradle.kts` (add `androidx.sqlite:sqlite-bundled` latest stable)
- Create: `spikes/foliate-spike/app/src/main/java/proto/foliate/FtsSpike.kt`
- Create: `docs/spikes/S4-fts5.md`

- [ ] **Step 1: Implement build + query**

```kotlin
package proto.foliate

import android.os.SystemClock
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import proto.common.Probe
import java.io.File
import java.util.zip.ZipFile

object FtsSpike {
    private val TAG = Regex("<[^>]+>")
    private val BLOCK = Regex("</(p|div|h[1-6]|li|blockquote|td|pre|section)>", RegexOption.IGNORE_CASE)

    fun build(book: String, dbFile: File) {
        dbFile.delete()
        val c = BundledSQLiteDriver().open(dbFile.path)
        c.execSQL("CREATE VIRTUAL TABLE seg USING fts5(text, spine UNINDEXED, start UNINDEXED, tokenize = 'unicode61 remove_diacritics 2')")
        val t0 = SystemClock.uptimeMillis(); var rows = 0
        ZipFile(book).use { z ->
            val docs = z.entries().toList().filter { it.name.endsWith("html") || it.name.endsWith(".htm") }.sortedBy { it.name }
            c.execSQL("BEGIN")
            c.prepare("INSERT INTO seg(text, spine, start) VALUES (?, ?, ?)").use { st ->
                docs.forEachIndexed { si, e ->
                    val body = z.getInputStream(e).readBytes().toString(Charsets.UTF_8).substringAfter("<body", "")
                    var off = 0
                    for (block in BLOCK.split(body)) {
                        val text = TAG.replace(block, "").replace("&amp;", "&").replace("&nbsp;", " ")
                        if (text.isBlank()) continue
                        st.bindText(1, text); st.bindLong(2, si.toLong()); st.bindLong(3, off.toLong()); st.step(); st.reset()
                        off += text.length; rows++
                    }
                }
            }
            c.execSQL("COMMIT")
        }
        Probe.ev("fts_build", "rows=$rows ms=${SystemClock.uptimeMillis() - t0} bytes=${dbFile.length()}")
        c.close()
    }

    fun query(dbFile: File, q: String, limit: Int = 200) {
        val c = BundledSQLiteDriver().open(dbFile.path)
        val t0 = SystemClock.uptimeMillis(); var n = 0; var first = -1L
        c.prepare("SELECT spine, start, highlight(seg, 0, char(1), char(2)) FROM seg WHERE seg MATCH ? ORDER BY rowid LIMIT ?").use { st ->
            st.bindText(1, q); st.bindLong(2, limit.toLong())
            while (st.step()) { if (first < 0) first = SystemClock.uptimeMillis() - t0; n += st.getText(2).count { it == '\u0001' } }
        }
        Probe.ev("fts_query", "q=$q hits=$n first_ms=$first done_ms=${SystemClock.uptimeMillis() - t0}")
        c.close()
    }
}
```
Debug commands: `ftsbuild <path>` and `ftsquery <ftsQueryString> [limit]` (db at `filesDir/fts-spike.db`).
Also run `SELECT sqlite_version()` and `PRAGMA compile_options` once and log them.

- [ ] **Step 2: Measure**

Build for War and Peace, Linear Algebra and the huge chapter (3 runs each; record `ms`, `rows`, `bytes`). Queries
(3 runs each, limit 5000): `"the"*` (W&P), `"borodino"` (must count 108 hits incl. "Borodinó"), `"zygote"`,
`"augmented matrix"` (Linear Algebra), `"markerwordhuge"` (huge chapter, expect 138). Also test `"borodino's"`
tokenization and `"well-known"` phrase behaviour (Review Focus 5) and note the results.

- [ ] **Step 3: Report, decide, commit**

`docs/spikes/S4-fts5.md`: compile options showing FTS5; build/query medians; index size per book; decision for M6
(FTS5 as designed, or the fallback: FTS4 with our own diacritic-folded text column if FTS5 is missing).
Commit with message `spike S4: FTS5 availability and search timings`.

---

### Task S5: Amend the master plan

- [ ] **Step 1:** Update the M3/M5/M6 task specs in `docs/plans/2026-10-30-mvp-plan.md` with each spike's decision
(design chosen, numbers, any new open question for the gatekeeper).
- [ ] **Step 2:** Commit: `plan: amend after M0 spikes` (with the Co-Authored-By line).
