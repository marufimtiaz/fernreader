# Third-party licences

All runtime dependencies (everything shipped in the APK) are permissively licensed (Apache-2.0 / MIT / public
domain) and free of proprietary components in the `fdroid` flavor (enforced by `:app:checkFdroidClean`). Test-only
dependencies are not shipped; JUnit (EPL-1.0) is one of them. Versions are those in `gradle/libs.versions.toml`.

## Gradle catalog artifacts

| Artifact | Version | Licence | Used in |
|---|---|---|---|
| androidx.room:room-runtime / room-compiler / room-testing | 2.8.4 | Apache-2.0 | core:data |
| androidx.sqlite:sqlite-bundled | 2.6.2 | Apache-2.0 (bundles SQLite, public domain) | core:data, core:search |
| org.jetbrains.kotlinx:kotlinx-coroutines-core / -android / -test | 1.10.2 | Apache-2.0 | all modules |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.9.0 | Apache-2.0 | core:data, ui, app |
| org.jetbrains.compose (runtime, foundation, material3, components.resources) | 1.10.0 | Apache-2.0 | ui |
| io.coil-kt.coil3:coil-compose (with coil-core, okio: Apache-2.0) | 3.3.0 | Apache-2.0 | ui (library covers from the thumbnail cache) |
| androidx.work:work-runtime-ktx | 2.11.0 | Apache-2.0 | declared in catalog, not yet used |
| androidx.webkit:webkit | 1.15.0 | Apache-2.0 | app |
| androidx.activity:activity-compose | 1.11.0 | Apache-2.0 | app |
| junit:junit | 4.13.2 | EPL-1.0 | tests only (not shipped) |
| androidx.test:runner, androidx.test.ext:junit, androidx.test.uiautomator:uiautomator | 1.7.0 / 1.3.0 / 2.3.0 | Apache-2.0 | instrumented tests |

Build plugins: Android Gradle Plugin 9.1.0 (Apache-2.0), Kotlin 2.4.20 (Apache-2.0), KSP 2.3.4 (Apache-2.0),
Compose Multiplatform plugin 1.10.0 (Apache-2.0), Room Gradle plugin 2.8.4 (Apache-2.0), Gradle wrapper
(Apache-2.0). None of them is shipped.

## npm devDependencies

Build and test tooling for `reader-web/` only; nothing from npm ships in the app except the Vite-bundled host page
code. Versions are pinned by `reader-web/package-lock.json`.

| Package | Range | Licence |
|---|---|---|
| typescript | ^5.6.0 | Apache-2.0 |
| vite | ^6.0.0 | MIT |
| vitest | ^3.0.0 | MIT |
| jsdom | ^25.0.0 | MIT |
| @types/node | ^22 | MIT |

## Vendored sources

foliate-js: MIT, pinned at commit 78914aef4466eb960965702401634c2cb348e9b1 (78914ae), vendored in
`reader-web/vendor/foliate-js/` (licence text in that directory; local changes logged in its `PATCHES.md`).

## Bundled fonts (M4, F-071)

Shipped in the app at `assets/reader/fonts/` (source: `reader-web/src/public/fonts/`), each with its licence text
next to it. Downloaded from the google/fonts repository (`ofl/<family>/`) on 2026-10-31. The SIL Open Font License
1.1 allows bundling with any software; the fonts are not sold on their own and keep their names.

| Font | Files | Licence | Copyright |
|---|---|---|---|
| Literata (variable, roman + italic) | `literata.ttf`, `literata-italic.ttf` | SIL OFL 1.1 (`OFL-Literata.txt`) | 2017 The Literata Project Authors |
| Source Sans 3 (variable, roman + italic) | `source-sans-3.ttf`, `source-sans-3-italic.ttf` | SIL OFL 1.1 (`OFL-SourceSans3.txt`) | 2010-2020 Adobe |
| Atkinson Hyperlegible (regular, italic, bold, bold italic) | `atkinson-*.ttf` | SIL OFL 1.1 (`OFL-AtkinsonHyperlegible.txt`) | 2020 Braille Institute of America, Inc. |
