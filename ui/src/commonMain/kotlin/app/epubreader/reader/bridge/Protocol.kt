package app.epubreader.reader.bridge

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kotlin side of the reader bridge. Mirrors reader-web/src/bridge/protocol.ts; both are tested against bridge/fixtures. */
@Serializable sealed interface ToWeb {
    @Serializable @SerialName("ping") data class Ping(val v: Int = 1, val id: Int) : ToWeb
    @Serializable @SerialName("open") data class Open(val v: Int = 1, val sessionId: String, val position: String? = null, val texts: WebTexts) : ToWeb
    @Serializable @SerialName("close") data class Close(val v: Int = 1) : ToWeb
    @Serializable @SerialName("next") data class Next(val v: Int = 1) : ToWeb
    @Serializable @SerialName("prev") data class Prev(val v: Int = 1) : ToWeb
    /** With [id], the web side answers [FromWeb.Seeked] once the jump has landed (or failed). */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("goTo") data class GoTo(
        val v: Int = 1, val target: String, @EncodeDefault(EncodeDefault.Mode.NEVER) val id: Int? = null,
    ) : ToWeb
}

@Serializable data class WebTexts(val missingPart: String)
@Serializable data class TocItem(val label: String, val href: String, val depth: Int, val missing: Boolean = false)

@Serializable sealed interface FromWeb {
    @Serializable @SerialName("ready") data class Ready(val v: Int = 1, val engine: String) : FromWeb
    @Serializable @SerialName("pong") data class Pong(val v: Int = 1, val id: Int) : FromWeb
    @Serializable @SerialName("error") data class Error(val v: Int = 1, val sessionId: String? = null, val kind: String, val message: String) : FromWeb
    @Serializable @SerialName("opened") data class Opened(
        val v: Int = 1, val sessionId: String, val identifier: String? = null, val title: String, val authors: List<String>,
        val dir: String, val fixedLayout: Boolean, val sections: Int, val toc: List<TocItem>,
    ) : FromWeb
    @Serializable @SerialName("relocate") data class Relocate(
        val v: Int = 1, val sessionId: String, val cfi: String, val fraction: Double, val sectionIndex: Int, val tocHref: String? = null,
    ) : FromWeb
    @Serializable @SerialName("painted") data class Painted(val v: Int = 1, val sessionId: String) : FromWeb
    /** Sent after the `relocate` of a [ToWeb.GoTo] that carried an id. */
    @Serializable @SerialName("seeked") data class Seeked(val v: Int = 1, val sessionId: String, val id: Int) : FromWeb
    @Serializable @SerialName("tap") data class Tap(val v: Int = 1, val sessionId: String, val x: Double, val y: Double) : FromWeb
}

val BridgeJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }

/** Returns null for malformed JSON or a message type this app version does not know (never throws). */
fun decodeFromWeb(text: String): FromWeb? = runCatching { BridgeJson.decodeFromString(FromWeb.serializer(), text) }.getOrNull()

fun encodeToWeb(m: ToWeb): String = BridgeJson.encodeToString(ToWeb.serializer(), m)
