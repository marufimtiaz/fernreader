# M3 — Library and scanning: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the M2 reader into a library app: onboarding for all-files access, a streamed full-storage scan that
fills a grid/list library with covers, sort and search, a failure report, open-with from file managers, and safe
handling of missing or changed files, without losing any reading data from M2.

**Architecture:** The markup scanner, package loader, metadata and cover locator from the parked core file land in
`:core:epub` (Kotlin, host-tested on the corpus). A `Scanner` in `:core:data` consumes paths from a `StorageWalker`
(interface, C-008) while the walk is still running (S3), loads each new/changed file with `PackageLoader`, and
upserts `book` + `book_file` keyed by `BookIdentity` — the same key M2 computed from foliate's identifier, so
picker-opened books merge with scanned ones. `app` orchestrates scans (`ScanController`), extracts thumbnails off
the scan path, and hosts onboarding and open-with. `ui` gets a `LibraryState` + `LibraryScreen` replacing the M2
recent list.

**Tech Stack:** as master plan (Kotlin 2.4.20, AGP 9.1.0, Room KMP 2.8.4 + bundled SQLite, Compose Multiplatform
1.10.0, Coil 3.3.0, coroutines 1.10.2). No new libraries except Coil (already in the catalog; record in
`docs/licenses.md`, Apache-2.0).

**Spec:** `~/Projects/epub-reader-spec/` features.md F-001, F-003, F-010..F-016, F-018, F-020..F-023, F-028
(library part), performance.md P-001..P-003, P-050..P-052, R-001..R-004, R-008, R-010; clarifications C-008,
C-009, C-015, C-024. Master plan `2026-10-30-mvp-plan.md` §M3; parked core file
`2026-10-30-mvp-core-epub-parked.md` Tasks 2.2, 2.3, 2.5, 2.9 (Step 1); `docs/spikes/S3-scan.md`;
`docs/milestones/M2.md`.

## Global Constraints

Everything in the master plan's Global Constraints applies. In particular:
- Clean room: never open, list or search `~/Projects/epub-reader-dirty`; no proprietary reader as reference; our own wording and design.
- MIT; every new dependency MIT-compatible and listed in `docs/licenses.md`; `fdroid` runtime classpath stays clean (`./gradlew checkFdroidClean`).
- `minSdk 26`, `compileSdk/targetSdk 37`, JVM 17, package root `app.epubreader`.
- R-005: parsing, scanning, cover extraction never on the main thread; Back ≤ 200 ms.
- Room schema changes bump the version with an `AutoMigration`, schema JSON exported to `core/data/schemas/`; reading data is never dropped (M2 users read with dev builds).
- Positions crossing reader ↔ storage go through `PositionMap` (identity until M8).
- C-015: Must targets only; full bench at milestone end only; `tools/smoke.sh` is the per-build check.
- C-024: P-001/P-002 measured with Gradle daemons stopped and the T3 device panel closed.
- Corpus files are untrusted: never execute anything in `corpus/`; Python reading them runs with `python3 -I`.
- Device: T3 device tools, hostId `local`; the device id is `emulator-5554` (AVD Medium_Phone). UI driving via the `agent-device` command from `device_open` (`$AD`); `adb` only for build/install/push/logcat/`am start -W`/`am broadcast`/`dumpsys`/`appops`. Close the `$AD` session before instrumented tests (it holds the accessibility service) and before bench runs.
- `connectedAndroidTest` uninstalls the app: run `./gradlew :app:installPlayBenchmark` right before `bench/run_all.sh`.
- Commit per task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

Inputs the spec implies that no requirement check exercises directly, most likely first. Each has a pinning test in
the named task.

1. **A book read in M2 (picker, `content://`) meets the first scan.** Its identity came from foliate's identifier
   (whitespace-collapsed) + the CD digest. Expected: the scan attaches the file to the same `book.id`, the reading
   position is untouched, the library shows one entry. Pinned: Task 3.5 `M2LibraryMigrationTest`
   (identifier with newlines/tabs inside, schema v2 database migrated to v3) and Task 3.3 `IdentityAgreementTest`.
2. **Unreadable directories during the walk** (`Android/data`, `Android/obb`, permission-denied folders, a folder
   deleted mid-walk, symlink loops). Expected: skipped silently, the walk finishes, everything else is found.
   Pinned: Task 3.5 `FileWalkerTest.unreadable_and_vanishing_dirs_skipped`, `.symlink_loop_terminates`.
3. **App killed or Back pressed during the first scan.** Expected: next start rescans incrementally, no duplicate
   books, no `scan` file row pruned from a scan that did not finish. Pinned: Task 3.5
   `ScannerTest.interrupted_scan_resumes` (prune only after a completed walk).
4. **A file that changes while it is being scanned or opened** (download still writing, replaced by another
   edition). Expected: no crash; identity is computed from what was read; a later rescan fixes it; the open reader
   shows a message (R-008). Pinned: Task 3.5 `ScannerTest.same_path_different_content`, Task 3.9
   `FileReplacedUnderReaderTest`.
5. **Titles/authors that are empty, whitespace-only, very long or RTL; books with no metadata at all.** Expected:
   fallback title = file name without extension; neutral author placeholder; truncation, no layout break; RTL
   right-aligned. Pinned: Task 3.3 `MetadataTest.empty_title_falls_back_to_file_name` (the fallback is applied by
   the Scanner, test in Task 3.5 `ScannerTest.untitled_book_uses_file_name`) and Task 3.8
   `LibraryStateTest.rtl_and_blank_metadata`.

---

## File structure

