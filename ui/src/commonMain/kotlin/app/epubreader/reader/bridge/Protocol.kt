package app.epubreader.reader.bridge

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kotlin side of the reader bridge. Mirrors reader-web/src/bridge/protocol.ts; both are tested against bridge/fixtures. */
@Serializable sealed interface ToWeb {
    @Serializable @SerialName("ping") data class Ping(val v: Int = 1, val id: Int) : ToWeb
    @Serializable @SerialName("open") data class Open(val v: Int = 1, val sessionId: String, val position: String? = null, val texts: WebTexts, val settings: WebSettings = WebSettings()) : ToWeb
    @Serializable @SerialName("close") data class Close(val v: Int = 1) : ToWeb
    @Serializable @SerialName("next") data class Next(val v: Int = 1) : ToWeb
    @Serializable @SerialName("prev") data class Prev(val v: Int = 1) : ToWeb
    /** With [id], the web side answers [FromWeb.Seeked] once the jump has landed (or failed). */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("goTo") data class GoTo(
        val v: Int = 1, val target: String, @EncodeDefault(EncodeDefault.Mode.NEVER) val id: Int? = null,
    ) : ToWeb
    /** Display settings, applied live (F-070..F-074, F-031); the flow is already resolved (AUTO → by orientation). */
    @Serializable @SerialName("setSettings") data class SetSettings(val v: Int = 1, val settings: WebSettings) : ToWeb
    /** F-034 slider: seek to a fraction of the book (0..1). */
    @Serializable @SerialName("goToFraction") data class GoToFraction(val v: Int = 1, val fraction: Double) : ToWeb
    /**
     * F-050: jump to a search hit. [offset]/[length] index the section's text (virtual spine, already mapped), [needle] is
     * the hit's text; the web side verifies it there (or finds it nearby) and highlights it until the next jump.
     */
    @Serializable @SerialName("goToText") data class GoToText(val v: Int = 1, val sectionIndex: Int, val offset: Int, val length: Int, val needle: String) : ToWeb
    @Serializable @SerialName("clearSearchHighlight") data class ClearSearchHighlight(val v: Int = 1) : ToWeb
    /** F-042: back to where the last followed link was tapped (sent only while [FromWeb.History.depth] > 0). */
    @Serializable @SerialName("back") data class Back(val v: Int = 1) : ToWeb
    /** F-043: close the footnote popup (system Back). */
    @Serializable @SerialName("closeFootnote") data class CloseFootnote(val v: Int = 1) : ToWeb
    /**
     * F-041: the book's bookmarks as virtual CFIs (sent whenever the list changes, and after `opened`). The web side
     * reports which are on the visible page in each [FromWeb.Relocate] and in a [FromWeb.PageBookmarks] answer.
     */
    @Serializable @SerialName("setBookmarks") data class SetBookmarks(val v: Int = 1, val sessionId: String, val cfis: List<String>) : ToWeb
    /**
     * F-060/F-061: draw a user annotation: [cfi] a virtual range CFI, [color] a HighlightColors id, [style] "highlight" |
     * "underline". The same [id] again changes its drawing. Drawn until the book closes.
     */
    @Serializable @SerialName("addAnnotation") data class AddAnnotation(
        val v: Int = 1, val sessionId: String, val id: String, val cfi: String, val color: String, val style: String,
    ) : ToWeb
    /** F-060: remove the text selection (after a selection menu action). */
    @Serializable @SerialName("clearSelection") data class ClearSelection(val v: Int = 1) : ToWeb
}

@Serializable data class WebSettings(
    val fontScale: Double = 1.0, val fontFamily: String = "publisher", val lineHeight: Double = 1.5, val marginPercent: Int = 5,
    /** "publisher" | "start" | "justify" */ val align: String = "publisher",
    /** "light" | "dark" | "black" | "warm" */ val theme: String = "light",
    /** "paginated" | "scrolled" */ val flow: String = "paginated",
    /** F-032: "auto" (the book's page-progression-direction) | "ltr" | "rtl": which horizontal swipe is "next". */
    val swipeDirection: String = "auto",
    /** F-032: "slide" | "none". */ val pageAnimation: String = "slide",
)

