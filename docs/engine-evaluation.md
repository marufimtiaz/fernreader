# EPUB rendering engine evaluation

Status: draft — numbers being filled in.

## 1. Candidates, versions, licenses

| Engine | Version used | License | Bundled / transitive pieces (license) | MIT-compatible? |
|---|---|---|---|---|
| Readium Kotlin Toolkit (`readium-shared`, `-streamer`, `-navigator`) | 3.4.0 (released 2026-09-11, Maven Central) | BSD-3-Clause | ReadiumCSS (BSD-3), OpenDyslexic font asset (SIL OFL 1.1), jsoup 1.23.2 (MIT), Guava 33.3.1 (Apache-2.0), Timber 5.0.1 (Apache-2.0), koi-core 0.5.5 (Apache-2.0), AndroidX/Kotlin (Apache-2.0) | Yes. BSD-3 needs its notice in "about/licenses"; OFL font may be bundled but stays OFL (or excluded) |
| foliate-js | git `78914ae` (2026-05-01; the project publishes no releases or tags) | MIT | Only first-party modules used (view, epub, epubcfi, paginator, fixed-layout, progress, overlayer, search, text-walker). Not shipped: `vendor/zip.js` (BSD-3), `vendor/fflate` (MIT), `vendor/pdfjs` (Apache-2.0) | Yes |

No copyleft in either dependency tree.

Toolchain: AGP 9.1.0, Gradle 9.3.1, Kotlin 2.4.20 (Readium 3.4.0 needs a Kotlin 2.4 compiler and core-library
desugaring), compile/target SDK 37, min SDK 26. System WebView on the emulator: 158.0.8127.2.