```
core/epub/src/commonMain/kotlin/app/epubreader/core/epub/
  markup/  TextDecoding.kt HtmlEntities.kt Entities.kt MarkupScanner.kt XmlTree.kt MarkupRewriter.kt Paths.kt   (3.1)
  pkg/     BookPackage.kt ContainerParser.kt OpfParser.kt PackageLoader.kt                                       (3.2)
  meta/    MetadataReader.kt CoverLocator.kt   (BookIdentity.kt exists)                                          (3.3)
core/epub/src/jvmTest/.../markup/*Test.kt pkg/PackageLoaderTest.kt meta/MetadataTest.kt CorpusTest.kt
core/data/src/commonMain/kotlin/app/epubreader/core/
  data/    ScanFailureEntity.kt, LibraryDao.kt (+queries), LibraryRepository.kt (+library/scan API), AppDatabase.kt (v3)
  library/ StorageWalker.kt Scanner.kt ScanEvent.kt ScanScope.kt LibraryBook.kt                                  (3.5)
core/data/src/jvmSharedMain/kotlin/app/epubreader/core/library/FileStorageWalker.kt  (java.io.File; JVM + Android)
core/data/src/jvmTest/.../library/{ScannerTest.kt, FileWalkerTest.kt, M2LibraryMigrationTest.kt}
app/src/main/kotlin/app/epubreader/
  library/ ScanController.kt CoverExtractor.kt StorageAccess.kt FileWatch.kt                                     (3.4, 3.6, 3.7, 3.9)
  MainActivity.kt (intents, onboarding route, library wiring)  AppGraph.kt (scanner, covers)
ui/src/commonMain/kotlin/app/epubreader/
  library/ LibraryState.kt LibraryScreen.kt BookCover.kt FailureSheet.kt                                         (3.8)
  onboarding/ OnboardingScreen.kt                                                                                (3.4)
  home/ (deleted: replaced by library/)
bench/groups/scan.sh, bench/groups/start.sh (+P-003), bench/push_corpus.sh (+14-book set)                       (3.10)
docs/milestones/M3.md
```

---

### Task 3.0: M2 carry-overs (reader event buffer, Back during open, test flake)

Fix first: every later task's instrumented verification depends on a stable suite.

**Files:**
- Modify: `app/src/main/kotlin/app/epubreader/reader/ReaderEngine.kt` (event flow)
- Modify: `ui/src/commonMain/kotlin/app/epubreader/reader/ReaderController.kt` (open/close race)
- Test: `ui/src/jvmTest/kotlin/app/epubreader/reader/ReaderControllerTest.kt` (new cases), the flaky instrumented test(s)

**Interfaces:** no public changes.

- [ ] **Step 1: Event buffer — failing test.** Today `ReaderEngine._events` is `MutableSharedFlow(extraBufferCapacity = 256, DROP_OLDEST)`: while the controller's collector is suspended (database write in `recordOpened`, `positions.await()`), a burst of > 256 events drops the oldest — possibly an `opened`, a `seeked` or the relocate that is the user's last page (≤ 1 page of position lost). Extract the emit policy into a tiny testable class in `ui` (`ReaderEvents`, commonMain): `class ReaderEvents { val flow: SharedFlow<FromWeb>; fun emit(m: FromWeb): Boolean }`, backed by `MutableSharedFlow<FromWeb>(extraBufferCapacity = Int.MAX_VALUE)` (SUSPEND policy with unbounded buffer: `tryEmit` never fails, nothing dropped). Test in `ui/src/jvmTest/.../reader/ReaderEventsTest.kt`:

