# Real-device pass (before the first public release)

One phone over USB/Wi-Fi adb. Required before the first public release (C-010, C-021); the emulator gates don't
cover these. Use the **slowest** phone you have and record its model, refresh rate and Android version (C-060).
About 45 minutes. Commands are for fish (the repo's scripts are bash and are run with `bash`).

The scripted steps live in `bench/device_pass.sh`; each writes to
`bench/results/device-<serial>-<time>/summary.txt` (plus videos, screenshots and raw `gfxinfo`). Copy the summary
into `docs/milestones/M9.md` ("Real-device pass") when done.

## 0. Prepare

1. On the phone: Settings → About phone → tap "Build number" 7 times; Settings → Developer options → USB debugging on.
   Plug in and accept the computer's key. Leave animations at their normal speed (do **not** turn them off).
2. Find the serial and keep it in a variable:
   ```fish
   adb devices -l
   set S <serial from the list>
   ```
3. Build and install the benchmark build (release code with the bench hooks, signed with the debug key):
   ```fish
   ./gradlew :app:assemblePlayBenchmark
   adb -s $S install -r -d app/build/outputs/apk/play/benchmark/app-play-benchmark.apk
   ```
   If an older `app.epubreader` build is on the phone, remove it: `adb -s $S uninstall app.epubreader`.
4. Copy the corpus and scan (adds `/sdcard/Books/{gutenberg,epub3-samples,generated}`, deletes nothing; ~350 MB):
   ```fish
   bash bench/device_pass.sh -s $S setup info
   ```
   Close other apps, keep the phone plugged in, screen brightness mid, no battery saver.

## 1. Scripted checks

```fish
bash bench/device_pass.sh -s $S all
```

runs, in this order (about 15 minutes; don't touch the phone):

| Step | Spec | What it does | Pass when |
|---|---|---|---|
| `info` | C-060 | model, Android version, refresh rate, WebView version | (recorded) |
| `frames` | C-060, P-027 | War and Peace, 30 page swipes, `dumpsys gfxinfo` | p90 frame time ≤ one refresh interval at the phone's native rate: 16.7 ms at 60 Hz, 11.1 ms at 90 Hz, 8.3 ms at 120 Hz |
| `anim` | C-025, P-025 | 5 recorded swipe turns | animation (first change → last change) ≤ 300 ms; 0 blank or partial frames |
| `p020` | C-031, C-066 | text size step with the display panel open, 3 runs | P-020 ≤ 100 ms; record both the video number and command → page painted (the video clock starts at the panel's label change, C-055) |
| `p002` | C-024 | Home, then hot start, 5 runs | median ≤ 250 ms |
| `vertical` | C-046 | Kusamakura (vertical Japanese): screenshots, one swipe each way | columns run right to left, the swipe left goes to the next page, no clipped text; see below |
| `toc` | P-031, C-019 | 2000-entry contents panel: open time, 10 flings | open ≤ 200 ms; scroll p90 ≤ one refresh interval |
| `library` | P-063, C-019 | `seedLibrary 1000`, 20 flings in the grid, then the seeds are removed | p90 ≤ one refresh interval; covers appear while scrolling (screenshots) |
| `return` | C-075, C-076, F-012 | 3 manual rescans with nothing changed; then 3 times: Home, a new book copied into a new folder `Documents/EprReturnN/Inner`, back to the app; copies removed after | the copied book is on top of the library ≤ 2000 ms after the app is back (app events `app_foreground` → `library_top`); record the manual rescan median (on your real storage) |

If the refresh rate shows as empty or wrong (some phones report the panel's maximum while running at 60 Hz),
check Settings → Display → refresh rate and re-run with it: `env REFRESH_HZ=120 bash bench/device_pass.sh -s $S frames`.
On adaptive-rate phones set the display to its fixed highest rate for the run.

## 2. Checks by eye

**C-025 animation.** Open the `anim-*.mp4` videos from the results folder and step through one turn frame by frame
(e.g. `mpv --pause`, then `.`). Every frame shows the old page, the new page or both sliding; never an empty white
page or a half-drawn page.

**C-046 vertical pages.** Look at `vertical-1..3.png`. On a WebView without CSS `column-wrap` support the reader
falls back to top-to-bottom pages with no book total: note the WebView version from `info` if so.

**TOC and library scroll (C-019).** In the app: open *stress-2000-chapters*, open the contents, fling up and down
for ten seconds. Then in the library run `adb -s $S shell am broadcast -a app.fernreader.BENCH -n app.fernreader/app.fernreader.bench.BenchCommandReceiver --es cmd seedLibrary --es arg 1000`,
restart the app, and fling through the grid. No visible stall (a hitch you would notice while reading). Remove the
seeds afterwards with the same command and `--es arg 0`.

**C-075 rescan on return.** With your usual files on the phone: open the app, press Home, copy any EPUB into a new
folder with the phone's file manager (e.g. from Download/ to a new folder in Documents/), and come back through
recents. The book shows at the top of the library within about 2 seconds, without a manual rescan, and its folder is
in the folder list. Then leave and come back once more without changing anything: no progress bar appears. Delete
the copy afterwards.

**C-036 keyboard (only if you have a phone with Android 8–10, API 26–29).** Open War and Peace, open search, type
"Natasha". With the keyboard up, the page behind the search panel must not jump to another position; close the
keyboard and the search: you are on the same page as before. If no such phone exists, write "no API 26–29 device"
in the results (the gatekeeper decides on an emulator image instead, C-036).

**C-070 huge chapter with TalkBack.** Run `bash bench/device_pass.sh -s $S talkback` (needs Google TalkBack on the
phone; it turns TalkBack on, opens the huge chapter and restores your setting afterwards — if it fails midway, turn
TalkBack off with both volume keys held for 3 s). While it is on:
1. In the open huge chapter, swipe right once: TalkBack must read text from the page within about 2 seconds.
2. Double-tap the right edge to turn the page; the new page is read.
3. Press Back: the library appears within a second or two.
If any of these takes more than ~5 seconds or the phone shows "app isn't responding", record it: per C-070,
accessibility on huge chapters then becomes a release blocker.

**Scroll mode (F-031, C-079, C-080; from Track B).**

Use a technical book with front matter and images (e.g. a programming book with cover, title page, copyright,
contents, preface) and War and Peace. In the reader: settings icon → Display → Reading mode → Scroll.

1. Right after switching to Scroll, the text that was on screen is still on screen (no blank screen).
2. Scroll down slowly through the first five sections, then fling several times quickly. The text moves only with
   the finger and the fling's momentum: it never jumps back, never stops at a chapter's end, and the next chapter
   follows without a blank gap beyond the book's own spacing. The page label never goes down while scrolling down.
3. Scroll up from the start of a chapter into the previous one: no jump, the text above appears in place.
4. From about page 100, open Contents and pick an early entry (Introduction, Acknowledgements), then chapter 1,
   then a later chapter. Each time the heading of the picked entry is at the top of the screen and stays there for
   3 s, and the page label shows its page. Repeat with a search result, a bookmark and a highlight from the lists,
   and a link with Back.
5. Rotate while scrolled into the middle of a chapter: the same text stays on screen.

**Page turns across chapters (F-032, C-071, P-025, P-026; from Track B).**

1. In Pages mode turn across a chapter boundary forward and back with a swipe, an edge tap and a volume key: the
   turn slides like a turn inside a chapter, with the next chapter's page fully drawn during the slide (no blank or
   partial frame).
2. In an RTL book the slide goes the other way.
3. With Display → page animation "None", every turn is instant, boundaries included.
4. Swipe twice quickly at a boundary: both turns happen, in order.

**One at a time swipe, Resume (C-092, C-093).**

1. In Scroll with Chapters: One at a time, swipe sideways on the text: the next chapter slides in at its top; swipe back: the
   previous chapter at its end. Vertical scrolling and flings aren't delayed or stolen, including slow drags that start a
   little diagonally. Sideways swipes on a wide table scroll the table.
2. Read a book, go back to the library: the Resume button reopens it at the same place; it doesn't cover the last row.

**P-020 and P-060 (open from the emulator runs).** Record P-020's video and command → painted numbers from the `p020` step. Measure
P-060 once: War and Peace, 20 page turns, wait 35 s, then `env PKG=app.fernreader ADB="adb -s $S" tools/mem_total.sh`; pass at ≤ 215 MB.

## 3. Record the results

In `docs/milestones/M9.md` → "Real-device pass": phone model, Android version, refresh rate, WebView version, the
summary lines, and PASS/FAIL per row of the table above plus the by-eye checks. A FAIL goes to the gatekeeper with
the result folder; the first public release waits for a green pass (C-021).
