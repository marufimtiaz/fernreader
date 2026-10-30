# M1 — Foundation and Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A production repository skeleton that builds both flavors, proves the risky toolchain combination
(AGP 9 KMP library plugin + Room KMP + bundled SQLite FTS5 + Compose Multiplatform + Vite-built reader assets),
runs a pre-warmed reader host page that handshakes over the typed bridge, and a benchmark harness that gates
P-001/P-002 on the emulator.

**Architecture:** See master plan "Module map". This milestone creates every module with minimal real content
(no throwaway code): `core:epub` (empty API package), `core:data` (Room DB with the `setting` table), `core:search`
(FTS5 capability check), `ui` (theme + placeholder library), `app` (single activity, ReaderEngine with pre-warm,
flavors, benchmark build type), `reader-web` (host page + bridge v0), `bench/`.

**Tech Stack:** Kotlin 2.4.20, AGP 9.1.0, Gradle 9.3.1, Room KMP, androidx.sqlite bundled, Compose Multiplatform,
kotlinx.serialization, androidx.webkit, TypeScript, Vite, Vitest, Python 3 (stdlib) for the gate.

**Spec:** master plan; performance.md (P-001, P-002, methods); features.md F-092, F-112, F-115; corpus.md.

## Global Constraints

See master plan. Repeated here because tasks rely on them: minSdk 26, compile/target SDK 37, JVM 17; namespace
root `app.epubreader`; flavors `play`/`fdroid` in dimension `distribution`; `fdroid` free of
`com.android.billingclient`, `com.google.android.gms`, `com.google.firebase`, `com.google.android.play`; host page
CSP `script-src 'self'`; commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Vite emitting code that the WebView on API 26 images cannot parse → blank reader on old devices. Pinned: Task 1.3
   sets `build.target = 'es2020'` and `HostPageTest` runs on the emulator (API 37); document the minimum WebView
   (feature `WEB_MESSAGE_LISTENER`) and show an error screen when unsupported (Task 1.3 Step 7).
2. Reader assets stale after editing TS (Gradle up-to-date check misses inputs) → testing old JS. Pinned: Task 1.3
   declares `src/`, `vendor/`, `package-lock.json`, `vite.config.ts` as inputs; Step 9 verifies a rebuild after a change.
3. Unknown bridge message types from a newer JS bundle crash Kotlin. Pinned: `ProtocolFixtureTest.unknown_type_is_ignored`.
4. Emulator timing noise making gates flaky. Pinned: `gate.py` uses medians and a single retry policy is in
   `run_all.sh`; `test_gate.py` covers median/threshold/regression maths.
5. `checkFdroidClean` silently passing because the configuration name changed. Pinned: Task 1.1 Step 6 proves it fails
   with a forbidden dependency.

---

### Task 1.1: Gradle skeleton, flavors, benchmark build type, fdroid check, billing seam

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `gradle/wrapper/*`, `gradlew`, `gradlew.bat` (copied from `prototypes/foliate`, Gradle 9.3.1)
- Create: `core/epub/build.gradle.kts`, `core/data/build.gradle.kts`, `core/search/build.gradle.kts`, `ui/build.gradle.kts`, `app/build.gradle.kts`
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/EpubCore.kt`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/epubreader/billing/BillingGateway.kt`
- Create: `app/src/play/kotlin/app/epubreader/billing/BillingModule.kt`, `app/src/fdroid/kotlin/app/epubreader/billing/BillingModule.kt`
- Create: `app/src/test/kotlin/app/epubreader/billing/BillingModuleTest.kt`
- Create: `app/proguard-rules.pro`, `.gitignore` additions

**Interfaces:**
- Produces: modules `:core:epub`, `:core:data`, `:core:search`, `:ui`, `:app`; variants `playDebug`, `playRelease`,
  `playBenchmark`, `fdroidDebug`, `fdroidRelease`, `fdroidBenchmark`; `interface BillingGateway { val available: Boolean }`;
  `object BillingModule { val gateway: BillingGateway }` (per flavor); task `:app:checkFdroidClean`.

- [ ] **Step 1: Copy the Gradle wrapper**

```bash
cd ~/Projects/epub-reader
cp -r prototypes/foliate/gradle prototypes/foliate/gradlew prototypes/foliate/gradlew.bat .
grep distributionUrl gradle/wrapper/gradle-wrapper.properties   # expect gradle-9.3.1-all.zip
```

- [ ] **Step 2: Write `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`**

```kotlin
// settings.gradle.kts
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "epub-reader"
include(":core:epub", ":core:data", ":core:search", ":ui", ":app")
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
```

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 3: Write `gradle/libs.versions.toml`**

Versions marked † were not verifiable from this repo; if one does not resolve in Step 7, use the newest stable
release of that artifact and record the change in the commit message.

```toml
[versions]
agp = "9.1.0"
kotlin = "2.4.20"
ksp = "2.3.4"                    # † newest KSP compatible with Kotlin 2.4.20
room = "2.8.4"                   # † Room KMP
sqlite = "2.6.2"                 # † androidx.sqlite (bundled driver)
composeMultiplatform = "1.10.0"  # † 
coroutines = "1.10.2"            # †
serialization = "1.9.0"          # †
coil = "3.3.0"                   # †
work = "2.11.0"                  # †
webkit = "1.15.0"
navigationCompose = "2.9.1"      # † org.jetbrains.androidx.navigation
lifecycle = "2.9.6"              # † org.jetbrains.androidx.lifecycle
activityCompose = "1.11.0"       # †
junit = "4.13.2"
androidxTestRunner = "1.7.0"     # †
androidxTestExt = "1.3.0"        # †
uiautomator = "2.3.0"            # †

[libraries]
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
webkit = { module = "androidx.webkit:webkit", version.ref = "webkit" }
navigation-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
junit = { module = "junit:junit", version.ref = "junit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-ext = { module = "androidx.test.ext:junit", version.ref = "androidxTestExt" }
uiautomator = { module = "androidx.test.uiautomator:uiautomator", version.ref = "uiautomator" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-kmp-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

- [ ] **Step 4: Write the core module build files**

`core/epub/build.gradle.kts` (the `jvmShared` source set holds code that uses `java.*` APIs available on both
Android and the JVM, e.g. `RandomAccessFile`, `Inflater`, `MessageDigest`):

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)
    // AGP 9 KMP library DSL. If AGP 9.1 names this block `android { }`, use that name; the content is the same.
    androidLibrary {
        namespace = "app.epubreader.core.epub"
        compileSdk = 37
        minSdk = 26
    }
    jvm()
    sourceSets {
        val jvmShared by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
```

`core/search/build.gradle.kts`: same as `core:epub` with namespace `app.epubreader.core.search`, plus in
`commonMain.dependencies`: `implementation(project(":core:epub"))`, `implementation(libs.sqlite.bundled)`.

`core/data/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvmToolchain(17)
    androidLibrary {
        namespace = "app.epubreader.core.data"
        compileSdk = 37
        minSdk = 26
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:epub"))
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

room { schemaDirectory("$projectDir/schemas") }
```

`ui/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
    androidLibrary {
        namespace = "app.epubreader.ui"
        compileSdk = 37
        minSdk = 26
    }
    jvm() // host tests for view models and the bridge protocol; no desktop app is built
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:epub"))
            implementation(project(":core:data"))
            implementation(project(":core:search"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
```

`core/epub/src/commonMain/kotlin/app/epubreader/core/epub/EpubCore.kt`:

```kotlin
package app.epubreader.core.epub

/** Version of the parsing/extraction rules. Bump when output for the same input changes (invalidates caches). */
const val EPUB_CORE_VERSION = 1
```

- [ ] **Step 5: Write `app/build.gradle.kts`, manifest, billing seam, and its test**

