plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidLibrary {
        namespace = "app.epubreader.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } } // host tests for state holders and the bridge protocol; no desktop app is built
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:epub"))
            implementation(project(":core:data"))
            implementation(project(":core:search"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            implementation(libs.compose.icons.core)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.coil.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