@Serializable data class WebTexts(val missingPart: String)
/** [index]: the spine section the entry points to, -1 when it resolves to none. */
@Serializable data class TocItem(val label: String, val href: String, val depth: Int, val missing: Boolean = false, val index: Int = -1)

@Serializable sealed interface FromWeb {
    @Serializable @SerialName("ready") data class Ready(val v: Int = 1, val engine: String) : FromWeb
    @Serializable @SerialName("pong") data class Pong(val v: Int = 1, val id: Int) : FromWeb
    /** [kind] `hit_not_found` is not fatal: a search hit's text was not in the section, the view went to the section start. */
    @Serializable @SerialName("error") data class Error(val v: Int = 1, val sessionId: String? = null, val kind: String, val message: String) : FromWeb
    @Serializable @SerialName("opened") data class Opened(
        val v: Int = 1, val sessionId: String, val identifier: String? = null, val title: String, val authors: List<String>,
        val dir: String, val fixedLayout: Boolean, val sections: Int, val toc: List<TocItem>,
    ) : FromWeb
    @Serializable @SerialName("relocate") data class Relocate(
        val v: Int = 1, val sessionId: String, val cfi: String, val fraction: Double, val sectionIndex: Int, val tocHref: String? = null,
        /** Paged: 1-based text page within the section and the section's text pages; 0 and 0 in scrolled mode. */
        val page: Int = 0, val pages: Int = 0, val sectionFraction: Double = 0.0,
        /** F-041: the entries of the last [ToWeb.SetBookmarks] that lie on the visible page (same strings); null when none. */
        val bookmarks: List<String>? = null,
    ) : FromWeb
    /** F-041: answer to [ToWeb.SetBookmarks] for the page now visible. */
    @Serializable @SerialName("pageBookmarks") data class PageBookmarks(val v: Int = 1, val sessionId: String, val cfis: List<String>) : FromWeb
    @Serializable @SerialName("painted") data class Painted(val v: Int = 1, val sessionId: String) : FromWeb
    /** Sent after the `relocate` of a [ToWeb.GoTo] that carried an id. */
    @Serializable @SerialName("seeked") data class Seeked(val v: Int = 1, val sessionId: String, val id: Int) : FromWeb
    /**
     * Page counts of one layout generation [gen] (F-034, P-021): [linear] (the counted sections, first message of a
     * generation only), [counts] as [index, pages] pairs, [sample] for the stratified first batch, [done] when every
     * linear section has been counted.
     */
    @Serializable @SerialName("pageCounts") data class PageCounts(
        val v: Int = 1, val sessionId: String, val gen: Int, val linear: List<Int>? = null, val counts: List<List<Int>> = emptyList(),
        val sample: Boolean = false, val done: Boolean = false,
    ) : FromWeb
    @Serializable @SerialName("tap") data class Tap(val v: Int = 1, val sessionId: String, val x: Double, val y: Double) : FromWeb
    /** F-042: entries in the followed-links history after each push or pop; Back pops one while it is > 0. */
    @Serializable @SerialName("history") data class History(val v: Int = 1, val sessionId: String, val depth: Int) : FromWeb
    /** F-043: the footnote popup opened or closed (a tap outside closes it on the web side). */
    @Serializable @SerialName("footnote") data class Footnote(val v: Int = 1, val sessionId: String, val open: Boolean) : FromWeb
    /** F-044: an http(s) or mailto link was tapped (the web side drops every other scheme). */
    @Serializable @SerialName("externalLink") data class ExternalLink(val v: Int = 1, val sessionId: String, val href: String) : FromWeb
    /**
     * F-060: the text selection settled: [text] exactly the document's Selection.toString(), [cfi] its virtual range CFI,
     * [sectionIndex] its (virtual) section. Cleared: [cfi] null, [text] empty, [sectionIndex] -1.
     */
    @Serializable @SerialName("selection") data class Selection(
        val v: Int = 1, val sessionId: String, val text: String = "", val cfi: String? = null, val sectionIndex: Int = -1,
    ) : FromWeb
}

val BridgeJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }

/** Returns null for malformed JSON or a message type this app version does not know (never throws). */
fun decodeFromWeb(text: String): FromWeb? = runCatching { BridgeJson.decodeFromString(FromWeb.serializer(), text) }.getOrNull()

fun encodeToWeb(m: ToWeb): String = BridgeJson.encodeToString(ToWeb.serializer(), m)
