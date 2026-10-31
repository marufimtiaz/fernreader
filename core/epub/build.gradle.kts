plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidLibrary {
        namespace = "app.epubreader.core.epub"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common { group("jvmShared") { withJvm(); withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm } } }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

val testCorpusDir = layout.buildDirectory.dir("test-corpus")
val generateTestCorpus by tasks.registering(Exec::class) {
    val tools = rootProject.layout.projectDirectory.dir("tools/spec-tools").asFile
    inputs.dir(tools)
    outputs.dir(testCorpusDir)
    doFirst { testCorpusDir.get().asFile.mkdirs() }
    commandLine("bash", "-c", """
        set -e
        python3 -I '$tools/gen_stress.py' '${testCorpusDir.get().asFile}/generated'
        python3 -I '$tools/gen_folder_books.py' '${testCorpusDir.get().asFile}/folders' download documents dup upper renamed moved nested3 unicode hidden nomedia androidmedia deletedfolder livetest insidecustom outsidecustom
        printf 'not an epub at all\n' > '${testCorpusDir.get().asFile}/folders/fake.epub'
    """.trimIndent())
}
tasks.withType<Test>().configureEach {
    dependsOn(generateTestCorpus)
    systemProperty("corpus.dir", testCorpusDir.get().asFile.absolutePath)
    // Optional: the full downloaded corpus for tests that need Gutenberg/IDPF books (skipped when absent).
    systemProperty("fullcorpus.dir", rootProject.layout.projectDirectory.dir("corpus").asFile.absolutePath)
}