```kotlin
// app/build.gradle.kts
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.epubreader"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.epubreader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("fdroid") { dimension = "distribution" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:epub"))
    implementation(project(":core:data"))
    implementation(project(":core:search"))
    implementation(project(":ui"))
    implementation(libs.webkit)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.uiautomator)
}

/** Fails if the fdroid release runtime classpath contains proprietary Google/billing artifacts (F-112 seam). */
val checkFdroidClean by tasks.registering {
    val forbidden = listOf("com.android.billingclient", "com.google.android.gms", "com.google.firebase", "com.google.android.play")
    val graph = configurations.named("fdroidReleaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    doLast {
        val bad = mutableSetOf<String>()
        fun walk(c: org.gradle.api.artifacts.result.ResolvedComponentResult, seen: MutableSet<Any>) {
            if (!seen.add(c.id)) return
            (c.id as? ModuleComponentIdentifier)?.let { m -> if (forbidden.any { m.group.startsWith(it) }) bad += "${m.group}:${m.module}" }
            c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { walk(it.selected, seen) }
        }
        walk(graph.get(), mutableSetOf())
        if (bad.isNotEmpty()) throw GradleException("fdroid flavor contains proprietary artifacts: $bad")
        println("checkFdroidClean: OK")
    }
}
tasks.named("check") { dependsOn(checkFdroidClean) }
```

```xml
<!-- app/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".EpubReaderApp"
        android:allowBackup="false"
        android:label="EPUB Reader"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
(No `INTERNET` permission: F-092. `EpubReaderApp` and `MainActivity` are created in Task 1.3/1.5.)

```kotlin
// app/src/main/kotlin/app/epubreader/billing/BillingGateway.kt
package app.epubreader.billing

/** Seam for store billing (F-112). The MVP has no billing in any flavor. */
interface BillingGateway {
    val available: Boolean
}
```

```kotlin
// app/src/play/kotlin/app/epubreader/billing/BillingModule.kt
package app.epubreader.billing

/** Play flavor. Real store billing will be implemented here (and only here) after the MVP. */
object BillingModule {
    val gateway: BillingGateway = object : BillingGateway { override val available = false }
}
```

```kotlin
// app/src/fdroid/kotlin/app/epubreader/billing/BillingModule.kt
package app.epubreader.billing

/** F-Droid flavor: never contains proprietary billing libraries. */
object BillingModule {
    val gateway: BillingGateway = object : BillingGateway { override val available = false }
}
```

```kotlin
// app/src/test/kotlin/app/epubreader/billing/BillingModuleTest.kt
package app.epubreader.billing

import org.junit.Assert.assertFalse
import org.junit.Test

