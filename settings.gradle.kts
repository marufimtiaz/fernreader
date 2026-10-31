pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "epub-reader"
include(":core:epub", ":core:data", ":core:search", ":ui", ":app")
