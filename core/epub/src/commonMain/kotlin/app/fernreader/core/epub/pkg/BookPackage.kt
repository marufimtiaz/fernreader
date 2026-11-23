package app.fernreader.core.epub.pkg

import app.fernreader.core.epub.markup.XmlElement

enum class FailReason { NOT_A_ZIP, DAMAGED_ARCHIVE, NO_PACKAGE, BROKEN_PACKAGE, NOTHING_READABLE }
enum class WarningKind { MISSING_FILE, UNKNOWN_IDREF, DANGLING_COVER, PACKAGE_RECOVERED, OPF_FOUND_BY_SEARCH }
data class PackageWarning(val kind: WarningKind, val detail: String)

data class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: Set<String>, val present: Boolean)
data class SpineItem(val index: Int, val idref: String, val path: String, val mediaType: String, val linear: Boolean, val present: Boolean)
data class BookMetadata(val title: String?, val authors: List<String>, val language: String?, val titleRtl: Boolean)

data class BookPackage(
    val opfPath: String,
    val version: String,
    val uniqueId: String?,
    val metadata: BookMetadata,
    val manifest: List<ManifestItem>,
    val spine: List<SpineItem>,
    val pageProgression: String?,
    val fixedLayout: Boolean,
    val coverPath: String?,
    val opf: XmlElement,
) {
    val opfDir: String get() = opfPath.substringBeforeLast('/', "")
    fun item(id: String): ManifestItem? = manifest.firstOrNull { it.id == id }
}

sealed interface LoadResult {
    data class Ok(val pkg: BookPackage, val warnings: List<PackageWarning>) : LoadResult
    data class Failed(val reason: FailReason, val detail: String) : LoadResult
}
