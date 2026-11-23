plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    androidLibrary {
        namespace = "app.fernreader.core.search"
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
            implementation(project(":core:epub"))
            implementation(libs.sqlite.bundled)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// Search tests index the generated stress books (see core/epub generateTestCorpus) and, when present, the full corpus.
tasks.withType<Test>().configureEach {
    dependsOn(":core:epub:generateTestCorpus")
    systemProperty("corpus.dir", project(":core:epub").layout.buildDirectory.dir("test-corpus").get().asFile.absolutePath)
    systemProperty("fullcorpus.dir", rootProject.layout.projectDirectory.dir("corpus").asFile.absolutePath)
}