class BillingModuleTest {
    @Test fun mvp_has_no_billing_in_any_flavor() = assertFalse(BillingModule.gateway.available)
}
```

`app/proguard-rules.pro`: start with
```
# kotlinx.serialization: keep generated serializers of bridge/backup models
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.epubreader.** { *** Companion; }
-keepclasseswithmembers class app.epubreader.** { kotlinx.serialization.KSerializer serializer(...); }
```

- [ ] **Step 6: Prove `checkFdroidClean` can fail**

Temporarily add to `app/build.gradle.kts` dependencies: `"fdroidImplementation"("com.google.android.gms:play-services-base:18.5.0")`.
Run: `./gradlew :app:checkFdroidClean`
Expected: FAIL with `fdroid flavor contains proprietary artifacts: [com.google.android.gms:play-services-base…]`.
Remove the line again.

- [ ] **Step 7: Build everything**

Run: `./gradlew :app:testPlayDebugUnitTest :app:testFdroidDebugUnitTest :app:assemblePlayBenchmark :app:assembleFdroidRelease :app:checkFdroidClean`
Expected: BUILD SUCCESSFUL; `checkFdroidClean: OK`. (The app will not run yet: MainActivity arrives in Task 1.3. If the
build complains about the missing classes in the manifest, do Step 7 after Task 1.3 Step 6 and note it.)

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat core ui app .gitignore
git commit -m "build: multi-module KMP skeleton with play/fdroid flavors and benchmark build type

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.2: Toolchain proof — Room KMP database and FTS5 capability

**Files:**
- Create: `core/data/src/commonMain/kotlin/app/epubreader/core/data/{AppDatabase.kt, SettingEntity.kt, SettingDao.kt, DatabaseFactory.kt}`
- Create: `core/data/src/jvmMain/kotlin/app/epubreader/core/data/DatabaseFactory.jvm.kt`
- Create: `core/data/src/androidMain/kotlin/app/epubreader/core/data/DatabaseFactory.android.kt`
- Create: `core/data/src/jvmTest/kotlin/app/epubreader/core/data/SettingDaoTest.kt`
- Create: `core/search/src/commonMain/kotlin/app/epubreader/core/search/Fts5Capability.kt`
- Create: `core/search/src/jvmTest/kotlin/app/epubreader/core/search/Fts5CapabilityTest.kt`
- Create: `app/src/androidTest/kotlin/app/epubreader/ToolchainInstrumentedTest.kt`

**Interfaces:**
- Produces: `AppDatabase` (Room, version 1, entity `SettingEntity`), `fun buildDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase`,
  `object Fts5Capability { fun check(dbPath: String): Fts5Report }` with `data class Fts5Report(val sqliteVersion: String, val fts5: Boolean, val diacriticsFolded: Boolean)`.
  Task 3.1 extends `AppDatabase` (version stays 1 until the first release; schema JSON committed).

- [ ] **Step 1: Write the failing JVM tests**

```kotlin
// core/data/src/jvmTest/kotlin/app/epubreader/core/data/SettingDaoTest.kt
package app.epubreader.core.data

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingDaoTest {
    private fun db() = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()))

    @Test fun upsert_then_read_back() = runTest {
        val dao = db().settingDao()
        dao.upsert(SettingEntity(id = "s1", key = "theme", json = "\"dark\"", updatedAt = 10, deleted = false))
        assertEquals("\"dark\"", dao.get("theme")?.json)
    }

    @Test fun tombstoned_setting_is_not_returned() = runTest {
        val dao = db().settingDao()
        dao.upsert(SettingEntity(id = "s1", key = "theme", json = "\"dark\"", updatedAt = 10, deleted = true))
        assertNull(dao.get("theme"))
    }
}
```

```kotlin
// core/search/src/jvmTest/kotlin/app/epubreader/core/search/Fts5CapabilityTest.kt
package app.epubreader.core.search

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class Fts5CapabilityTest {
    @Test fun bundled_sqlite_has_fts5_with_diacritic_folding() {
        val r = Fts5Capability.check(Files.createTempDirectory("fts").resolve("c.db").toString())
        assertTrue(r.fts5, "FTS5 missing in ${r.sqliteVersion}")
        assertTrue(r.diacriticsFolded, "remove_diacritics 2 did not match Borodinó for borodino")
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:data:jvmTest :core:search:jvmTest`
Expected: compilation FAIL (`buildDatabase`, `SettingEntity`, `Fts5Capability` unresolved).

- [ ] **Step 3: Implement the database**

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/SettingEntity.kt
package app.epubreader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user setting. Synced row (F-115): stable id, change timestamp, tombstone. Value is JSON. */
@Entity(tableName = "setting", indices = [Index(value = ["key"], unique = true)])
data class SettingEntity(
    @PrimaryKey val id: String,
    val key: String,
    val json: String,
    val updatedAt: Long,
    val deleted: Boolean,
)
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/SettingDao.kt
package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SettingDao {
    @Upsert suspend fun upsert(row: SettingEntity)
    @Query("SELECT * FROM setting WHERE `key` = :key AND deleted = 0") suspend fun get(key: String): SettingEntity?
}
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/AppDatabase.kt
package app.epubreader.core.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(entities = [SettingEntity::class], version = 1, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingDao(): SettingDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/DatabaseFactory.kt
package app.epubreader.core.data

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** All platforms use the bundled SQLite (same version everywhere, FTS5 available). */
fun buildDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()
```

```kotlin
// core/data/src/jvmMain/kotlin/app/epubreader/core/data/DatabaseFactory.jvm.kt
package app.epubreader.core.data

import androidx.room.Room
import androidx.room.RoomDatabase

fun jvmDatabaseBuilder(path: String): RoomDatabase.Builder<AppDatabase> = Room.databaseBuilder<AppDatabase>(name = path)
```

```kotlin
// core/data/src/androidMain/kotlin/app/epubreader/core/data/DatabaseFactory.android.kt
package app.epubreader.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun androidDatabaseBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {
    val app = context.applicationContext
    return Room.databaseBuilder<AppDatabase>(context = app, name = app.getDatabasePath("library.db").absolutePath)
}
```

- [ ] **Step 4: Implement the FTS5 capability check**

```kotlin
// core/search/src/commonMain/kotlin/app/epubreader/core/search/Fts5Capability.kt
package app.epubreader.core.search

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

data class Fts5Report(val sqliteVersion: String, val fts5: Boolean, val diacriticsFolded: Boolean)

/** Verifies that the bundled SQLite supports what the search index needs (FTS5 + unicode61 remove_diacritics 2). */
object Fts5Capability {
    fun check(dbPath: String): Fts5Report {
        val c = BundledSQLiteDriver().open(dbPath)
        try {
            val version = c.prepare("SELECT sqlite_version()").use { it.step(); it.getText(0) }
            val fts5 = runCatching {
                c.execSQL("DROP TABLE IF EXISTS fts_probe")
                c.execSQL("CREATE VIRTUAL TABLE fts_probe USING fts5(text, tokenize = 'unicode61 remove_diacritics 2')")
            }.isSuccess
            if (!fts5) return Fts5Report(version, false, false)
            c.execSQL("INSERT INTO fts_probe(text) VALUES ('the battle of Borodinó')")
            val folded = c.prepare("SELECT count(*) FROM fts_probe WHERE fts_probe MATCH 'borodino'").use { it.step(); it.getLong(0) == 1L }
            c.execSQL("DROP TABLE fts_probe")
            return Fts5Report(version, true, folded)
        } finally {
            c.close()
        }
    }
}
```

- [ ] **Step 5: Run the JVM tests**

Run: `./gradlew :core:data:jvmTest :core:search:jvmTest`
Expected: PASS (2 + 1 tests). `core/data/schemas/app.epubreader.core.data.AppDatabase/1.json` exists.
If FTS5 is missing: STOP and report to the user — M6 then uses the fallback recorded in S4.

- [ ] **Step 6: Write and run the instrumented toolchain test**

```kotlin
// app/src/androidTest/kotlin/app/epubreader/ToolchainInstrumentedTest.kt
package app.epubreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.core.data.SettingEntity
import app.epubreader.core.data.androidDatabaseBuilder
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.search.Fts5Capability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolchainInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun room_kmp_works_on_device() = runBlocking {
        val db = buildDatabase(androidDatabaseBuilder(ctx))
        db.settingDao().upsert(SettingEntity("t", "probe", "1", 1, false))
        assertEquals("1", db.settingDao().get("probe")?.json)
    }

    @Test fun fts5_available_on_device() {
        val r = Fts5Capability.check(ctx.cacheDir.resolve("fts-probe.db").path)
        assertTrue("FTS5 missing (${r.sqliteVersion})", r.fts5 && r.diacriticsFolded)
    }
}
```
Run (emulator running; device opened via the device tools): `./gradlew :app:connectedPlayDebugAndroidTest`
Expected: 2 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add core/data core/search app/src/androidTest
git commit -m "data/search: Room KMP database and FTS5 capability check (toolchain proof)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.3: reader-web, vendored foliate-js, host page, bridge v0, app shell with pre-warmed reader

**Files:**
- Create: `reader-web/{package.json, tsconfig.json, vite.config.ts, src/index.html, src/main.ts, src/bridge/protocol.ts, src/bridge/bridge.ts, test/bridge.test.ts}`
- Create: `reader-web/vendor/foliate-js/` (copy of the prototype's vendored files) + `reader-web/vendor/foliate-js/PATCHES.md`
- Create: `bridge/fixtures/{ready.json, ping.json, pong.json, unknown.json}`
- Create: `ui/src/commonMain/kotlin/app/epubreader/reader/bridge/Protocol.kt`
- Create: `ui/src/jvmTest/kotlin/app/epubreader/reader/bridge/ProtocolFixtureTest.kt`
- Create: `ui/src/commonMain/kotlin/app/epubreader/ui/{App.kt, theme/Theme.kt}`
- Create: `app/src/main/kotlin/app/epubreader/{EpubReaderApp.kt, MainActivity.kt}`, `app/src/main/kotlin/app/epubreader/reader/ReaderEngine.kt`
- Create: `app/src/androidTest/kotlin/app/epubreader/HostPageTest.kt`
- Modify: `app/build.gradle.kts` (reader asset generation)
- Create: `docs/bridge-protocol.md`

**Interfaces:**
- Produces (Kotlin, package `app.epubreader.reader.bridge`): `sealed interface ToWeb`, `sealed interface FromWeb`,
  `val BridgeJson: Json`, `fun decodeFromWeb(text: String): FromWeb?` (null for unknown/invalid), `fun encodeToWeb(m: ToWeb): String`.
- Produces (TS): `type ToWeb`, `type FromWeb`, `class Bridge { send(m: FromWeb); onMessage(h) }`, `window.__reader.receive(json: string)`.
- Produces (Android): `class ReaderEngine(context) { val webView: WebView; val events: SharedFlow<FromWeb>; fun prewarm(); fun send(m: ToWeb) }`.
- Asset URL: `https://appassets.androidplatform.net/assets/reader/index.html`.

- [ ] **Step 1: Create the reader-web project**

```json
// reader-web/package.json
{
  "name": "reader-web",
  "private": true,
  "type": "module",
  "scripts": { "build": "tsc --noEmit && vite build", "test": "vitest run" },
  "devDependencies": { "typescript": "^5.6.0", "vite": "^6.0.0", "vitest": "^3.0.0", "jsdom": "^25.0.0" }
}
```

```json
// reader-web/tsconfig.json
{
  "compilerOptions": {
    "target": "ES2020", "module": "ESNext", "moduleResolution": "Bundler", "strict": true,
    "allowJs": true, "checkJs": false, "noEmit": true, "lib": ["ES2022", "DOM", "DOM.Iterable"], "skipLibCheck": true
  },
  "include": ["src", "test", "vendor"]
}
```

```ts
// reader-web/vite.config.ts
import { defineConfig } from 'vite'

export default defineConfig({
  root: 'src',
  base: './',
  build: {
    outDir: '../dist',
    emptyOutDir: true,
    target: 'es2020',
    modulePreload: false,
    rollupOptions: {
      // foliate-js lazily imports archive/PDF helpers we never ship; books come from our own loader.
      external: [/vendor\/zip\.js$/, /vendor\/fflate\.js$/, /pdf\.js$/, /vendor\/pdfjs\//],
    },
  },
  test: { root: '.', environment: 'jsdom', include: ['test/**/*.test.ts'] },
})
```
Then `cd reader-web && npm install` (creates `package-lock.json`; commit it).

- [ ] **Step 2: Vendor foliate-js and start the patch log**

```bash
mkdir -p reader-web/vendor
cp -r prototypes/foliate/app/src/main/assets/foliate-js reader-web/vendor/foliate-js
cat > reader-web/vendor/foliate-js/PATCHES.md <<'EOF'
# foliate-js vendored copy

Upstream: https://github.com/johnfactotum/foliate-js, commit 78914aef4466eb960965702401634c2cb348e9b1 (2026-05-01), MIT.
Only first-party modules are vendored (view, epub, epubcfi, paginator, fixed-layout, progress, overlayer, search,
text-walker, footnotes). Every local change is listed below with the reason and the commit that made it.

| Date | File | Change | Reason |
|---|---|---|---|
EOF
```

- [ ] **Step 3: Write the shared fixtures and the failing TS test**

```json
// bridge/fixtures/ready.json
{"v":1,"type":"ready","engine":"foliate-js@78914ae"}
```
```json
// bridge/fixtures/ping.json
{"v":1,"type":"ping","id":7}
```
```json
// bridge/fixtures/pong.json
{"v":1,"type":"pong","id":7}
```
```json
// bridge/fixtures/unknown.json
{"v":1,"type":"from_the_future","x":1}
```

```ts
// reader-web/test/bridge.test.ts
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { Bridge } from '../src/bridge/bridge'

const fixture = (n: string) => readFileSync(new URL(`../../bridge/fixtures/${n}.json`, import.meta.url), 'utf8').trim()

describe('Bridge', () => {
  it('answers ping with pong of the same id', () => {
    const post = vi.fn()
    const b = new Bridge(post)
    b.receive(fixture('ping'))
    expect(JSON.parse(post.mock.calls[0][0])).toEqual(JSON.parse(fixture('pong')))
  })
  it('serializes ready exactly like the fixture', () => {
    const post = vi.fn()
    new Bridge(post).send({ v: 1, type: 'ready', engine: 'foliate-js@78914ae' })
    expect(JSON.parse(post.mock.calls[0][0])).toEqual(JSON.parse(fixture('ready')))
  })
  it('ignores unknown message types without throwing', () => {
    const post = vi.fn()
    expect(() => new Bridge(post).receive(fixture('unknown'))).not.toThrow()
    expect(post).not.toHaveBeenCalled()
  })
})
```
Run: `cd reader-web && npx vitest run` → Expected: FAIL (cannot resolve `../src/bridge/bridge`).

- [ ] **Step 4: Implement protocol, bridge and host entry**

```ts
// reader-web/src/bridge/protocol.ts
export const PROTOCOL_VERSION = 1
export type ToWeb = { v: 1; type: 'ping'; id: number }
export type FromWeb =
  | { v: 1; type: 'ready'; engine: string }
  | { v: 1; type: 'pong'; id: number }
  | { v: 1; type: 'error'; kind: string; message: string }
```

```ts
// reader-web/src/bridge/bridge.ts
import type { FromWeb, ToWeb } from './protocol'

export type Post = (json: string) => void

/** Typed JSON bridge. Kotlin → JS: window.__reader.receive(json). JS → Kotlin: ReaderBridge.postMessage(json). */
export class Bridge {
  private handlers: Array<(m: ToWeb) => void> = []
  constructor(private post: Post) {}

  send(m: FromWeb) { this.post(JSON.stringify(m)) }

  onMessage(h: (m: ToWeb) => void) { this.handlers.push(h) }

  receive(json: string) {
    let m: { v?: number; type?: string; [k: string]: unknown }
    try { m = JSON.parse(json) } catch { return }
    switch (m.type) {
      case 'ping':
        this.send({ v: 1, type: 'pong', id: Number(m.id) })
        return
      default:
        if (!['ping'].includes(String(m.type))) { console.warn('bridge: ignoring unknown message', m.type); return }
    }
    for (const h of this.handlers) h(m as ToWeb)
  }
}
```

```html
<!-- reader-web/src/index.html -->
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<!-- Book content loads into blob: frames that inherit this policy, so book scripts never run (R-007). -->
<meta http-equiv="Content-Security-Policy" content="default-src 'self' blob: data:; script-src 'self'; style-src 'self' 'unsafe-inline' blob: data:; img-src 'self' blob: data:; font-src 'self' blob: data:; media-src 'self' blob: data:; frame-src blob: 'self'; connect-src 'self' blob: data:">
<style>html, body { margin: 0; height: 100%; overflow: hidden; background: transparent; }</style>
<script type="module" src="./main.ts"></script>
</head>
<body></body>
</html>
```

```ts
// reader-web/src/main.ts
import '../vendor/foliate-js/view.js' // registers <foliate-view>; warms the module graph before any book is opened
import { Bridge } from './bridge/bridge'

declare global {
  interface Window {
    ReaderBridge?: { postMessage(s: string): void }
    __reader: { receive(json: string): void }
  }
}

const post = (json: string) => window.ReaderBridge?.postMessage(json)
const bridge = new Bridge(post)
window.__reader = { receive: json => bridge.receive(json) }
bridge.send({ v: 1, type: 'ready', engine: 'foliate-js@78914ae' })
```
Run: `cd reader-web && npx vitest run && npm run build`
Expected: 3 tests PASS; `reader-web/dist/index.html` and `dist/assets/*.js` exist. Add `reader-web/dist/` and
`reader-web/node_modules/` to `.gitignore`.

- [ ] **Step 5: Kotlin protocol with fixture test**

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/reader/bridge/Protocol.kt
package app.epubreader.reader.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kotlin side of the reader bridge. Mirrors reader-web/src/bridge/protocol.ts; both are tested against bridge/fixtures. */
@Serializable sealed interface ToWeb {
    @Serializable @SerialName("ping") data class Ping(val v: Int = 1, val id: Int) : ToWeb
}

@Serializable sealed interface FromWeb {
    @Serializable @SerialName("ready") data class Ready(val v: Int = 1, val engine: String) : FromWeb
    @Serializable @SerialName("pong") data class Pong(val v: Int = 1, val id: Int) : FromWeb
    @Serializable @SerialName("error") data class Error(val v: Int = 1, val kind: String, val message: String) : FromWeb
}

val BridgeJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }

/** Returns null for malformed JSON or a message type this app version does not know (never throws). */
fun decodeFromWeb(text: String): FromWeb? = runCatching { BridgeJson.decodeFromString(FromWeb.serializer(), text) }.getOrNull()

fun encodeToWeb(m: ToWeb): String = BridgeJson.encodeToString(ToWeb.serializer(), m)
```

```kotlin
// ui/src/jvmTest/kotlin/app/epubreader/reader/bridge/ProtocolFixtureTest.kt
package app.epubreader.reader.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolFixtureTest {
    private fun fixture(n: String) = File("../bridge/fixtures/$n.json").readText().trim()
    private fun tree(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test fun ready_decodes() = assertEquals(FromWeb.Ready(engine = "foliate-js@78914ae"), decodeFromWeb(fixture("ready")))
    @Test fun pong_decodes() = assertEquals(FromWeb.Pong(id = 7), decodeFromWeb(fixture("pong")))
    @Test fun ping_encodes_like_fixture() = assertEquals(tree(fixture("ping")), tree(encodeToWeb(ToWeb.Ping(id = 7))))
    @Test fun unknown_type_is_ignored() = assertNull(decodeFromWeb(fixture("unknown")))
}
```
Run: `./gradlew :ui:jvmTest` → Expected: 4 tests PASS (write the test first and see it fail to compile, then add `Protocol.kt`).

- [ ] **Step 6: Wire reader assets into the app and add the app shell**

Append to `app/build.gradle.kts`:
```kotlin
val readerWebDir = rootProject.layout.projectDirectory.dir("reader-web")

val buildReaderWeb by tasks.registering(Exec::class) {
    workingDir = readerWebDir.asFile
    commandLine("bash", "-c", "npm ci --no-audit --no-fund && npm run build")
    inputs.dir(readerWebDir.dir("src"))
    inputs.dir(readerWebDir.dir("vendor"))
    inputs.file(readerWebDir.file("package-lock.json"))
    inputs.file(readerWebDir.file("vite.config.ts"))
    inputs.file(readerWebDir.file("tsconfig.json"))
    outputs.dir(readerWebDir.dir("dist"))
}

abstract class CopyReaderAssets : DefaultTask() {
    @get:InputDirectory abstract val from: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        from.get().asFile.copyRecursively(out.resolve("reader"))
    }
}

val copyReaderAssets by tasks.registering(CopyReaderAssets::class) {
    dependsOn(buildReaderWeb)
    from.set(readerWebDir.dir("dist"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyReaderAssets, CopyReaderAssets::outputDir)
    }
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/reader/ReaderEngine.kt
package app.epubreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.epubreader.bench.Probe
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.decodeFromWeb
import app.epubreader.reader.bridge.encodeToWeb
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * Owns the single reader WebView for the activity's lifetime. Created and loaded before any book is chosen
 * (pre-warm), so opening a book does not pay for WebView start-up or JS module loading.
 */
class ReaderEngine(private val context: Context) {
    private var view: WebView? = null
    private val _events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<FromWeb> = _events

    val webView: WebView get() = view ?: create().also { view = it }

    /** Call after the first library frame has been drawn. Main thread. */
    fun prewarm() { webView }

    fun send(m: ToWeb) {
        val js = "window.__reader && window.__reader.receive(${JSONObject.quote(encodeToWeb(m))})"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    fun destroy() { view?.destroy(); view = null }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "WEBVIEW_TOO_OLD" }
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val w = WebView(context)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(r.url)
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.host != ORIGIN_HOST
        }
        WebViewCompat.addWebMessageListener(w, "ReaderBridge", setOf("https://$ORIGIN_HOST")) { _, msg, _, _, _ ->
            val text = msg.data ?: return@addWebMessageListener
            val m = decodeFromWeb(text) ?: run { Probe.ev("bridge_unknown", text.take(80)); return@addWebMessageListener }
            if (m is FromWeb.Ready) Probe.ev("reader_ready")
            _events.tryEmit(m)
        }
        Probe.ev("webview_created")
        w.loadUrl(HOST_URL)
        return w
    }

    companion object {
        const val ORIGIN_HOST = "appassets.androidplatform.net"
        val HOST_URL: String = Uri.parse("https://$ORIGIN_HOST/assets/reader/index.html").toString()
    }
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/bench/Probe.kt
package app.epubreader.bench

import android.os.SystemClock
import android.util.Log

/**
 * Timestamped events for the benchmark harness ("EVT <name> t=<uptimeMs> ..." on logcat tag EPR).
 * Enabled only in the `benchmark` build type (BenchHooks); a no-op elsewhere.
 */
object Probe {
    @Volatile var enabled = false
    fun ev(name: String, extra: String = "") {
        if (enabled) Log.i("EPR", "EVT $name t=${SystemClock.uptimeMillis()} $extra".trimEnd())
    }
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/EpubReaderApp.kt
package app.epubreader

import android.app.Application
import app.epubreader.bench.BenchHooks

class EpubReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BenchHooks.install(this)
    }
}
```
`BenchHooks` is created per build type in Task 1.5 Step 4; until then create
`app/src/{debug,release,benchmark}/kotlin/app/epubreader/bench/BenchHooks.kt` each containing:
```kotlin
package app.epubreader.bench

import android.app.Application

object BenchHooks { fun install(app: Application) {} }
```

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/ui/theme/Theme.kt
package app.epubreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Our own palette (placeholder values until the design pass in M4; never copied from another reader).
private val Light = lightColorScheme(primary = Color(0xFF2F5D50), secondary = Color(0xFF8A6D3B), background = Color(0xFFFBFAF7))
private val Dark = darkColorScheme(primary = Color(0xFF8CC7B4), secondary = Color(0xFFD6B77E), background = Color(0xFF151716))

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
```

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/ui/App.kt
package app.epubreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.epubreader.ui.theme.AppTheme

/** Root composable. M1: placeholder library; real navigation arrives in M3. */
@Composable
fun App(dark: Boolean) = AppTheme(dark) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Your library is empty") }
    }
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/MainActivity.kt
package app.epubreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import app.epubreader.bench.Probe
import app.epubreader.reader.ReaderEngine
import app.epubreader.ui.App

