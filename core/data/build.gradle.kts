plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidLibrary {
        namespace = "app.fernreader.core.data"
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
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies { implementation(libs.room.testing) }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

room { schemaDirectory("$projectDir/schemas") }

// Scanner tests build folder trees from the generated corpus (see core/epub generateTestCorpus).
tasks.withType<Test>().configureEach {
    dependsOn(":core:epub:generateTestCorpus")
    systemProperty("corpus.dir", project(":core:epub").layout.buildDirectory.dir("test-corpus").get().asFile.absolutePath)
    systemProperty("fullcorpus.dir", rootProject.layout.projectDirectory.dir("corpus").asFile.absolutePath)
}
