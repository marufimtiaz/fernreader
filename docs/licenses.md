# Third-party licences

All dependencies are permissively licensed (Apache-2.0 / MIT) and free of proprietary components in the `fdroid`
flavor (enforced by `:app:checkFdroidClean`). Versions are those in `gradle/libs.versions.toml`.

## Gradle catalog artifacts

| Artifact | Version | Licence | Used in |
|---|---|---|---|
| androidx.room:room-runtime / room-compiler | 2.8.4 | Apache-2.0 | core:data |
| androidx.sqlite:sqlite-bundled | 2.6.2 | Apache-2.0 | core:data, core:search |
| org.jetbrains.kotlinx:kotlinx-coroutines-core / -android / -test | 1.10.2 | Apache-2.0 | all modules |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.9.0 | Apache-2.0 | core:data, ui, app |
| org.jetbrains.compose (runtime, foundation, material3, components.resources) | 1.10.0 | Apache-2.0 | ui |
| io.coil-kt.coil3:coil-compose | 3.3.0 | Apache-2.0 | declared in catalog, not yet used |
| androidx.work:work-runtime-ktx | 2.11.0 | Apache-2.0 | declared in catalog, not yet used |
| androidx.webkit:webkit | 1.15.0 | Apache-2.0 | app |
| androidx.activity:activity-compose | 1.11.0 | Apache-2.0 | app |
| junit:junit | 4.13.2 | EPL-1.0 | tests |
| androidx.test:runner, androidx.test.ext:junit, androidx.test.uiautomator:uiautomator | 1.7.0 / 1.3.0 / 2.3.0 | Apache-2.0 | instrumented tests |

Build plugins: Android Gradle Plugin 9.1.0 (Apache-2.0), Kotlin 2.4.20 (Apache-2.0), KSP 2.3.4 (Apache-2.0),
Compose Multiplatform plugin 1.10.0 (Apache-2.0), Room Gradle plugin 2.8.4 (Apache-2.0).

## npm devDependencies

None yet (added with the host page in a later task).

## Vendored sources

foliate-js: MIT, pinned at 78914ae (added with the host page in a later task).