class MainActivity : ComponentActivity() {
    lateinit var reader: ReaderEngine
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = ReaderEngine(this)
        setContent { App(dark = isSystemInDarkTheme()) }
        // Pre-warm after the first frame so it never delays the library (P-001).
        window.decorView.post {
            Probe.ev("first_frame")
            window.decorView.post { reader.prewarm() }
        }
    }

    override fun onDestroy() {
        reader.destroy()
        super.onDestroy()
    }
}
```

- [ ] **Step 7: Instrumented host-page handshake test**

```kotlin
// app/src/androidTest/kotlin/app/epubreader/HostPageTest.kt
package app.epubreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.reader.ReaderEngine
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostPageTest {
    @Test fun host_page_loads_and_answers_ping() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        lateinit var engine: ReaderEngine
        inst.runOnMainSync { engine = ReaderEngine(inst.targetContext).also { it.prewarm() } }
        withTimeout(10_000) { engine.events.filterIsInstance<FromWeb.Ready>().first() }
        engine.send(ToWeb.Ping(id = 42))
        val pong = withTimeout(5_000) { engine.events.filterIsInstance<FromWeb.Pong>().first() }
        assertEquals(42, pong.id)
        inst.runOnMainSync { engine.destroy() }
    }
}
```
Note: `ready` may be emitted before the collector subscribes; `events` has no replay. If the test is flaky for that
reason, give `_events` `replay = 1` for `Ready` only by tracking `val ready = MutableStateFlow(false)` set on Ready, and
wait on that instead (keep the API: add `val isReady: StateFlow<Boolean>`).
Run: `./gradlew :app:connectedPlayDebugAndroidTest` → Expected: PASS.
Also: if `WEBVIEW_TOO_OLD` is thrown on a device, `MainActivity` must show a full-screen message asking the user to
update "Android System WebView" (wrap `prewarm()` in `runCatching`, expose the failure to `App` as a state; the
text lives with the other UI copy). Add this now with a unit-free manual check (not reproducible on API 37).

- [ ] **Step 8: Write `docs/bridge-protocol.md`**

Content: transport (both directions, origin restriction), envelope `{v, type, ...}`, versioning rule (add fields
compatibly; unknown types ignored on both sides; bump `v` only for breaking changes and support N-1), the message
table (v0: ping/pong/ready/error), and the rule that every new message gets a fixture in `bridge/fixtures/` and a
test on both sides.

- [ ] **Step 9: Verify asset rebuilds and run the app**

```bash
./gradlew :app:installPlayDebug
echo "// touch" >> reader-web/src/main.ts && ./gradlew :app:assemblePlayDebug --console=plain | grep -E "buildReaderWeb|copyReaderAssets"
git checkout reader-web/src/main.ts
```
Expected: the second command shows `buildReaderWeb` executed (not UP-TO-DATE). Launch the app through `$AD`
(`$AD open app.epubreader`) and take `device_screenshot`: placeholder library visible.

- [ ] **Step 10: Commit**

```bash
git add reader-web bridge ui app docs/bridge-protocol.md .gitignore
git commit -m "reader: vendored foliate-js, Vite host page with CSP, typed bridge v0, pre-warmed ReaderEngine

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.4: Corpus tooling

