package app.epubreader.reader.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kotlin side of the reader bridge. Mirrors reader-web/src/bridge/protocol.ts; both are tested against bridge/fixtures. */
@Serializable sealed interface ToWeb {
    @Serializable @SerialName("ping") data class Ping(val v: Int = 1, val id: Int) : ToWeb
}

@Serializable sealed interface FromWeb {
    @Serializable @SerialName("ready") data class Ready(val v: Int = 1, val engine: String) : FromWeb
    @Serializable @SerialName("pong") data class Pong(val v: Int = 1, val id: Int) : FromWeb
    @Serializable @SerialName("error") data class Error(val v: Int = 1, val kind: String, val message: String) : FromWeb
}

val BridgeJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }

/** Returns null for malformed JSON or a message type this app version does not know (never throws). */
fun decodeFromWeb(text: String): FromWeb? = runCatching { BridgeJson.decodeFromString(FromWeb.serializer(), text) }.getOrNull()

fun encodeToWeb(m: ToWeb): String = BridgeJson.encodeToString(ToWeb.serializer(), m)
