// Dev-only: runs the shared Compose UI in a desktop window (docs/DEVELOPMENT.md). Nothing in :app depends on this module.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":core:epub"))
            implementation(project(":core:data"))
            implementation(project(":core:search"))
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
    }
}

compose.desktop {
    application { mainClass = "app.epubreader.devtools.MainKt" }
}

/** Renders the screens to PNGs without a window or a display: `./gradlew :devtools:desktop:screenshots`. */
tasks.register<JavaExec>("screenshots") {
    group = "application"
    val jvm = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvm.compileTaskProvider)
    classpath = files(jvm.output.allOutputs, jvm.runtimeDependencyFiles)
    mainClass.set("app.epubreader.devtools.MainKt")
    jvmArgs("-Djava.awt.headless=true")
    args("--screenshots", layout.buildDirectory.dir("screenshots").get().asFile.path, rootProject.file("docs/licenses.md").path)
}