**Files:**
- Create: `tools/spec-tools/` (copies of the spec's `gen_stress.py`, `gen_folder_books.py`, `fetch_corpus.sh`, `frame_changes.py`, `summarize_open.sh`, `mem.sh`) + `tools/spec-tools/README.md`
- Create: `tools/corpus.sh`, `bench/push_corpus.sh`
- Modify: `core/epub/build.gradle.kts` (`generateTestCorpus` task, jvmTest system property)

**Interfaces:**
- Produces: `corpus/{gutenberg,epub3-samples,generated,folders,local}` (gitignored); Gradle task
  `:core:epub:generateTestCorpus` writing `core/epub/build/test-corpus/{generated,folders}`; JVM test system property
  `corpus.dir` pointing at it; on-device layout as in corpus.md §4.

- [ ] **Step 1: Copy the spec tools (spec v0.3) with provenance**

```bash
mkdir -p tools/spec-tools
for f in gen_stress.py gen_folder_books.py fetch_corpus.sh frame_changes.py summarize_open.sh mem.sh; do
  cp ~/Projects/epub-reader-spec/tools/$f tools/spec-tools/
done
cat > tools/spec-tools/README.md <<'EOF'
Copied unchanged from ~/Projects/epub-reader-spec/tools (spec v0.3, HANDOFF_LOG 2026-10-30) so builds and the
benchmark harness do not depend on a sibling checkout. Do not edit; re-copy when the spec changes.
EOF
```

- [ ] **Step 2: Write `tools/corpus.sh`**

```bash
#!/usr/bin/env bash
# Build the test corpus into corpus/ (gitignored) with the spec's tools. Downloads are untrusted input.
# Usage: tools/corpus.sh [--no-download]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); T=$ROOT/tools/spec-tools; C=$ROOT/corpus
if [ "${1:-}" != "--no-download" ]; then "$T/fetch_corpus.sh" "$C"; date -u +%F > "$C/DOWNLOAD_DATE"; fi
python3 -I "$T/gen_stress.py" "$C/generated"
python3 -I "$T/gen_folder_books.py" "$C/folders" download documents nested3 unicode hidden nomedia androidmedia \
  dup upper livetest renamed moved deletedfolder outsidecustom insidecustom
printf 'not an epub at all\n' > "$C/folders/fake.epub"
mkdir -p "$C/local" && python3 -I "$ROOT/tools/gen_script_test.py" "$C/local/script-test.epub"
ls "$C"/*/ | head -100
```
Check `tools/gen_script_test.py`'s usage line first and adapt the last call to it.

- [ ] **Step 3: Write `bench/push_corpus.sh` (placement per corpus.md §4)**

```bash
#!/usr/bin/env bash
# Push the corpus and the folder-discovery set to the emulator. Usage: bench/push_corpus.sh [--no-livetest]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); C=$ROOT/corpus; F=$C/folders
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
S=/sdcard
$A shell mkdir -p $S/Books $S/Download "$S/Documents/Books" $S/Books/Fiction/Classics/Old "'$S/Livres Été 2026'" \
  $S/.hidden $S/NoMedia $S/Android/media/com.example.test $S/Books/ToDelete
$A push "$C/gutenberg" "$C/epub3-samples" "$C/generated" "$C/local" $S/Books/ >/dev/null
for f in download dup renamed moved outsidecustom; do $A push "$F/$f.epub" $S/Download/ >/dev/null; done
$A push "$F/fake.epub" $S/Download/fake.epub >/dev/null
$A push "$F/upper.epub" $S/Download/UPPER.EPUB >/dev/null
$A push "$F/documents.epub" "$S/Documents/Books/" >/dev/null
$A push "$F/dup.epub" "$S/Documents/Books/dup-copy.epub" >/dev/null
$A push "$F/nested3.epub" $S/Books/Fiction/Classics/Old/ >/dev/null
$A push "$F/unicode.epub" "$S/Livres Été 2026/" >/dev/null
$A push "$F/hidden.epub" $S/.hidden/ >/dev/null
$A shell touch $S/NoMedia/.nomedia && $A push "$F/nomedia.epub" $S/NoMedia/ >/dev/null
$A push "$F/androidmedia.epub" $S/Android/media/com.example.test/ >/dev/null
$A push "$F/deletedfolder.epub" $S/Books/ToDelete/ >/dev/null
$A push "$F/insidecustom.epub" $S/Books/ >/dev/null
# livetest.epub is pushed later by the F-012 test into Download/Live/.
$A shell "find $S -iname '*.epub' -not -path '*/Android/data/*' | wc -l"
```
Run it. Expected last line: number of corpus EPUBs + 17 placed folder files. Record it in the commit message.

- [ ] **Step 4: Gradle task for the host-test corpus**

Append to `core/epub/build.gradle.kts`:
```kotlin
val testCorpusDir = layout.buildDirectory.dir("test-corpus")
val generateTestCorpus by tasks.registering(Exec::class) {
    val tools = rootProject.layout.projectDirectory.dir("tools/spec-tools").asFile
    inputs.dir(tools)
    outputs.dir(testCorpusDir)
    doFirst { testCorpusDir.get().asFile.mkdirs() }
    commandLine("bash", "-c", """
        set -e
        python3 -I '$tools/gen_stress.py' '${testCorpusDir.get().asFile}/generated'
        python3 -I '$tools/gen_folder_books.py' '${testCorpusDir.get().asFile}/folders' download documents dup upper renamed moved
        printf 'not an epub at all\n' > '${testCorpusDir.get().asFile}/folders/fake.epub'
    """.trimIndent())
}
tasks.withType<Test>().configureEach {
    dependsOn(generateTestCorpus)
    systemProperty("corpus.dir", testCorpusDir.get().asFile.absolutePath)
    // Optional: the full downloaded corpus for tests that need Gutenberg/IDPF books (skipped when absent).
    systemProperty("fullcorpus.dir", rootProject.layout.projectDirectory.dir("corpus").asFile.absolutePath)
}
```
Run: `./gradlew :core:epub:generateTestCorpus && ls core/epub/build/test-corpus/generated`
Expected: the 12 generated books listed in corpus.md §3.

- [ ] **Step 5: Commit**

```bash
git add tools/spec-tools tools/corpus.sh bench/push_corpus.sh core/epub/build.gradle.kts
git commit -m "tools: corpus generation, device placement and host-test corpus task

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.5: Benchmark harness skeleton (P-001, P-002)

**Files:**
- Create: `bench/{lib.sh, targets.json, gate.py, test_gate.py, start.sh, run_all.sh, baseline.json, README.md}`
- Create: `app/src/benchmark/kotlin/app/epubreader/bench/{BenchHooks.kt, BenchCommandReceiver.kt}` (replace the no-op)
- Create: `app/src/benchmark/AndroidManifest.xml`

**Interfaces:**
- Produces: result lines (JSON Lines) `{"id":"P-001","value":812,"unit":"ms","run":1,"note":"..."}`;
  `bench/gate.py RESULTS TARGETS BASELINE` exit 0/1; `bench/run_all.sh <group...>` where group ∈ `start` now
  (later: `open action mem search scan pagetotal jank r005 r006 checks`); bench broadcast action
  `app.epubreader.BENCH` with extras `cmd`, `arg`; logcat tag `EPR` events `EVT <name> t=<ms> k=v…`.
- `targets.json` holds **every** Must target from performance.md now (later milestones only add scripts).

- [ ] **Step 1: Write the failing gate tests**

```python
# bench/test_gate.py  — run: python3 -I -m unittest bench/test_gate.py  (from repo root)
import json, os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gate  # noqa: E402

TARGETS = {"P-001": {"must": 1200, "unit": "ms"}, "P-016": {"must": 0.5, "unit": "ratio"}}

class GateTest(unittest.TestCase):
    def rows(self, *vals, id="P-001"):
        return [{"id": id, "value": v, "run": i + 1} for i, v in enumerate(vals)]

    def test_median_of_odd_and_even(self):
        self.assertEqual(gate.median([3, 1, 2]), 2)
        self.assertEqual(gate.median([4, 1, 3, 2]), 2.5)

    def test_pass_when_median_within_must(self):
        res = gate.evaluate(self.rows(1300, 900, 1000), TARGETS, {})
        self.assertEqual(res["P-001"]["status"], "PASS")

    def test_fail_when_median_above_must(self):
        res = gate.evaluate(self.rows(1300, 1250, 900), TARGETS, {})
        self.assertEqual(res["P-001"]["status"], "FAIL")

    def test_fail_when_fewer_than_three_runs(self):
        res = gate.evaluate(self.rows(100, 100), TARGETS, {})
        self.assertEqual(res["P-001"]["status"], "FAIL")
        self.assertIn("runs", res["P-001"]["reason"])

    def test_regression_against_baseline(self):
        res = gate.evaluate(self.rows(700, 700, 700), TARGETS, {"P-001": 500})
        self.assertEqual(res["P-001"]["status"], "FAIL")      # 700 > 500 * 1.25
        res = gate.evaluate(self.rows(600, 600, 600), TARGETS, {"P-001": 500})
        self.assertEqual(res["P-001"]["status"], "PASS")

    def test_unknown_id_is_reported_not_ignored(self):
        res = gate.evaluate(self.rows(1, 1, 1, id="P-999"), TARGETS, {})
        self.assertEqual(res["P-999"]["status"], "FAIL")

    def test_main_exit_code(self):
        with tempfile.TemporaryDirectory() as d:
            r, t, b = (os.path.join(d, n) for n in ("r.jsonl", "t.json", "b.json"))
            open(r, "w").write("\n".join(json.dumps(x) for x in self.rows(900, 900, 900)))
            json.dump(TARGETS, open(t, "w")); json.dump({}, open(b, "w"))
            self.assertEqual(gate.main([r, t, b]), 0)

if __name__ == "__main__":
    unittest.main()
```
Run: `python3 -I -m unittest bench/test_gate.py` → Expected: FAIL (`No module named 'gate'`).

- [ ] **Step 2: Implement `bench/gate.py`**

```python
#!/usr/bin/env python3
"""Release gate: medians of benchmark results vs performance.md Must targets and the stored baseline.

Usage: python3 -I bench/gate.py RESULTS.jsonl bench/targets.json bench/baseline.json
Every target is an upper bound (ms, MB, ratio, count). A result id fails when: fewer than 3 runs, median > must,
or median > baseline * REGRESSION (when a baseline exists). Exit code 1 if anything fails.
"""
import json
import sys

REGRESSION = 1.25  # performance.md: expect ±20% run-to-run variation


def median(xs):
    s = sorted(xs)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2


def evaluate(rows, targets, baseline):
    by_id = {}
    for r in rows:
        by_id.setdefault(r["id"], []).append(float(r["value"]))
    out = {}
    for id_, vals in sorted(by_id.items()):
        m = median(vals)
        t = targets.get(id_)
        if t is None:
            out[id_] = {"status": "FAIL", "median": m, "reason": "no target defined"}
        elif len(vals) < 3:
            out[id_] = {"status": "FAIL", "median": m, "reason": f"only {len(vals)} runs (need >= 3)"}
        elif m > t["must"]:
            out[id_] = {"status": "FAIL", "median": m, "reason": f"median {m:g} > must {t['must']:g}"}
        elif id_ in baseline and m > baseline[id_] * REGRESSION:
            out[id_] = {"status": "FAIL", "median": m, "reason": f"regression: {m:g} > baseline {baseline[id_]:g} x {REGRESSION}"}
        else:
            out[id_] = {"status": "PASS", "median": m, "reason": ""}
    return out


def main(argv):
    results, targets, baseline = argv
    rows = [json.loads(l) for l in open(results, encoding="utf-8") if l.strip()]
    res = evaluate(rows, json.load(open(targets, encoding="utf-8")), json.load(open(baseline, encoding="utf-8")))
    bad = 0
    for id_, r in res.items():
        print(f"{r['status']:4}  {id_:14} median={r['median']:g}  {r['reason']}")
        bad += r["status"] != "PASS"
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```
Run: `python3 -I -m unittest bench/test_gate.py` → Expected: 7 tests OK.

- [ ] **Step 3: Write `bench/targets.json` (every Must target) and an empty baseline**

```json
{
  "P-001": {"must": 1200, "unit": "ms", "desc": "cold start to library visible (am start -W TotalTime)"},
  "P-002": {"must": 250, "unit": "ms", "desc": "hot start"},
  "P-003": {"must": 320, "unit": "ms", "desc": "open-with intent to reader frame (warm)"},
  "P-010": {"must": 1500, "unit": "ms", "desc": "tap to first page: Alice"},
  "P-011": {"must": 1500, "unit": "ms", "desc": "tap to first page: War and Peace"},
  "P-012": {"must": 2000, "unit": "ms", "desc": "tap to first page: 2000 chapters"},
  "P-013": {"must": 2000, "unit": "ms", "desc": "tap to first page: 200 images"},
  "P-014": {"must": 3000, "unit": "ms", "desc": "tap to first page: huge single chapter"},
  "P-015": {"must": 100, "unit": "ms", "desc": "tap to reader frame + progress indicator"},
  "P-016": {"must": 0.5, "unit": "ratio", "desc": "reopen / first open of the same book (per book, worst)"},
  "P-020": {"must": 100, "unit": "ms", "desc": "text size change to reflowed page"},
  "P-021": {"must": 5000, "unit": "ms", "desc": "layout change to exact book-wide total (War and Peace)"},
  "P-021.estimate_error_pct": {"must": 5, "unit": "pct", "desc": "|estimate - exact| / exact"},
  "P-022": {"must": 900, "unit": "ms", "desc": "rotation, War and Peace"},
  "P-023": {"must": 3000, "unit": "ms", "desc": "rotation, huge chapter"},
  "P-025.blank_frames": {"must": 0, "unit": "count", "desc": "blank/partial frames during in-chapter turns"},
  "P-026": {"must": 400, "unit": "ms", "desc": "chapter-boundary turn"},
  "P-026.blank_frames": {"must": 0, "unit": "count", "desc": "blank frames during boundary turns"},
  "P-027.p90_ratio": {"must": 1.3, "unit": "ratio", "desc": "p90 frame time / Settings baseline p90 (same session)"},
  "P-030": {"must": 700, "unit": "ms", "desc": "TOC jump to Second Epilogue"},
  "P-031": {"must": 200, "unit": "ms", "desc": "TOC panel open, 2000 entries"},
  "P-031.p90_ratio": {"must": 1.3, "unit": "ratio", "desc": "TOC scroll p90 / Settings baseline p90"},
  "P-040": {"must": 500, "unit": "ms", "desc": "search 'the' W&P: first results"},
  "P-041": {"must": 1500, "unit": "ms", "desc": "search 'Borodinó' W&P: complete list"},
  "P-042": {"must": 1200, "unit": "ms", "desc": "search 'zygote' W&P: 0 results shown"},
  "P-043": {"must": 3000, "unit": "ms", "desc": "search 'augmented matrix' Linear Algebra: first results"},
  "P-044.first": {"must": 2000, "unit": "ms", "desc": "search MARKERWORDHUGE: first results"},
  "P-044.all": {"must": 5000, "unit": "ms", "desc": "search MARKERWORDHUGE: all 138"},
  "P-050": {"must": 7000, "unit": "ms", "desc": "first full scan complete"},
  "P-050.first": {"must": 500, "unit": "ms", "desc": "first full scan: first book visible"},
  "P-051": {"must": 300, "unit": "ms", "desc": "rescan, no changes"},
  "P-052": {"must": 1200, "unit": "ms", "desc": "rescan after adding 14 small books"},
  "P-060": {"must": 215, "unit": "MB", "desc": "PSS reading War and Peace"},
  "P-061.steady": {"must": 300, "unit": "MB", "desc": "PSS huge chapter steady"},
  "P-061.peak": {"must": 400, "unit": "MB", "desc": "PSS huge chapter peak while loading"},
  "P-062": {"must": 300, "unit": "MB", "desc": "PSS paging 50 plates of 200-image book"},
  "P-062.growth_pct": {"must": 10, "unit": "pct", "desc": "growth after the first 10 plates"},
  "P-063.p90_ratio": {"must": 1.3, "unit": "ratio", "desc": "1000-book grid scroll p90 / Settings baseline p90"}
}
```
`bench/baseline.json`: `{}` (filled at the end of M3 and updated deliberately with `--update-baseline`).

- [ ] **Step 4: Benchmark-only hooks: Probe on, command receiver**

```kotlin
// app/src/benchmark/kotlin/app/epubreader/bench/BenchHooks.kt
package app.epubreader.bench

import android.app.Application

/** Benchmark build type only: enable Probe events. The receiver is declared in this build type's manifest. */
object BenchHooks {
    fun install(app: Application) { Probe.enabled = true; Probe.ev("app_create") }
}
```

```kotlin
// app/src/benchmark/kotlin/app/epubreader/bench/BenchCommandReceiver.kt
package app.epubreader.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug commands for the harness: adb shell am broadcast -a app.epubreader.BENCH -n app.epubreader/.bench.BenchCommandReceiver
 *   --es cmd <name> [--es arg <value>]. Commands are dispatched to BenchCommands (registered by screens as they appear).
 */
class BenchCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("arg") ?: ""
        Probe.ev("cmd", "$cmd $arg")
        BenchCommands.dispatch(cmd, arg)
    }
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/bench/BenchCommands.kt
package app.epubreader.bench

