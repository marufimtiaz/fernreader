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
    @Test fun unknown_type_is_ignored() = assertNull(decodeFromWeb(fixture("unknown")))

    @Test fun every_from_web_fixture_decodes() {
        for (n in listOf("opened", "relocate", "painted", "tap", "error")) assertNotNull(decodeFromWeb(fixture(n)), n)
        val o = decodeFromWeb(fixture("opened")) as FromWeb.Opened
        assertEquals(listOf(false, true), o.toc.map { it.missing })
    }

    @Test fun every_to_web_message_encodes_like_its_fixture() {
        val msgs = mapOf(
            "open" to ToWeb.Open(sessionId = "s-1", position = "epubcfi(/6/4!/4/2/1:0)", texts = WebTexts("This part of the book could not be shown.")),
            "close" to ToWeb.Close(), "next" to ToWeb.Next(), "prev" to ToWeb.Prev(), "goTo" to ToWeb.GoTo(target = "OEBPS/c2.xhtml"),
        )
        for ((n, m) in msgs) assertEquals(tree(fixture(n)), tree(encodeToWeb(m)), n)
    }
}
