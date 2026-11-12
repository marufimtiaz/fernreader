package app.epubreader.reader.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProtocolFixtureTest {
    private fun fixture(n: String) = File("../bridge/fixtures/$n.json").readText().trim()
    private fun tree(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test fun ready_decodes() = assertEquals(FromWeb.Ready(engine = "foliate-js@78914ae"), decodeFromWeb(fixture("ready")))
    @Test fun pong_decodes() = assertEquals(FromWeb.Pong(id = 7), decodeFromWeb(fixture("pong")))
    @Test fun ping_encodes_like_fixture() = assertEquals(tree(fixture("ping")), tree(encodeToWeb(ToWeb.Ping(id = 7))))
    @Test fun toc_index_defaults_to_unresolved() =
        assertEquals(-1, BridgeJson.decodeFromString(TocItem.serializer(), """{"label":"A","href":"a","depth":0}""").index)
    @Test fun a_relocate_without_text_offsets_decodes_with_unknown() =
        assertEquals(-1, (decodeFromWeb("""{"v":1,"type":"relocate","sessionId":"s","cfi":"c","fraction":0.1,"sectionIndex":0}""") as FromWeb.Relocate).textOffset)
    @Test fun a_relocate_without_bookmarks_decodes_with_none() =
        assertEquals(null, decodeFromWeb("""{"v":1,"type":"relocate","sessionId":"s","cfi":"c","fraction":0.1,"sectionIndex":0}""").let { (it as FromWeb.Relocate).bookmarks })
    @Test fun unknown_type_is_ignored() = assertNull(decodeFromWeb(fixture("unknown")))

    @Test fun every_from_web_fixture_decodes() {
        for (n in listOf("opened", "relocate", "painted", "seeked", "tap", "error")) assertNotNull(decodeFromWeb(fixture(n)), n)
        assertEquals(FromWeb.Seeked(sessionId = "s-1", id = 1), decodeFromWeb(fixture("seeked")))
        assertEquals(FromWeb.History(sessionId = "s-1", depth = 1), decodeFromWeb(fixture("history")))
        assertEquals(FromWeb.Footnote(sessionId = "s-1", open = true), decodeFromWeb(fixture("footnote")))
        assertEquals(FromWeb.ExternalLink(sessionId = "s-1", href = "https://www.gutenberg.org/"), decodeFromWeb(fixture("externalLink")))
        assertEquals(FromWeb.Relocate(sessionId = "s-1", cfi = "epubcfi(/6/4!/4,/2/1:0,/6/1:5)", fraction = 0.12, sectionIndex = 1, tocHref = "OEBPS/c1.xhtml", page = 3, pages = 12, sectionFraction = 0.18, bookmarks = listOf("epubcfi(/6/4!/4/2/1:10)"), textOffset = 1800, textEnd = 2950),
            decodeFromWeb(fixture("relocate")))
        assertEquals(FromWeb.Selection(sessionId = "s-1", text = "quick ", cfi = "epubcfi(/6/4!/4/2,/1:4,/1:10)", sectionIndex = 1), decodeFromWeb(fixture("selection")))
        assertEquals(FromWeb.Selection(sessionId = "s-1", text = "", cfi = null, sectionIndex = -1), decodeFromWeb(fixture("selectionCleared")))
        assertEquals(FromWeb.AnnotationTapped(sessionId = "s-1", id = "a-1"), decodeFromWeb(fixture("annotationTapped")))
        assertEquals(FromWeb.PageBookmarks(sessionId = "s-1", cfis = listOf("epubcfi(/6/4!/4/2/1:10)")), decodeFromWeb(fixture("pageBookmarks")))
        assertEquals(FromWeb.PageCounts(sessionId = "s-1", gen = 2, linear = listOf(0, 1, 2), counts = listOf(listOf(1, 4), listOf(2, 9)), sample = true),
            decodeFromWeb(fixture("pageCounts")))
        assertEquals(FromWeb.TextOffsets(sessionId = "s-1", id = 2, offsets = listOf(listOf(1, 10), listOf(-1, -1))), decodeFromWeb(fixture("textOffsetsAnswer")))
        val o = decodeFromWeb(fixture("opened")) as FromWeb.Opened
        assertEquals(listOf(false, true), o.toc.map { it.missing })
        assertEquals(listOf(0, -1), o.toc.map { it.index })
        assertEquals(listOf(1200L, 0L, 3400L), o.sizes)
    }

    @Test fun every_to_web_message_encodes_like_its_fixture() {
        val msgs = mapOf(
            "open" to ToWeb.Open(sessionId = "s-1", position = "epubcfi(/6/4!/4/2/1:0)", texts = WebTexts("This part of the book could not be shown.")),
            "close" to ToWeb.Close(), "next" to ToWeb.Next(), "prev" to ToWeb.Prev(), "goTo" to ToWeb.GoTo(target = "OEBPS/c2.xhtml"),
            "goToSeek" to ToWeb.GoTo(target = "epubcfi(/6/6!/4/2)", id = 1),
            "goToText" to ToWeb.GoToText(sectionIndex = 4, offset = 1234, length = 8, needle = "Borodinó"),
            "clearSearchHighlight" to ToWeb.ClearSearchHighlight(),
            "back" to ToWeb.Back(), "closeFootnote" to ToWeb.CloseFootnote(),
            "setBookmarks" to ToWeb.SetBookmarks(sessionId = "s-1", cfis = listOf("epubcfi(/6/4!/4/2/1:10)", "epubcfi(/6/8!/4/6/1:0)")),
            "goToFraction" to ToWeb.GoToFraction(fraction = 0.25),
            "goToFractionSeek" to ToWeb.GoToFraction(fraction = 0.25, id = 2),
            "addAnnotation" to ToWeb.AddAnnotation(sessionId = "s-1", id = "a-1", cfi = "epubcfi(/6/4!/4/2,/1:4,/1:9)", color = "yellow", style = "highlight"),
            "clearSelection" to ToWeb.ClearSelection(),
            "setAnnotations" to ToWeb.SetAnnotations(sessionId = "s-1", items = listOf(
                WebAnnotation(id = "a-1", cfi = "epubcfi(/6/4!/4/2,/1:4,/1:9)", color = "yellow", style = "highlight"),
                WebAnnotation(id = "a-2", cfi = "epubcfi(/6/8!/4/6,/1:0,/1:12)", color = "blue", style = "underline"),
            )),
            "removeAnnotation" to ToWeb.RemoveAnnotation(sessionId = "s-1", id = "a-1"),
            "goToOffset" to ToWeb.GoToOffset(sectionIndex = 3, offset = 5400),
            "textOffsets" to ToWeb.TextOffsets(sessionId = "s-1", id = 2, cfis = listOf("epubcfi(/6/4!/4/2/1:10)", "epubcfi(/6/99!/4)")),
            "setSettings" to ToWeb.SetSettings(settings = WebSettings(1.4, "literata", 1.8, 3, "justify", "warm", "scrolled", "rtl", "none", "one-at-a-time", 28)),
            "openContinued" to ToWeb.Open(sessionId = "s-1", position = null, texts = WebTexts("Missing."), continued = listOf(3, 4, 5)),
            "openChapterTexts" to ToWeb.Open(sessionId = "s-1", position = null, texts = WebTexts("Missing.", "Next: {title}", "Previous: {title}", "Next chapter", "Previous chapter")),
        )
        for ((n, m) in msgs) assertEquals(tree(fixture(n)), tree(encodeToWeb(m)), n)
    }
}