/** Registry of bench command handlers (screens register while visible). Unused outside the benchmark build. */
object BenchCommands {
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()
    fun register(cmd: String, h: (String) -> Unit) { handlers[cmd] = h }
    fun unregister(cmd: String) { handlers.remove(cmd) }
    fun dispatch(cmd: String, arg: String) { handlers[cmd]?.invoke(arg) ?: Probe.ev("cmd_unknown", cmd) }
}
```

```xml
<!-- app/src/benchmark/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <receiver android:name=".bench.BenchCommandReceiver" android:exported="true">
            <intent-filter><action android:name="app.epubreader.BENCH" /></intent-filter>
        </receiver>
    </application>
</manifest>
```
Delete `app/src/benchmark/kotlin/app/epubreader/bench/BenchHooks.kt`'s no-op version from Task 1.3 (replaced above).

- [ ] **Step 5: Harness scripts**

```bash
# bench/lib.sh — sourced by every bench script
set -u
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
PKG=${PKG:-app.epubreader}
ACT=${ACT:-app.epubreader.MainActivity}
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SPEC_TOOLS=$ROOT/tools/spec-tools
RESULTS=${RESULTS:?run through bench/run_all.sh (sets RESULTS)}

require_ad() { : "${AD:?export AD = the agent-device command returned by the T3 device_open tool (host local, device Medium_Phone)}"; }
emit() { # id value [note]
  printf '{"id":"%s","value":%s,"run":%s,"note":"%s"}\n' "$1" "$2" "${RUN:-0}" "${3:-}" >> "$RESULTS"
  echo "  $1 = $2 ${3:-}"
}
bench_cmd() { $A shell am broadcast -a app.epubreader.BENCH -n "$PKG/.bench.BenchCommandReceiver" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
# ev_delta <from-event> <to-event>: ms between the first <from> and the first following <to> in logcat (tag EPR)
ev_delta() {
  $A logcat -d -s EPR:I | awk -v a="EVT $1 " -v b="EVT $2 " '
    function t() { match($0, /t=[0-9]+/); return substr($0, RSTART+2, RLENGTH-2) }
    index($0, a) && s=="" { s=t() } index($0, b) && s!="" && e=="" { e=t() }
    END { if (s!="" && e!="") print e-s; else print "NA" }'
}
```

```bash
#!/usr/bin/env bash
# bench/start.sh — P-001 cold start, P-002 hot start (performance.md: am start -W TotalTime). 3 runs each.
source "$(dirname "$0")/lib.sh"
for RUN in 1 2 3; do
  $A shell am force-stop "$PKG"; sleep 1
  v=$($A shell am start -W -n "$PKG/$ACT" | awk '/TotalTime/ {print $2}')
  emit P-001 "${v:-999999}"
done
for RUN in 1 2 3; do
  $A shell input keyevent KEYCODE_HOME; sleep 2
  v=$($A shell am start -W -n "$PKG/$ACT" | awk '/TotalTime/ {print $2}')
  emit P-002 "${v:-999999}"
done
```
(`input keyevent HOME` is a measurement step of the project's runner, matching performance.md's method; taps in
later scripts go through `$AD`.)

```bash
#!/usr/bin/env bash
# bench/run_all.sh <group...> [--update-baseline] — runs bench/<group>.sh, then the gate. Groups: start (M1); more per milestone.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"
UPDATE=0; GROUPS=()
for g in "$@"; do [ "$g" = "--update-baseline" ] && UPDATE=1 || GROUPS+=("$g"); done
mkdir -p bench/results
export RESULTS="$ROOT/bench/results/$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short HEAD).jsonl"
: > "$RESULTS"
for g in "${GROUPS[@]}"; do echo "== $g"; bash "bench/$g.sh" || echo "WARN: $g exited non-zero" >&2; done
python3 -I bench/gate.py "$RESULTS" bench/targets.json bench/baseline.json; rc=$?
if [ $UPDATE = 1 ] && [ $rc = 0 ]; then
  python3 -I - "$RESULTS" bench/baseline.json <<'EOF'
import json, sys
sys.path.insert(0, "bench"); import gate
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
base = json.load(open(sys.argv[2]))
ids = {}
for r in rows: ids.setdefault(r["id"], []).append(float(r["value"]))
base.update({k: gate.median(v) for k, v in ids.items()})
json.dump(base, open(sys.argv[2], "w"), indent=2, sort_keys=True)
EOF
fi
echo "results: $RESULTS"; exit $rc
```
Add `bench/results/` to `.gitignore` except a `.gitkeep`; milestone notes copy the relevant gate output.

`bench/README.md`: prerequisites (emulator `Medium_Phone` running; `device_open` → `export AD=...`; benchmark APK
installed: `./gradlew :app:installPlayBenchmark`; corpus pushed), how to run a group, how to update the baseline,
what each group measures (table mapping group → spec IDs, filled in as groups are added).

- [ ] **Step 6: Run the start group on the emulator**

```bash
./gradlew :app:installPlayBenchmark
chmod +x bench/*.sh && bench/run_all.sh start
```
Expected: six `emit` lines, then `PASS  P-001 …` and `PASS  P-002 …`; exit code 0. If `P-001` fails here (empty
app), stop and investigate before building features on top.

- [ ] **Step 7: Commit**

```bash
git add bench app/src/benchmark app/src/main/kotlin/app/epubreader/bench .gitignore
git commit -m "bench: harness skeleton with Must-target gate and start-time group (P-001, P-002)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 8: Milestone note**

Write `docs/milestones/M1.md`: toolchain versions actually resolved, FTS5 report (`sqlite_version`), gate output of
`bench/run_all.sh start`, anything deferred. Commit with the same trailer.
