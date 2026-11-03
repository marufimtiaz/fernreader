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
        applicationId = "app.fernreader" // C-054; Kotlin packages and the namespace stay app.epubreader
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing (docs/release-checklist.md): no key lives in this repo. When the four `fernreader.signing.*`
    // properties are set (in ~/.gradle/gradle.properties, or as FERNREADER_SIGNING_* environment variables), release
    // builds are signed with that keystore; otherwise they stay unsigned (F-Droid signs its own builds).
    val signingProp = { name: String ->
        providers.gradleProperty("fernreader.signing.$name").orElse(providers.environmentVariable("FERNREADER_SIGNING_" + name.uppercase())).orNull
    }
    // Some but not all four set is a mistake (a typo, a missing variable): fail here, not with an unsigned or half-signed release.
    val signing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith(signingProp)
    val missingSigning = signing.filterValues { it == null }.keys
    if (missingSigning.size in 1..3) {
        throw GradleException(
            "Release signing is half configured: set all four fernreader.signing.* properties or none. Missing: " +
                missingSigning.joinToString { "fernreader.signing.$it (FERNREADER_SIGNING_${it.uppercase()})" },
        )
    }
    val releaseKeystore = signing["storeFile"]
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = signing["storePassword"]
            keyAlias = signing["keyAlias"]
            keyPassword = signing["keyPassword"]
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("fdroid") { dimension = "distribution" }
    }
    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
    // C-049: window animations off while instrumented tests run (Gradle runs; `am instrument --no-window-animation`)
    testOptions { animationsDisabled = true }
    // R-009: the instrumented old-backup restore reads the frozen v1 fixtures of core:data (never copied, never edited).
    sourceSets.getByName("androidTest").assets.directories += "../core/data/src/jvmTest/resources/backup"
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
    implementation(libs.coil.compose) // the app configures the image loader for covers (memory, P-060)
    implementation(libs.webkit)
    implementation(libs.work.runtime) // idle/charging job that finishes unfinished search indexes
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

/** F-100 about → licences: the app shows docs/licenses.md, so the list in the app is the audited one. */
abstract class CopyLicences : DefaultTask() {
    @get:InputFile abstract val from: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        from.get().asFile.copyTo(out.resolve("licenses.md"))
    }
}

val copyLicences by tasks.registering(CopyLicences::class) {
    from.set(rootProject.layout.projectDirectory.file("docs/licenses.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyReaderAssets, CopyReaderAssets::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicences, CopyLicences::outputDir)
    }
}
