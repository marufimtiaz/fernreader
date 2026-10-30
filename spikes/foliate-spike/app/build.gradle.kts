plugins {
    id("com.android.application")
}

android {
    namespace = "proto.foliate"
    compileSdk = 37
    defaultConfig {
        applicationId = "spike.foliate"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    sourceSets["main"].kotlin.directories += "../../common/kotlin"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1") // SPIKE S4 (Apache-2.0; SQLite public domain)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
}
