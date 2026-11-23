package app.fernreader.core.epub.pkg

import app.fernreader.core.epub.markup.Paths
import app.fernreader.core.epub.markup.TextDecoding
import app.fernreader.core.epub.markup.XmlTree
import app.fernreader.core.epub.meta.CoverLocator
import app.fernreader.core.epub.meta.MetadataReader
import app.fernreader.core.epub.zip.RandomAccessSource
import app.fernreader.core.epub.zip.ZipError
import app.fernreader.core.epub.zip.ZipFormatException
import app.fernreader.core.epub.zip.ZipReader

/** Loads and classifies a book (R-001..R-004). Never throws for content problems. */
object PackageLoader {
    /** Opens the zip and loads the package. The returned reader (if any) is owned by the caller. */
    fun open(source: RandomAccessSource): Pair<ZipReader?, LoadResult> {
        val zip = try { ZipReader.open(source) } catch (e: ZipFormatException) {
            return null to LoadResult.Failed(if (e.error == ZipError.NOT_A_ZIP) FailReason.NOT_A_ZIP else FailReason.DAMAGED_ARCHIVE, e.message ?: "")
        }
        return zip to load(zip)
    }

    fun load(zip: ZipReader): LoadResult {
        val warnings = ArrayList<PackageWarning>()
        val opfPath = ContainerParser.opfPath(zip) ?: run {
            val opfs = zip.entries.filter { it.name.endsWith(".opf", ignoreCase = true) }.map { it.name }.sorted()
            if (opfs.isEmpty()) return LoadResult.Failed(FailReason.NO_PACKAGE, "no package document found")
            warnings += PackageWarning(WarningKind.OPF_FOUND_BY_SEARCH, opfs.first())
            opfs.first()
        }
        val opfEntry = zip.entry(opfPath) ?: return LoadResult.Failed(FailReason.NO_PACKAGE, "package document $opfPath is missing")
        val text = try { TextDecoding.decode(zip.readBytes(opfEntry, 8L shl 20)) } catch (e: ZipFormatException) {
            return LoadResult.Failed(FailReason.DAMAGED_ARCHIVE, e.message ?: "")
        }
        val doc = XmlTree.parse(text)
        val root = doc.root?.takeIf { it.localName == "package" }
            ?: return LoadResult.Failed(FailReason.BROKEN_PACKAGE, "package document has no <package> root")
        val opfDir = Paths.dirOf(opfPath)

        val manifest = root.first("manifest")?.elements()?.filter { it.localName == "item" }?.mapNotNull { e ->
            val id = e.attr("id") ?: return@mapNotNull null
            val href = e.attr("href") ?: return@mapNotNull null
            val path = Paths.resolve(opfDir, href)
            ManifestItem(id, path, e.attr("media-type") ?: "", e.attr("properties")?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
                present = zip.entry(path) != null)
        } ?: emptyList()
        val byId = manifest.associateBy { it.id }

        val spineEl = root.first("spine")
        val spine = ArrayList<SpineItem>()
        spineEl?.elements()?.filter { it.localName == "itemref" }?.forEach { ref ->
            val idref = ref.attr("idref") ?: return@forEach
            val item = byId[idref]
            if (item == null) { warnings += PackageWarning(WarningKind.UNKNOWN_IDREF, idref); return@forEach }
            if (!item.present) warnings += PackageWarning(WarningKind.MISSING_FILE, item.path)
            spine += SpineItem(spine.size, idref, item.path, item.mediaType, ref.attr("linear") != "no", item.present)
        }

        if (!doc.wellFormed) {
            // R-002: an unparseable package is fatal unless the lenient tree still gives a usable spine.
            if (spine.none { it.present }) return LoadResult.Failed(FailReason.BROKEN_PACKAGE, doc.errors.first())
            warnings += PackageWarning(WarningKind.PACKAGE_RECOVERED, doc.errors.first())
        }
        if (spine.none { it.present }) return LoadResult.Failed(FailReason.NOTHING_READABLE, "the spine has no readable items")

        val uidAttr = root.attr("unique-identifier")
        val identifiers = root.first("metadata")?.all("identifier")?.toList().orEmpty()
        val uniqueId = (identifiers.firstOrNull { it.attr("id") == uidAttr } ?: identifiers.firstOrNull())?.text()
            ?.let { WS.replace(it, " ").trim() }?.takeIf { it.isNotEmpty() } // the reader's rule (foliate-js), see BookIdentity
        val metas = root.first("metadata")?.all("meta")?.toList().orEmpty()
        val fixed = metas.any { it.attr("property") == "rendition:layout" && it.text().trim() == "pre-paginated" } ||
            metas.any { it.attr("name") == "fixed-layout" && it.attr("content") == "true" }

        val pkg = BookPackage(
            opfPath = opfPath,
            version = root.attr("version") ?: "",
            uniqueId = uniqueId,
            metadata = MetadataReader.read(root),
            manifest = manifest,
            spine = spine,
            pageProgression = spineEl?.attr("page-progression-direction"),
            fixedLayout = fixed,
            coverPath = null,
            opf = root,
        )
        val cover = CoverLocator.locate(zip, pkg)
        cover.danglingReference?.let { warnings += PackageWarning(WarningKind.DANGLING_COVER, it) }
        return LoadResult.Ok(pkg.copy(coverPath = cover.path), warnings)
    }
}

private val WS = Regex("\\s+")
