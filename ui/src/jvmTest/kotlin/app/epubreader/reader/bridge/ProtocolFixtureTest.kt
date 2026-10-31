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
    @Test fun unknown_type_is_ignored() = assertNull(decodeFromWeb(fixture("unknown")))

    @Test fun every_from_web_fixture_decodes() {
        for (n in listOf("opened", "relocate", "painted", "seeked", "tap", "error")) assertNotNull(decodeFromWeb(fixture(n)), n)
        assertEquals(FromWeb.Seeked(sessionId = "s-1", id = 1), decodeFromWeb(fixture("seeked")))
        assertEquals(FromWeb.Relocate(sessionId = "s-1", cfi = "epubcfi(/6/4!/4/2/1:0)", fraction = 0.12, sectionIndex = 1, tocHref = "OEBPS/c1.xhtml", page = 3, pages = 12, sectionFraction = 0.18),
            decodeFromWeb(fixture("relocate")))
        assertEquals(FromWeb.PageCounts(sessionId = "s-1", gen = 2, linear = listOf(0, 1, 2), counts = listOf(listOf(1, 4), listOf(2, 9)), sample = true),
            decodeFromWeb(fixture("pageCounts")))
        val o = decodeFromWeb(fixture("opened")) as FromWeb.Opened
        assertEquals(listOf(false, true), o.toc.map { it.missing })
        assertEquals(listOf(0, -1), o.toc.map { it.index })
    }

    @Test fun every_to_web_message_encodes_like_its_fixture() {
        val msgs = mapOf(
            "open" to ToWeb.Open(sessionId = "s-1", position = "epubcfi(/6/4!/4/2/1:0)", texts = WebTexts("This part of the book could not be shown.")),
            "close" to ToWeb.Close(), "next" to ToWeb.Next(), "prev" to ToWeb.Prev(), "goTo" to ToWeb.GoTo(target = "OEBPS/c2.xhtml"),
            "goToSeek" to ToWeb.GoTo(target = "epubcfi(/6/6!/4/2)", id = 1),
            "goToText" to ToWeb.GoToText(sectionIndex = 4, offset = 1234, length = 8, needle = "Borodinó"),
            "clearSearchHighlight" to ToWeb.ClearSearchHighlight(),
            "goToFraction" to ToWeb.GoToFraction(fraction = 0.25),
            "setSettings" to ToWeb.SetSettings(settings = WebSettings(1.4, "literata", 1.8, 3, "justify", "warm", "scrolled", "rtl", "none")),
        )
        for ((n, m) in msgs) assertEquals(tree(fixture(n)), tree(encodeToWeb(m)), n)
    }
}
