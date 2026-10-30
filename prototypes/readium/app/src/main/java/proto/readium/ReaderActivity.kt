package proto.readium

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import proto.common.BaseReaderActivity
import proto.common.Probe
import java.io.File

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : BaseReaderActivity(),
    EpubNavigatorFragment.Listener, EpubNavigatorFragment.PaginationListener {

    private var publication: Publication? = null
    private var nav: EpubNavigatorFragment? = null
    private var positionCount = 0
    private var searchJob: Job? = null
    private val prefs by lazy { getSharedPreferences("positions", MODE_PRIVATE) }

    override fun openBook(path: String) {
        lifecycleScope.launch {
            val pub = withContext(Dispatchers.IO) {
                val http = DefaultHttpClient()
                val retriever = AssetRetriever(contentResolver, http)
                val opener = PublicationOpener(DefaultPublicationParser(
                    this@ReaderActivity, http, retriever, pdfFactory = null))
                val asset = retriever.retrieve(File(path)).getOrElse {
                    return@withContext Result.failure<Publication>(Exception("asset: ${it.message}"))
                }
                val p = opener.open(asset, allowUserInteraction = false).getOrElse {
                    return@withContext Result.failure<Publication>(Exception("open: ${it.message}"))
                }
                Result.success(p)
            }.getOrElse { showError(it.message ?: "unknown error"); return@launch }
            publication = pub
            Probe.ev("pub_opened", "readingOrder=${pub.readingOrder.size} layout=${pub.metadata.layout}")
            if (pub.readingOrder.isEmpty()) { showError("The book has no readable content (empty spine)."); return@launch }
            positionCount = withContext(Dispatchers.IO) { pub.positions().size }
            Probe.ev("positions_ready", "n=$positionCount")
            val saved = prefs.getString(path, null)?.let { Locator.fromJSON(org.json.JSONObject(it)) }
            supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
                initialLocator = saved,
                initialPreferences = EpubPreferences(fontSize = fontScale),
                listener = this@ReaderActivity,
                paginationListener = this@ReaderActivity,
            )
            supportFragmentManager.beginTransaction()
                .replace(content.id, EpubNavigatorFragment::class.java, Bundle(), "nav").commitNow()
            val n = supportFragmentManager.findFragmentByTag("nav") as EpubNavigatorFragment
            nav = n
            Probe.ev("navigator_added")
            n.currentLocator.collectLatest { loc ->
                prefs.edit().putString(path, loc.toJSON().toString()).apply()
            }
        }
    }

    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
        val pos = locator.locations.position ?: 0
        onPageShown("${pageIndex + 1}/$totalPages · pos $pos/$positionCount", locator.href.toString())
    }

    override fun onPageLoaded() { Probe.ev("resource_loaded") }

    override fun onExternalLinkActivated(url: AbsoluteUrl) { Probe.ev("external_link", url.toString()) }

    override fun next() { nav?.goForward(animated = true) }
    override fun prev() { nav?.goBackward(animated = true) }

    override fun applyFontScale(scale: Double) {
        nav?.submitPreferences(EpubPreferences(fontSize = scale))
    }

    override suspend fun toc(): List<TocItem> {
        val out = mutableListOf<TocItem>()
        fun walk(links: List<Link>, depth: Int) {
            for (l in links) { out += TocItem(l.title ?: l.href.toString(), depth, l); walk(l.children, depth + 1) }
        }
        walk(publication?.tableOfContents ?: emptyList(), 0)
        return out
    }

    override fun goTo(key: Any) {
        Probe.ev("goto")
        when (key) {
            is Link -> nav?.go(key)
            is Locator -> nav?.go(key)
        }
    }

    override fun startSearch(query: String, sink: SearchSink) {
        val pub = publication ?: return
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            val it = pub.search(query, SearchService.Options(caseSensitive = false, diacriticSensitive = false))
            if (it == null) { Probe.ev("search_unsupported"); sink.done(); return@launch }
            while (isActive) {
                val page = it.next().getOrElse { Probe.ev("search_error", it.message); null } ?: break
                sink.hits(page.locators.map { l ->
                    Hit(l.title ?: l.href.toString(),
                        "${l.text.before ?: ""}[${l.text.highlight ?: ""}]${l.text.after ?: ""}", l)
                })
            }
            it.close()
            sink.done()
        }
    }

    override fun cancelSearch() { searchJob?.cancel() }
}
