package app.epubreader.reader.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolFixtureTest {
    private fun fixture(n: String) = File("../bridge/fixtures/$n.json").readText().trim()
    private fun tree(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test fun ready_decodes() = assertEquals(FromWeb.Ready(engine = "foliate-js@78914ae"), decodeFromWeb(fixture("ready")))
    @Test fun pong_decodes() = assertEquals(FromWeb.Pong(id = 7), decodeFromWeb(fixture("pong")))
    @Test fun ping_encodes_like_fixture() = assertEquals(tree(fixture("ping")), tree(encodeToWeb(ToWeb.Ping(id = 7))))
    @Test fun unknown_type_is_ignored() = assertNull(decodeFromWeb(fixture("unknown")))
}