```kotlin
@Test fun burst_while_collector_is_slow_drops_nothing() = runTest {
    val ev = ReaderEvents()
    val got = mutableListOf<FromWeb>()
    val gate = CompletableDeferred<Unit>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { ev.flow.collect { gate.await(); got += it } }
    repeat(1000) { i -> assertTrue(ev.emit(FromWeb.Relocate(sessionId = "s", cfi = "c$i", fraction = 0.0, sectionIndex = 0, tocHref = null))) }
    gate.complete(Unit); advanceUntilIdle()
    assertEquals(1000, got.size); assertEquals("c999", (got.last() as FromWeb.Relocate).cfi)
    job.cancel()
}
```
  (Match `FromWeb.Relocate`'s real constructor in `bridge/Protocol.kt`.) Run `./gradlew :ui:jvmTest --tests '*ReaderEventsTest*'` → FAIL (class missing). Implement; `ReaderEngine` uses `ReaderEvents`. → PASS.

- [ ] **Step 2: Back during an open — failing test.** In `ReaderController.open`, if `close()` runs while `opener.open(ref)` is in flight, the coroutine is cancelled and the `OpenResult.Ok` session it produced is never closed (it stays registered until the next open retires it). Add to `ReaderControllerTest` (use its fakes; the fake opener suspends on a `CompletableDeferred` and records `close(sessionId)` calls):

```kotlin
@Test fun back_while_opening_closes_the_session_that_was_being_opened() = runTest {
    val opener = FakeOpener(suspendOpen = true)
    val c = controller(opener = opener)
    c.open(SourceRef.File("/b.epub")); runCurrent()
    c.close()                                   // Back before the opener returned
    opener.finishOpen(OpenResult.Ok("s1", "cd", 10)); advanceUntilIdle()
    assertEquals(listOf("s1"), opener.closed)
}
```
  Fix: run `opener.open` under `withContext(NonCancellable)` and, if the job is no longer active afterwards (or `session` never got set), call `opener.close(r.sessionId)` before rethrowing. Keep the existing `isReady` cancellation branch. Run `./gradlew :ui:jvmTest` → PASS.

- [ ] **Step 3: Find the flake.** Close any `$AD` session. Run the instrumented suite 5× and the host suites 3×, logging each failure:
  `for i in 1 2 3 4 5; do ./gradlew :app:connectedPlayDebugAndroidTest --continue > /tmp/m3-inst-$i.log 2>&1; grep -E "FAILED|tests completed" /tmp/m3-inst-$i.log; done`.
  Use superpowers:systematic-debugging on each failing test: read its failure, form a hypothesis (timing on a loaded host is the usual cause — waits on fixed sleeps instead of events), fix the test or the app, never by just raising timeouts without a reason. Record test name, failure count before/after and the cause in the commit message (and later in `docs/milestones/M3.md`).
- [ ] **Step 4: Verify.** 5 more instrumented runs with 0 failures (if a run fails for an unrelated device reason, e.g. `adb` offline, say so and rerun once). `./gradlew check` green.
- [ ] **Step 5: Commit** `reader: no dropped reader events; Back during open closes the session; <flaky test> fix`.

---

### Task 3.1: Markup foundation (parked Task 2.2)

**Files / interfaces / steps:** exactly as parked file Task 2.2 (`2026-10-30-mvp-core-epub-parked.md`, section
"Task 2.2: Markup foundation", Steps 1–7, test and implementation code given there), with these re-checks:
- `core/epub/build.gradle.kts`: add `alias(libs.plugins.kotlin.serialization)` and `implementation(libs.serialization.json)` only if Task 2.2 code uses them (Step 6 says it is for the chunker, M8): **skip Step 6 in M3** (YAGNI).
- Packages: `app.epubreader.core.epub.markup`. The M2 code has no conflicting names (check with `grep -r "object Paths\|MarkupScanner" core/`).
- Verify: `./gradlew :core:epub:jvmTest` (all old + new tests green).
- Commit: `core: markup foundation (decoding, entities, lenient scanner, tree, rewriter, paths)`.

---

### Task 3.2: Package loading and failure classes (parked Tasks 2.3 + 2.9 Step 1)

**Files:** parked Task 2.3 files (`pkg/BookPackage.kt, ContainerParser.kt, OpfParser.kt, PackageLoader.kt`,
`pkg/PackageLoaderTest.kt`) and `core/epub/src/jvmTest/.../CorpusTest.kt` (parked 2.9 Step 1).

**Interfaces (Produces):** as parked Task 2.3, with `BookPackage.toc` **removed** (no TOC parser; the reader's TOC
is used) and `WarningKind.TOC_MISSING_TARGET`, `NAV_UNREADABLE` removed. `PackageLoader.open(source)` returns
`Pair<ZipReader?, LoadResult>`; it maps `ZipFormatException(NOT_A_ZIP)` → `NOT_A_ZIP`, other `ZipFormatException`
→ `DAMAGED_ARCHIVE`. `object ExpectedCorpus { val failures: Map<String, FailReason> }` lives in `CorpusTest.kt`
and is copied by Task 3.5's tests.

Changes against the parked code:
- Drop all `TocParser` wiring and TOC tests; an empty/absent spine **or** a spine where no item is present → `NOTHING_READABLE`.
- **Identifier rule (Review Focus 1):** in `OpfParser`, replace `?.text()?.trim()` for `uniqueId` with
  `?.text()?.let { WS.replace(it, " ").trim() }?.takeIf { it.isNotEmpty() }` where `WS = Regex("\\s+")` — the rule
  foliate-js uses and that `BookIdentity.normalize` repeats. Add to `PackageLoaderTest`:
```kotlin
@Test fun unique_identifier_whitespace_is_collapsed_like_the_reader() {
    val zip = TestZips.epub(opfMetadata = """<dc:identifier id="uid">
        urn:uuid:1234
        \t abcd </dc:identifier>""", uniqueIdentifier = "uid")
    val ok = PackageLoader.load(zip) as LoadResult.Ok
    assertEquals("urn:uuid:1234 abcd", ok.pkg.uniqueId)
}
```
  (adapt to `TestZips`' real helper; add a small OPF-builder helper there if none exists.)
- `CorpusTest` (host): every generated valid book `Ok`; every malformed file its expected class
  (`malformed-not-a-zip` → NOT_A_ZIP, `malformed-truncated` → DAMAGED_ARCHIVE, `malformed-no-container` → NO_PACKAGE,
  `malformed-broken-opf` → BROKEN_PACKAGE, `malformed-empty-spine` → NOTHING_READABLE, folders `fake.epub` →
  NOT_A_ZIP; `malformed-missing-files-bad-xml` → `Ok` with `MISSING_FILE`/`UNKNOWN_IDREF`/`DANGLING_COVER` warnings);
  when `Corpus.full` is present, every Gutenberg and IDPF book `Ok`. Time budget assertion: none (C-015).
- Verify: `./gradlew :core:epub:jvmTest`. Commit: `core: package loader with failure classes; corpus classification`.

---

### Task 3.3: Metadata, cover locator, identity agreement (parked Task 2.5)

**Files:** parked Task 2.5 (`meta/MetadataReader.kt`, `meta/CoverLocator.kt`, `PackageLoader.kt` wiring,
`meta/MetadataTest.kt`); `BookIdentity.kt` and `IdentityTest.kt` exist (keep; add cases). New:
`app/src/androidTest/kotlin/app/epubreader/library/IdentityAgreementTest.kt`; main manifest permission.

**Interfaces (Produces):** `MetadataReader.read(opf): BookMetadata`, `MetadataReader.isRtl(text)`,
`MetadataReader.sortKey(title)`; `CoverLocator.locate(zip, pkg): CoverResult(path, danglingReference)`;
`BookPackage.coverPath` and `metadata` filled by `PackageLoader`.

- [ ] Steps 1–3 as parked Task 2.5, minus the `BookIdentity` implementation (exists). Extra tests:
  `MetadataTest.empty_title_is_null` (whitespace-only `dc:title` → `title == null`), `MetadataTest.rtl_hebrew_and_arabic_titles`,
  `IdentityTest.pp_epub2_vs_epub3_are_two_books` (via `PackageLoader` + CD digest on the two corpus files when `Corpus.full` exists; skipped otherwise).
- [ ] **Step 4: Move the storage permission to the main manifest** (needed here for the instrumented test, and by
  Task 3.4): `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage"/>`,
  `READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"`, `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="29"`;
  remove it from `app/src/benchmark/AndroidManifest.xml`.
- [ ] **Step 5: `IdentityAgreementTest` (instrumented, Review Focus 1).** Grants access with
  `UiAutomation.executeShellCommand("appops set app.epubreader MANAGE_EXTERNAL_STORAGE allow")`; skipped
  (`assumeTrue`) if `/sdcard/Books` has no corpus. For every `.epub` under `/sdcard/Books` that `PackageLoader`
  loads `Ok` and that is not fixed-layout: launch `MainActivity`, `openBook(SourceRef.File(path))`, wait (≤ 15 s) for
  the controller's recorded identity (subscribe to `activity.reader.events` for `FromWeb.Opened` of that session and
  compute `BookIdentity.of(opened.identifier, cdDigest)`), and assert it equals
  `BookIdentity.of(pkg.uniqueId, zip.centralDirectoryDigest)`. Collect all mismatches, then fail listing them.
  Run: `bench/push_corpus.sh` (if not pushed), then
  `./gradlew :app:connectedPlayDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.epubreader.library.IdentityAgreementTest` → PASS.
  A mismatch is a bug in `OpfParser`'s identifier choice (unique-identifier attr vs first `dc:identifier`): fix it
  there, never in `BookIdentity` (M2 rows depend on it).
- [ ] Commit: `core: metadata, cover locator; Kotlin identity agrees with the reader's`.

---

### Task 3.4: Storage access onboarding (F-001, F-003)

**Files:**
- Create: `app/src/main/kotlin/app/epubreader/library/StorageAccess.kt`, `ui/src/commonMain/kotlin/app/epubreader/onboarding/OnboardingScreen.kt`
- Modify: `ui/.../ui/Navigator.kt` (`Route.Onboarding`, `Route.Library` replaces `Route.Home`), `ui/.../ui/App.kt`, `ui/.../ui/Copy.kt`, `app/.../MainActivity.kt`
- Test: `app/src/androidTest/kotlin/app/epubreader/library/OnboardingTest.kt`

**Interfaces:**
- Produces: `interface StoragePermissionPort { fun granted(): Boolean; fun request() }` (in `ui`, `app.epubreader.onboarding`);
  `class StorageAccess(activity: ComponentActivity) : StoragePermissionPort` — API 30+: `Environment.isExternalStorageManager()`,
  request = `startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:app.epubreader")))`
  (fallback `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` if the first throws `ActivityNotFoundException`);
  API 26–29: `READ_EXTERNAL_STORAGE` runtime request via `ActivityResultContracts.RequestPermission`.
  `OnboardingScreen(onGrant: () -> Unit, onLeave: () -> Unit, onOpenPicker: () -> Unit)`.
- Consumes (Task 3.6): `ScanController.onAccessGranted()`; until 3.6 exists, a no-op lambda.

- [ ] **Step 1: Failing instrumented test** `OnboardingTest`:
  - `denied_shows_explanation_first`: `appops set app.epubreader MANAGE_EXTERNAL_STORAGE deny`, launch → the text `Copy.onboardingTitle` is displayed (UiDevice `By.text`), no library title.
  - `pregranted_skips_explanation`: `allow`, launch → library title (`Copy.appTitle`) shown, onboarding text absent.
  - `grant_while_running_shows_library_without_restart`: deny, launch, explanation shown; `appops … allow`; send the activity through `onResume` (press HOME, `am start` again) → library shown, same activity instance (`ActivityScenario` state RESUMED, no recreation: compare the instance hash before/after).
  Run → FAIL.
- [ ] **Step 2: Implement.** Start route: `if (access.granted()) Route.Library else Route.Onboarding`, decided in `onCreate` before `setContent` (no flash). `onResume`: if top is Onboarding and `granted()` → replace with Library and call `onAccessGranted()`. Onboarding copy (our own words): title "Find your books", body "To list the EPUB books on this device automatically, the app needs permission to read all files. Books are only read, never uploaded.", buttons "Allow access", "Open a single book" (the picker, F-002 path), "Leave". "Leave" → `finish()`. Opaque `Surface` (blocks touches to the WebView below).
- [ ] **Step 3:** Run `OnboardingTest` → PASS; `./gradlew check`. Commit `app: storage access onboarding`.

---

### Task 3.5: Scanner core (F-010, F-011, F-012, F-015, F-016, R-004, R-010, Review Focus 1–5)

**Files:**
- Create: `core/data/src/commonMain/kotlin/app/epubreader/core/library/{StorageWalker.kt, Scanner.kt, ScanEvent.kt, ScanScope.kt, LibraryBook.kt}`
- Create: `core/data/src/jvmSharedMain/kotlin/app/epubreader/core/library/FileStorageWalker.kt` (add the `jvmShared` hierarchy group to `core/data/build.gradle.kts` exactly as `core/epub` does)
- Create: `core/data/src/commonMain/kotlin/app/epubreader/core/data/ScanFailureEntity.kt`
- Modify: `AppDatabase.kt` (version 3, `AutoMigration(from = 2, to = 3)`, new entity), `LibraryDao.kt`, `LibraryRepository.kt`; new schema `core/data/schemas/.../3.json` (generated)
- Test: `core/data/src/jvmTest/kotlin/app/epubreader/core/library/{ScannerTest.kt, FileWalkerTest.kt, M2LibraryMigrationTest.kt}`; extend `MigrationTest.kt` (2 → 3). `core/data/build.gradle.kts` test task gets the same `dependsOn(":core:epub:generateTestCorpus")` + `corpus.dir`/`fullcorpus.dir` system properties as `core/epub`.

**Interfaces (Produces):**
```kotlin
package app.epubreader.core.library
data class FoundFile(val path: String, val folder: String, val size: Long, val mtimeMs: Long)
/** C-008 seam: the fdroid/play builds use FileStorageWalker; a play-only folder-picker walker can replace it. */
interface StorageWalker { suspend fun walk(scope: ScanScope, emit: suspend (FoundFile) -> Unit) }
sealed interface ScanScope { data object Everywhere : ScanScope; data class Folder(val path: String) : ScanScope }
class FileStorageWalker(private val root: String, private val firstDirs: List<String> = listOf("Books", "Download", "Documents")) : StorageWalker
sealed interface ScanEvent {
    data class Started(val full: Boolean) : ScanEvent
    data class BookAdded(val bookId: String, val path: String) : ScanEvent
    data class Failed(val path: String, val reason: FailReason) : ScanEvent
    data class Complete(val added: Int, val removed: Int, val failed: Int, val files: Int, val ms: Long) : ScanEvent
}
data class ScanState(val running: Boolean = false, val found: Int = 0, val lastComplete: ScanEvent.Complete? = null)
class Scanner(private val db: AppDatabase, private val clock: HybridClock, private val walker: StorageWalker,
              private val open: (String) -> RandomAccessSource = ::openFileSource, private val workers: Int = 4) {
    /** Single pass. Emits events as it goes; prunes only after the walk completed (Review Focus 3). */
    suspend fun scan(scope: ScanScope, onEvent: (ScanEvent) -> Unit = {}): ScanEvent.Complete
}
package app.epubreader.core.data
@Entity(tableName = "scan_failure") data class ScanFailureEntity(@PrimaryKey val path: String, val folder: String,
    val name: String, val reason: String /* FailReason.name */, val size: Long, val mtimeMs: Long, val lastSeenScan: Long)
data class LibraryBook(val id: String, val title: String, val authors: List<String>, val titleDirection: String,
    val coverThumb: String?, val addedAt: Long, val lastOpenedAt: Long?, val sortTitle: String, val sortAuthor: String,
    val path: String /* preferred file: a scan path if any, else the opened one */, val paths: List<String>)
// LibraryRepository additions
fun library(): Flow<List<LibraryBook>>          // not deleted, not hidden; includes picker-only books
fun failures(): Flow<List<ScanFailureEntity>>
suspend fun removeBook(bookId: String)          // tombstone book (deleted = 1, updatedAt), delete its book_file rows
suspend fun setCover(bookId: String, thumbPath: String?)
```
(`LibraryBook` lives in `core.data` next to `RecentBook`; `RecentBook`/`recent()` are removed in Task 3.8 with the
home screen.)

Scanner pipeline (each per-file step on `workers` coroutines on `Dispatchers.IO`, fed by a `Channel<FoundFile>(256)`
that the walker fills while it walks — S3):
1. `scanId = clock.now()`. Load `book_file` rows with `source='scan'` into a map path → (size, mtime, bookId) and the `scan_failure` map once.
2. For each found file: unchanged (path, size, mtime) in `book_file` → `UPDATE lastSeenScan` only (batch at the end); unchanged in `scan_failure` → same; else open → `PackageLoader.open` → `Failed` ⇒ upsert `scan_failure`; `Ok` ⇒ `identity = BookIdentity.of(pkg.uniqueId, zip.centralDirectoryDigest)`; in one transaction: `byIdentity(identity)` → reuse id (un-tombstone if `deleted`, keep `hidden`) else insert `book` (title = metadata.title ?: file name without extension, authors, sort keys from `MetadataReader.sortKey`, language, `titleDirection = if (isRtl(title)) "rtl" else "auto"`, `addedAt = now`); upsert `book_file(path, bookId, source='scan', folder, size, mtime, lastSeenScan=scanId)`; if the path previously pointed to a **different** book, that is a different book at the same path (Review Focus 4): the old book loses this file row. Emit `BookAdded` only for files that were not unchanged. Close the source always.
3. After the walk **completed** (not cancelled): delete `book_file` rows with `source='scan'` and `lastSeenScan < scanId` whose path is inside the scope; delete `scan_failure` rows not seen; tombstone `book` rows that have no `book_file` rows left, unless `hidden` (M7) — rows with an `opened` file (picker/open-with) keep the book. Reading data is keyed by `book.id` and is never deleted here.
4. Scope `Folder(p)`: the walker walks only `p`; pruning applies only to paths under `p` (books outside are kept, F-011 fallback).

`FileStorageWalker`: `java.io.File.listFiles()` recursion with an explicit stack; first the `firstDirs` subtrees, then the rest of the root (skipping the already-walked ones); 4 parallel top-level walkers are optional (S3: 0.3 s total without them on 4 workers) — keep it single-threaded unless P-050.first fails. Matches `name.endsWith(".epub", ignoreCase = true)` for regular files; includes dot-folders and `.nomedia` folders; `listFiles()` returning null (unreadable) → skip; tracks visited canonical paths to stop symlink loops; never `Files.walk`.

- [ ] **Step 1: Failing tests.** Fixture builder in `ScannerTest`: copies generated folder books (`Corpus.folder(...)`) into a `@TempDir`-like `Files.createTempDirectory` tree following corpus.md §4 (use names that exist in the Gradle-generated set: download, documents, dup, upper, renamed, moved, fake.epub; build `nested3`, `unicode`, `hidden`, `nomedia`, `androidmedia`, `deletedfolder`, `livetest`, `insidecustom`, `outsidecustom` by adding them to `generateTestCorpus`'s `gen_folder_books.py` name list in `core/epub/build.gradle.kts`). DB = `TestDb` in-memory, clock fake.
  - `folder_set_finds_13_valid_and_reports_fake` (F-010, R-004, R-010): 13 books incl. `.hidden/`, `NoMedia/` (with `.nomedia`), `Android/media/com.example.test/`, `Livres Été 2026/`, `Books/Fiction/Classics/Old/`, `UPPER.EPUB`; one failure `fake.epub` = NOT_A_ZIP; scan completes.
  - `rename_move_delete_add` (F-012 check): position row on `moved` survives the move; renamed → one entry; ToDelete gone (book tombstoned, position row still present but book deleted); livetest present.
  - `duplicate_files_one_book` (F-015): `dup.epub` + `dup-copy.epub` → one `book`, two `book_file` rows.
  - `two_editions_two_books`: when `Corpus.full` exists, P&P EPUB 2 and EPUB 3 → two books.
  - `same_path_different_content`: replace `download.epub` with `documents.epub`'s bytes (different identifier) → old book has no file and is tombstoned; the path's book is the documents book; the old book's position is not attached to the new one.
  - `same_identifier_updated_in_place` (C-009): rewrite the zip with the same OPF but an extra entry (different CD digest!). Note: identity includes the CD digest, so this is a **new identity**. Expected per C-009: same book. Implement: when a scanned path's identity differs from its row's book but the **uniqueId** half (first 16 hex) matches and the path is the same, keep the book id and update `identityKey`. Test asserts same `book.id`, position kept.
  - `unchanged_rescan_does_no_zip_io`: second scan with a counting `open` lambda → 0 opens.
  - `interrupted_scan_resumes` (Review Focus 3): cancel the first scan after the 3rd `BookAdded` (walker that suspends); no row pruned, no duplicates; second scan → full result, each book once.
  - `first_book_before_walk_ends` (S3): a walker that emits one file, then suspends until released; `BookAdded` arrives while the walker is still suspended.
  - `picker_books_survive_scan`: a book recorded via `recordOpened` with a `content://` path that is not on disk → still in `library()` after a scan.
  - `scope_folder_excludes_outside_adds` (F-011): scope `Folder(root/Books)` → `outsidecustom` (in Download) not added; switch to `Everywhere` → added; books outside a folder scope are not pruned.
  - `untitled_book_uses_file_name` (Review Focus 5): book whose `dc:title` is blank → title = file name without extension.
  - `FileWalkerTest.unreadable_and_vanishing_dirs_skipped`: a dir with `setReadable(false)` (skip assertion on root user: `assumeFalse(System.getProperty("user.name") == "root")`) and a dir deleted by a hook during the walk → walk completes, other books found. `.symlink_loop_terminates`: `Files.createSymbolicLink(a/loop, a)` → terminates, book in `a` found once. `.firstDirs_walked_first`: first emitted file is under `Books/` when `Android/media` also has books.
  - `M2LibraryMigrationTest` (Review Focus 1, the user-data requirement): build a **version 2** database file with Room's `MigrationTestHelper` (or by creating the v2 schema with SQL from `schemas/.../2.json`) containing a book recorded the M2 way: `identityKey = BookIdentity.of("\n  urn:uuid:FT-download \t", cdDigest)` where the OPF of the copied `download.epub` has that identifier text **with** the newline/tab (generate the zip in the test via `TestZips` with the same CD as on disk — simplest: write the zip file to the temp tree first, compute its digest with `ZipReader`, then insert the row), `book_file(path='content://com.android.providers.downloads.documents/document/42', source='opened')`, and a `reading_position(cfi='epubcfi(/6/4!/4/2/1:0)')`. Open it with the current `AppDatabase` (AutoMigration 2→3), run `Scanner.scan(Everywhere)` over the tree → exactly one `book` row with the old id, two file rows (opened + scan), the same `reading_position` row (cfi, updatedAt unchanged), `library()` has one entry for it.
  Run `./gradlew :core:data:jvmTest` → FAIL.
- [ ] **Step 2: Schema v3.** Add `ScanFailureEntity`, bump to 3 with `AutoMigration(2, 3)`, build to export `3.json`, extend `MigrationTest` with 2→3 (existing rows intact). Add DAO queries: `scanFiles(): List<BookFileEntity>` (`source='scan'`), `markSeen(paths, scanId)` (chunk to ≤ 900 params), `pruneScan(scanId, prefix)`, `tombstoneOrphans(now)`, failure upsert/prune, `library()` query (books + `GROUP_CONCAT(path, '\n')`; preferred path = a `scan` path if any).
- [ ] **Step 3: Walker, then Scanner** per the pipeline above. Use `Channel` + `coroutineScope { launch(walker); repeat(workers) { launch(worker) } }`; database writes go through Room's suspend DAO (one small transaction per book); `onEvent` is called from worker coroutines (callers must be thread-safe; `ScanController` uses a `MutableStateFlow`).
- [ ] **Step 4:** `./gradlew :core:data:jvmTest` → PASS; `./gradlew check`. Commit `data: streamed scanner, scan failures (schema v3); M2 picker books merge with scanned files`.

---

### Task 3.6: Scan orchestration on Android (F-003, F-012)

**Files:** Create `app/src/main/kotlin/app/epubreader/library/ScanController.kt`; modify `AppGraph.kt`,
`MainActivity.kt`; Test `app/src/test/kotlin/app/epubreader/library/ScanControllerTest.kt` (JVM unit test with a
fake `StorageWalker` and in-memory DB if the Android unit-test classpath allows Room bundled; otherwise put the
single-flight/debounce logic in a pure class `ScanScheduler` in `core/data` commonMain and test it in jvmTest).

**Interfaces (Produces):**
```kotlin
class ScanController(scanner: Scanner, scope: CoroutineScope, access: () -> Boolean, scopeSetting: suspend () -> ScanScope,
                     probe: (String, String) -> Unit, onBookAdded: (String) -> Unit /* covers, Task 3.7 */) {
    val state: StateFlow<ScanState>
    fun onAccessGranted()   // full scan now
    fun onAppStart()        // incremental scan now, if access
    fun onLibraryResumed()  // incremental, debounced: skipped if a scan completed < 2 s ago
    fun rescan()            // manual
}
```
Single flight: a request while running sets a "rerun after" flag (at most one queued rescan). Probe events
(bench): `scan_started`, `scan_book_added`, `scan_complete` with `ms` and counts. The scan scope setting is read from
`setting` key `library.scope` (`"everywhere"` default or a folder path; the picker UI is M7).

- [ ] Step 1: failing tests: `request_while_running_runs_once_more`, `resume_within_2s_of_complete_is_skipped`, `no_access_no_scan`. Step 2: implement, wire into `AppGraph` (`Scanner(db, clock, FileStorageWalker(Environment.getExternalStorageDirectory().path))`) and `MainActivity` (`onAppStart` in `onCreate` after the first frame — never before it, P-001; `onLibraryResumed` from `onResume` when the library is on top; `onAccessGranted` from Task 3.4's hook). Step 3: `./gradlew check`, manual check on the emulator: `appops … allow`, launch, `adb logcat -s EPR` shows `scan_complete`. Commit `app: scan controller (start, resume, manual, after grant)`.

---

### Task 3.7: Covers and thumbnail cache (F-021)

**Files:** Create `app/src/main/kotlin/app/epubreader/library/CoverExtractor.kt`,
`ui/src/commonMain/kotlin/app/epubreader/library/BookCover.kt`; modify `AppGraph.kt` (queue), `ui/build.gradle.kts`
(Coil for androidMain only if needed — prefer loading the file with Coil's `AsyncImage` in commonMain via
`coil-compose`), `docs/licenses.md`. Test `app/src/androidTest/kotlin/app/epubreader/library/CoverExtractorTest.kt`,
`ui/src/commonTest/.../library/PlaceholderTest.kt`.

**Interfaces:** `class CoverExtractor(filesDir: File) { fun extract(bookId: String, path: String): String? }` —
opens the file, `PackageLoader.open`, reads `pkg.coverPath` bytes (`readBytes(limit = 20 MB)`), decodes bounds then
`inSampleSize` so width ≤ 360 px, scales to ≤ 360 wide, writes `filesDir/thumbs/<bookId>.webp`
(`WEBP_LOSSY` quality 80 on API 30+, `WEBP` below), returns the path or null (no cover / decode failure).
Runs on a single-thread queue (`Dispatchers.IO.limitedParallelism(2)`) fed by `ScanController.onBookAdded` and by
books with `coverThumb == null` after each scan; never on the scan path (S3). `LibraryRepository.setCover`.
`@Composable fun BookCover(title: String, thumb: String?, modifier)`: thumbnail via Coil `AsyncImage(model = File(thumb))`
or the placeholder: title text on a colour chosen from an own 8-colour palette by `title.hashCode()`
(`fun placeholderColorIndex(title: String): Int` pure, tested).

- [ ] Step 1 failing tests: `CoverExtractorTest`: War and Peace (when on device) thumb exists, ≤ 60 KB, width ≤ 360; `no-cover.epub` → null; `malformed-missing-files-bad-xml` (dangling cover) → null, no exception. `PlaceholderTest`: stable index for a title, in 0..7. Step 2 implement. Step 3 run both, `./gradlew check`. Commit `app: cover thumbnails off the scan path; placeholders`.

---

### Task 3.8: Library screen (F-020, F-022, F-023, F-016, F-021 RTL, F-028 partial)

**Files:** Create `ui/src/commonMain/kotlin/app/epubreader/library/{LibraryState.kt, LibraryScreen.kt, FailureSheet.kt}`;
delete `ui/.../home/HomeScreen.kt`, `HomeState.kt`, `HomeStateTest.kt` and `RecentBook`/`recent()`/`forgetOpened`
(keep `forget` semantics as "remove book" only if still used by `ReaderScreen.onForget` — replace with
`LibraryState.remove(bookId)`); modify `App.kt`, `Copy.kt`, `MainActivity.kt`, SmokeTest/other instrumented tests that
reference the home screen (`Copy.openBook` stays). Test `ui/src/jvmTest/.../library/LibraryStateTest.kt`,
`app/src/androidTest/.../library/LibraryScreenTest.kt`.

**Interfaces:**
```kotlin
enum class LibraryLayout { GRID, LIST }
enum class LibrarySort { RECENT, ADDED, TITLE, AUTHOR }
data class LibraryUi(val books: List<LibraryBook>, val query: String, val layout: LibraryLayout, val sort: LibrarySort,
                     val scan: ScanState, val failures: List<ScanFailureEntity>, val failureNoticeVisible: Boolean)
class LibraryState(repo: LibraryRepository, settings: SettingsPort, scan: StateFlow<ScanState>, scope: CoroutineScope) {
    val ui: StateFlow<LibraryUi>
    fun setQuery(q: String); fun setLayout(l: LibraryLayout); fun setSort(s: LibrarySort)
    fun dismissFailures(); fun remove(bookId: String)
}
interface SettingsPort { suspend fun get(key: String): String?; suspend fun put(key: String, value: String) } // core.data SettingDao adapter
fun matchRanges(text: String, query: String): List<IntRange>   // case-insensitive substring ranges for emphasis
```
Rules: RECENT = `lastOpenedAt` desc, never-opened after, then `addedAt` desc; ADDED = `addedAt` desc; TITLE =
`sortTitle` (lowercased) asc; AUTHOR = `sortAuthor` asc then title. Search: case-insensitive substring over title
and each author; `LibraryUi.books` already filtered and sorted. Layout/sort persisted under `library.layout`,
`library.sort`. Failure notice: visible after a completed scan with `failed > 0` until dismissed; tap → `FailureSheet`
(file name, folder, reason in words: not a zip / damaged archive / no package found / broken package / nothing
readable). Screen: top bar (title, search field, layout toggle, sort menu, overflow with "Rescan" and "Open book");
scan progress as a thin `LinearProgressIndicator` while `scan.running`; grid = `LazyVerticalGrid(Adaptive(112.dp))`
cards (cover 2:3, title ≤ 2 lines, author 1 line, ellipsis); list = rows (48×72 cover, title ≤ 2 lines, author);
`titleDirection == "rtl"` → `TextAlign.End` + `TextDirection.Rtl` (list layout check); missing author → `Copy.unknownAuthor`
("Unknown author"); empty library: text + "Rescan" + "Open book"; empty search: "No books match “q”". Probe event
`library_first_book` the first time a non-empty list is composed (bench P-050.first). All copy is our own.

- [ ] Step 1 failing `LibraryStateTest` (fixed fake dataset of 6 books incl. Moby-Dick by Herman Melville, a Hebrew title, a blank-author book): `ville_finds_moby_dick`, `whale_finds_by_title`, `zzzz_is_empty`, `each_sort_order`, `layout_and_sort_persist` (new `LibraryState` over the same fake settings restores them), `rtl_and_blank_metadata` (Review Focus 5), `matchRanges_case_insensitive`. Run `./gradlew :ui:jvmTest` → FAIL.
- [ ] Step 2 implement state + screen; wire `App.kt` (`Route.Library`) and `MainActivity`; remove the home screen; update instrumented tests to the library (SmokeTest opens via the bench path/`openBook` as before).
- [ ] Step 3 instrumented `LibraryScreenTest`: with corpus pushed and access granted, after `scan_complete` the grid shows "Alice's Adventures in Wonderland"; switch to list, recreate the activity (`scenario.recreate()`) → still list; failure notice visible and its sheet lists `fake.epub` with folder `Download`.
- [ ] Step 4 `./gradlew check`, `tools/smoke.sh`; screenshot grid + list on the emulator for `docs/milestones/M3.md` (`$AD screenshot`). Commit `ui: library grid/list, sort, search, failure report (replaces the recent list)`.

---

### Task 3.9: Open-with, missing file, file changes under the reader (F-013, F-018, R-002, R-008, P-003)

**Files:** modify `app/src/main/AndroidManifest.xml`, `MainActivity.kt`, `ui/.../reader/ReaderScreen.kt` (error
screen "Back" finishes when opened by intent), `ui/.../reader/ReaderController.kt` (`fileGone()`); create
`app/src/main/kotlin/app/epubreader/library/FileWatch.kt`; tests
`app/src/androidTest/.../library/{OpenWithTest.kt, MissingFileTest.kt, FileReplacedUnderReaderTest.kt}`.

**Interfaces:** `ReaderController.fileGone()` — sets `error = ReaderError.FileChanged` (new variant; copy: "This
book's file was changed or removed, so it was closed.") and closes the session safely. `class FileWatch(path: String,
onGone: () -> Unit)` wraps `FileObserver(File, DELETE_SELF or MOVE_SELF or CLOSE_WRITE)` (API 29+ constructor; the
String constructor below), started when a `SourceRef.File` book opens, stopped on close; callbacks are posted to the
main thread. `LibraryState.open(book)`: checks `File(book.path).exists()` (IO, `SourceRef.File` only) before
navigating; missing → `LibraryUi.missing = book` → dialog "File not found" with "Remove from library" /
"Rescan" / "Cancel" (≤ 500 ms: the check is one `stat`).

Manifest intent filters on `MainActivity` (keep `exported`):
```xml
<intent-filter>
  <action android:name="android.intent.action.VIEW" /><category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="content" /><data android:scheme="file" /><data android:mimeType="application/epub+zip" />
</intent-filter>
<intent-filter>
  <action android:name="android.intent.action.VIEW" /><category android:name="android.intent.category.DEFAULT" />
  <data android:scheme="content" /><data android:scheme="file" /><data android:host="*" />
  <data android:mimeType="*/*" />
  <data android:pathPattern=".*\\.epub" /><data android:pathPattern=".*\\..*\\.epub" />
  <data android:pathPattern=".*\\.EPUB" /><data android:pathPattern=".*\\..*\\.EPUB" />
</intent-filter>
```
Activity behaviour: `onCreate`/`onNewIntent` with `ACTION_VIEW` → `viewIntent = true`, route = Reader only (no
onboarding even without access: the URI grant is enough), `openBook(SourceRef.Content(uri) or File(path))`;
`takePersistableUriPermission` attempted (often refused: fine). Back from the reader (or the error screen's button)
with `viewIntent` → `finish()` (returns to the caller; default `standard` launch mode keeps us in the caller's task).
Opening the same file again later resumes (identity, M2 logic). A `content://` VIEW book is recorded as `opened`.

- [ ] Step 1 failing instrumented tests: `OpenWithTest.fileUri` (VIEW `file:///sdcard/Books/gutenberg/frankenstein-epub3.epub`, access granted → `painted_first` event; Back → activity finishing), `.contentUriWithoutPath` (debug `TestBookProvider` URI, Review Focus 2 of the master plan), `.sameFileResumes` (open, `next` ×3, Back, open again → same CFI), `.malformedEachFile` (the 6 malformed corpus files + `fake.epub` via `TestBookProvider`: no crash, error message shown, button finishes the activity); `MissingFileTest` (scan, delete a book file with `File.delete()` (test runs with access), tap its card → dialog ≤ 500 ms; "Remove" → gone from `library()`); `FileReplacedUnderReaderTest` (open a copied book from `/sdcard/Download/m3-tmp/`, overwrite it → message shown, session closed, no crash; same for delete).
- [ ] Step 2 implement. Step 3 run the tests; `./gradlew check`. Commit `app: open-with, missing-file dialog, file changes under the reader`.

---

### Task 3.10: M3 milestone suite, results, review

**Files:** create `bench/groups/scan.sh`; modify `bench/groups/start.sh` (P-003), `bench/push_corpus.sh` (folder set +
14-book set), `bench/README.md` (groups table), `app/src/benchmark/.../BenchCommandReceiver` registration of
`rescan`; create `docs/milestones/M3.md`.

- [ ] **Step 1: P-003** in `start.sh`, after P-002: 3 runs of warm open-with — app in background on the library
  (`am start -n` then HOME), then `am start -W -a android.intent.action.VIEW -t application/epub+zip -d file:///sdcard/Books/gutenberg/alice-epub3-images.epub -n "$PKG/$ACT"` → TotalTime (the activity draws the reader frame + progress in its first frame). Back between runs.
- [ ] **Step 2: `scan.sh`** (3 runs each, events from `adb logcat -s EPR` via `lib.sh` helpers used by `open.sh`):
  - P-050 / P-050.first: `pm clear $PKG`; `appops set $PKG MANAGE_EXTERNAL_STORAGE allow`; corpus + folder set already on storage (58 files per performance.md; remove leftovers `livetest`/14-book set first); `am start -W`; P-050 = `scan_started` → `scan_complete` ms; P-050.first = `scan_started` → `library_first_book`.
  - P-051: `am broadcast … rescan` with nothing changed → `scan_complete.ms`.
  - P-052: push 14 small generated books into `/sdcard/Download/Batch14/` (`python3 -I tools/spec-tools/gen_folder_books.py <tmp> b01 … b14`), `rescan` → ms; remove the folder afterwards (and rescan) so the next run starts equal.
- [ ] **Step 3: Run the suite** on a quiet host (C-024): `$AD close` (device session closed), `./gradlew --stop` after `./gradlew :app:installPlayBenchmark`, then `bench/run_all.sh start open action mem scan` (open/action need `$AD`: if they do, reopen the device session for those groups only and run `start` and `scan` with it closed — two invocations, both recorded). Re-run a failing group once (bench/README.md). P-002 per C-024.
- [ ] **Step 4: `docs/milestones/M3.md`**: table of every Must target in the suite (runs, median, PASS/FAIL), conditions, carry-over fixes (Task 3.0: flake cause and counts), deviations, known gaps (folder-scope picker UI in M7, live file watching = rescan-on-resume, F-017 folder view M7), "Questions for the gatekeeper" (with numbers and a proposed default), screenshots.
- [ ] **Step 5: Whole-branch review** with superpowers:requesting-code-review (base `master`, head `m3`); fix every Critical/Important finding with a pinning test; re-run affected tests and groups; record findings/fixes in M3.md.
- [ ] **Step 6: Hand-off state:** `./gradlew :app:installPlayDebug`, `appops … allow`, corpus + folder set on shared storage, app launched on the library (populated), device session closed. Commit `docs: M3 milestone results`.

---

## Self-review notes

- Spec coverage: F-001 (3.4), F-003 (3.4, 3.6), F-010 (3.5), F-011 (3.5 scanner + setting key; picker UI M7 per master plan), F-012 (3.5, 3.6; live watching = rescan-on-resume, allowed), F-013 (3.9), F-015 (3.5; book-details paths M7 Task 7.6), F-016 (3.2, 3.5, 3.8), F-018 (3.9), F-020/F-022/F-023 (3.8), F-021 (3.3, 3.7, 3.8), F-028 (library reachable; drawer M7), R-001..R-004 (3.2), R-008 (3.9), R-010 (3.5), P-001..P-003, P-050..P-052 (3.10), M2 carry-overs (3.0).
- Deviations from the master plan: Task 3.0 added (carry-overs); parked Task 2.2 Step 6 (serialization) skipped until M8; C-009 "same identifier, changed content" handled by the uniqueId-half match at the same path (identity includes the CD digest).
