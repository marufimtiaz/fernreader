pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "epub-reader"
include(":core:epub", ":core:data", ":core:search", ":ui", ":app")
include(":devtools:desktop") // dev-only JVM window for the shared UI; no APK depends on it
