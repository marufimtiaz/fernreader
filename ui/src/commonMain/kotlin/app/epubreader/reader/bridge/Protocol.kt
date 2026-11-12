package app.epubreader.reader.bridge

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kotlin side of the reader bridge. Mirrors reader-web/src/bridge/protocol.ts; both are tested against bridge/fixtures. */
@Serializable sealed interface ToWeb {
    @Serializable @SerialName("ping") data class Ping(val v: Int = 1, val id: Int) : ToWeb
    /**
     * [continued] (C-085 with M8): the virtual sections that are a later part of a huge document the app split; with
     * the section before they are one chapter (One at a time doesn't stop between them). Null when nothing is split.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("open") data class Open(
        val v: Int = 1, val sessionId: String, val position: String? = null, val texts: WebTexts, val settings: WebSettings = WebSettings(),
        @EncodeDefault(EncodeDefault.Mode.NEVER) val continued: List<Int>? = null,
    ) : ToWeb
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
    /** F-034 slider: seek to a fraction of the book (0..1). With [id], answered by [FromWeb.Seeked] as [GoTo] is. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("goToFraction") data class GoToFraction(
        val v: Int = 1, val fraction: Double, @EncodeDefault(EncodeDefault.Mode.NEVER) val id: Int? = null,
    ) : ToWeb
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
    /**
     * F-061: the book's annotations (virtual CFIs, book order), replacing the web side's set for the session. Sent once
     * per open after the first `painted`; later changes go as [AddAnnotation] / [RemoveAnnotation].
     */
    @Serializable @SerialName("setAnnotations") data class SetAnnotations(val v: Int = 1, val sessionId: String, val items: List<WebAnnotation>) : ToWeb
    /** F-061: the annotation [id] is gone: its drawing is erased at once. */
    @Serializable @SerialName("removeAnnotation") data class RemoveAnnotation(val v: Int = 1, val sessionId: String, val id: String) : ToWeb
    /** C-083 go to page: jump to text [offset] of (virtual) section [sectionIndex], a book page's start. */
    @Serializable @SerialName("goToOffset") data class GoToOffset(val v: Int = 1, val sectionIndex: Int, val offset: Int) : ToWeb
    /** C-083: where these virtual CFIs start in their sections' text; answered by [FromWeb.TextOffsets] with [id]. */
    @Serializable @SerialName("textOffsets") data class TextOffsets(val v: Int = 1, val sessionId: String, val id: Int, val cfis: List<String>) : ToWeb
    /**
     * C-108 (c): count the screen pages of layout generation [gen] (announced by [FromWeb.CountReady]; the web side ignores
     * an older one), or with [run] false stop counting (screen pages are no longer shown).
     */
    @Serializable @SerialName("countPages") data class CountPages(val v: Int = 1, val sessionId: String, val gen: Int, val run: Boolean) : ToWeb
}

/** One annotation as the page draws it (F-061): [cfi] virtual range CFI, [color] a HighlightColors id, [style] "highlight" | "underline". */
@Serializable data class WebAnnotation(val id: String, val cfi: String, val color: String, val style: String)

@Serializable data class WebSettings(
    val fontScale: Double = 1.0, val fontFamily: String = "publisher", val lineHeight: Double = 1.5, val marginPercent: Int = 5,
    /** "publisher" | "start" | "justify" */ val align: String = "publisher",
    /** "light" | "dark" | "black" | "warm" */ val theme: String = "light",
    /** "paginated" | "scrolled" */ val flow: String = "paginated",
    /** F-032: "auto" (the book's page-progression-direction) | "ltr" | "rtl": which horizontal swipe is "next". */
    val swipeDirection: String = "auto",
    /** F-032: "slide" | "none". */ val pageAnimation: String = "slide",
    /** C-085, scrolled flow: "continuous" | "one-at-a-time" (stop at each chapter's end). */ val chapters: String = "continuous",
    /** C-099: CSS px at the top of the page kept free of text at rest (fullscreen: the display cutout). */ val topInset: Int = 0,
)

/**
 * Texts the web page shows. C-085: the One at a time chapter controls; `{title}` stands for the chapter's title, the
 * untitled ones are for a chapter without one. Null: the web side's own (English) text.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class WebTexts(
    val missingPart: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextChapter: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val previousChapter: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextChapterUntitled: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val previousChapterUntitled: String? = null,
)
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
        /** C-073: each section's size as foliate's progress uses it (bytes; 0 for non-linear or empty sections). */
        val sizes: List<Long> = emptyList(),
    ) : FromWeb
    @Serializable @SerialName("relocate") data class Relocate(
        val v: Int = 1, val sessionId: String, val cfi: String, val fraction: Double, val sectionIndex: Int, val tocHref: String? = null,
        /** Paged: 1-based text page within the section and the section's text pages; 0 and 0 in scrolled mode. */
        val page: Int = 0, val pages: Int = 0, val sectionFraction: Double = 0.0,
        /** F-041: the entries of the last [ToWeb.SetBookmarks] that lie on the visible page (same strings); null when none. */
        val bookmarks: List<String>? = null,
        /** C-083: the visible range's start and end as offsets in the section's text (search-hit coordinates); -1 unknown. */
        val textOffset: Int = -1, val textEnd: Int = -1,
    ) : FromWeb
    /** F-041: answer to [ToWeb.SetBookmarks] for the page now visible. */
    @Serializable @SerialName("pageBookmarks") data class PageBookmarks(val v: Int = 1, val sessionId: String, val cfis: List<String>) : FromWeb
    @Serializable @SerialName("painted") data class Painted(val v: Int = 1, val sessionId: String) : FromWeb
    /** Sent after the `relocate` of a [ToWeb.GoTo] or [ToWeb.GoToFraction] that carried an id. */
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
    /**
     * C-108 (c): layout generation [gen] has settled and can be counted; [key] names everything its counts depend on, so a
     * book's counts for one key are the same at every open. Nothing is counted until [ToWeb.CountPages].
     */
    @Serializable @SerialName("countReady") data class CountReady(val v: Int = 1, val sessionId: String, val gen: Int, val key: String) : FromWeb
    @Serializable @SerialName("tap") data class Tap(val v: Int = 1, val sessionId: String, val x: Double, val y: Double) : FromWeb
    /** F-042: entries in the followed-links history after each push or pop; Back pops one while it is > 0. */
    @Serializable @SerialName("history") data class History(val v: Int = 1, val sessionId: String, val depth: Int) : FromWeb
    /** F-043: the footnote popup opened or closed (a tap outside closes it on the web side). */
    @Serializable @SerialName("footnote") data class Footnote(val v: Int = 1, val sessionId: String, val open: Boolean) : FromWeb
    /** F-044: an http(s) or mailto link was tapped (the web side drops every other scheme). */
    @Serializable @SerialName("externalLink") data class ExternalLink(val v: Int = 1, val sessionId: String, val href: String) : FromWeb
    /** F-061: a tap landed on the user annotation [id] (a link inside it stays a link); it is not a [Tap]. */
    @Serializable @SerialName("annotationTapped") data class AnnotationTapped(val v: Int = 1, val sessionId: String, val id: String) : FromWeb
    /** C-083: answer to [ToWeb.TextOffsets]: [section, offset] per CFI in order (virtual), [-1, -1] when one did not resolve. */
    @Serializable @SerialName("textOffsets") data class TextOffsets(val v: Int = 1, val sessionId: String, val id: Int, val offsets: List<List<Int>>) : FromWeb
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
