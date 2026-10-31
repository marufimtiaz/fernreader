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
