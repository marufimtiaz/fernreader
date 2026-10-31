package app.epubreader.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

enum class Align { PUBLISHER, START, JUSTIFY }
enum class ReadingThemeId { LIGHT, DARK, BLACK, WARM }
/** F-031: AUTO is paged in portrait and scrolled in landscape (C-004). */
enum class Flow { PAGED, SCROLLED, AUTO }
enum class IndicatorScope { BOOK, CHAPTER }
/** F-032/F-081: AUTO follows the book's page-progression-direction; LTR = swipe right-to-left is next; RTL = the reverse. */
enum class SwipeDirection { AUTO, LTR, RTL }
/** C-005: ON = volume-down next, volume-up previous; REVERSED swaps them. */
enum class VolumeKeys { OFF, ON, REVERSED }
enum class PageAnimation { SLIDE, NONE }
enum class OrientationLock { SYSTEM, PORTRAIT, LANDSCAPE }

/**
 * Reading display settings (F-070..F-075, F-031, F-034 scope), one value for all books, stored as JSON in the
 * `setting` table under [KEY]. Every value read back is [clamped]; unknown fields are ignored and a field that does not
 * decode keeps its default, so older or newer app versions (or a hand-edited backup) never break the reader.
 * The input and screen fields (swipe direction, tap zones, volume keys, page-turn animation, fullscreen, keep-screen-on,
 * orientation, external-link confirmation; F-032, F-035, F-044, C-005) arrived with M6.
 */
@Serializable
data class ReaderSettings(
    val fontScale: Double = 1.0,
    val fontFamily: String = PUBLISHER,
    val lineHeight: Double = 1.5,
    val marginPercent: Int = 5,
    val align: Align = Align.PUBLISHER,
    val theme: ReadingThemeId = ReadingThemeId.LIGHT,
    val flow: Flow = Flow.PAGED,
    /** null = follow the system brightness; otherwise the reader window's brightness, 0.05..1. */
    val brightness: Double? = null,
    val pageIndicatorScope: IndicatorScope = IndicatorScope.BOOK,
    val swipeDirection: SwipeDirection = SwipeDirection.AUTO,
    /** F-032: tapping the screen edges turns pages; off by default. */
    val tapZones: Boolean = false,
    val volumeKeys: VolumeKeys = VolumeKeys.OFF,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val fullscreen: Boolean = true,
    val keepScreenOn: Boolean = true,
    val orientation: OrientationLock = OrientationLock.SYSTEM,
    /** F-044: ask before opening a link that leaves the book. */
    val confirmExternalLinks: Boolean = true,
) {
    fun clamped(): ReaderSettings = copy(
        fontScale = tenth(fontScale.coerceIn(MIN_FONT, MAX_FONT)),
        fontFamily = fontFamily.takeIf { it in FONTS } ?: PUBLISHER,
        lineHeight = tenth(lineHeight.coerceIn(1.3, 2.0)),
        marginPercent = marginPercent.coerceIn(0, 10),
        brightness = brightness?.let { if (it.isNaN()) null else (it.coerceIn(0.05, 1.0) * 100).roundToInt() / 100.0 },
    )

    /** F-070: one 10 % step up or down, staying within 80 %..260 %. */
    fun stepFont(delta: Int): ReaderSettings = copy(fontScale = fontScale + delta * 0.1).clamped()

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        const val KEY = "reader.settings"
        const val PUBLISHER = "publisher"
        const val LITERATA = "literata"
        const val SOURCE_SANS = "source-sans"
        const val ATKINSON = "atkinson"
        val FONTS = listOf(PUBLISHER, LITERATA, SOURCE_SANS, ATKINSON)
        const val MIN_FONT = 0.8
        const val MAX_FONT = 2.6

        private fun tenth(x: Double) = (x * 10).roundToInt() / 10.0

        /** Never throws: garbage decodes to the defaults, a bad field to its default. */
        fun decode(json: String?): ReaderSettings {
            val o = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return ReaderSettings()
            val d = ReaderSettings()
            fun p(k: String): JsonPrimitive? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
            fun str(k: String) = p(k)?.takeIf { it.isString }?.contentOrNull
            fun num(k: String) = p(k)?.takeIf { !it.isString }?.doubleOrNull
            fun <E : Enum<E>> enum(k: String, values: Array<E>, def: E) = str(k)?.let { s -> values.firstOrNull { it.name == s } } ?: def
            fun bool(k: String, def: Boolean) = p(k)?.takeIf { !it.isString }?.booleanOrNull ?: def
            return ReaderSettings(
                fontScale = num("fontScale") ?: d.fontScale,
                fontFamily = str("fontFamily") ?: d.fontFamily,
                lineHeight = num("lineHeight") ?: d.lineHeight,
                marginPercent = p("marginPercent")?.takeIf { !it.isString }?.intOrNull ?: d.marginPercent,
                align = enum("align", Align.entries.toTypedArray(), d.align),
                theme = enum("theme", ReadingThemeId.entries.toTypedArray(), d.theme),
                flow = enum("flow", Flow.entries.toTypedArray(), d.flow),
                brightness = num("brightness"),
                pageIndicatorScope = enum("pageIndicatorScope", IndicatorScope.entries.toTypedArray(), d.pageIndicatorScope),
                swipeDirection = enum("swipeDirection", SwipeDirection.entries.toTypedArray(), d.swipeDirection),
                tapZones = bool("tapZones", d.tapZones),
                volumeKeys = enum("volumeKeys", VolumeKeys.entries.toTypedArray(), d.volumeKeys),
                pageAnimation = enum("pageAnimation", PageAnimation.entries.toTypedArray(), d.pageAnimation),
                fullscreen = bool("fullscreen", d.fullscreen),
                keepScreenOn = bool("keepScreenOn", d.keepScreenOn),
                orientation = enum("orientation", OrientationLock.entries.toTypedArray(), d.orientation),
                confirmExternalLinks = bool("confirmExternalLinks", d.confirmExternalLinks),
            ).clamped()
        }
    }
}
