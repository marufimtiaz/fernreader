package app.epubreader.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

enum class Align { PUBLISHER, START, JUSTIFY }
/** C-105: SYSTEM (the default) is Light while the phone is in light mode and Black while it is in dark mode. */
enum class ReadingThemeId {
    SYSTEM, LIGHT, DARK, BLACK, WARM;

    /** The theme the page shows: SYSTEM follows the phone's dark mode, every other theme is itself. */
    fun resolve(systemDark: Boolean): ReadingThemeId = if (this != SYSTEM) this else if (systemDark) BLACK else LIGHT
}
/** F-031: AUTO is paged in portrait and scrolled in landscape (C-004). */
enum class Flow { PAGED, SCROLLED, AUTO }
enum class IndicatorScope { BOOK, CHAPTER }
/**
 * C-083 "Page numbers": BOOK_PAGES (the default) = the book's own pages, print labels or fixed 1,800-character pages,
 * the same at every setting; SCREEN_BOOK / SCREEN_CHAPTER = pages as laid out on this screen, for the whole book or the
 * current chapter (the M4 "Whole book" / "This chapter").
 */
enum class PageNumbers {
    BOOK_PAGES, SCREEN_BOOK, SCREEN_CHAPTER;
    /** The screen-page counter's scope (BOOK_PAGES keeps counting screen pages for the slider preview and totals). */
    val screenScope: IndicatorScope get() = if (this == SCREEN_CHAPTER) IndicatorScope.CHAPTER else IndicatorScope.BOOK
}
/** F-032/F-081: AUTO follows the book's page-progression-direction; LTR = swipe right-to-left is next; RTL = the reverse. */
enum class SwipeDirection { AUTO, LTR, RTL }
/** C-005: ON = volume-down next, volume-up previous; REVERSED swaps them. */
enum class VolumeKeys { OFF, ON, REVERSED }
enum class PageAnimation { SLIDE, NONE }
enum class OrientationLock { SYSTEM, PORTRAIT, LANDSCAPE }
/** C-085: in scroll mode, CONTINUOUS = the next chapter follows below (C-079); ONE_AT_A_TIME = scrolling stops at its end. */
enum class ScrollChapters { CONTINUOUS, ONE_AT_A_TIME }
/** C-095: the tabs of the reader's settings sheet. */
enum class ReaderSheetTab { DISPLAY, GENERAL }

/**
 * Reading display settings (F-070..F-075, F-031, F-034 scope), one value for all books, stored as JSON in the
 * `setting` table under [KEY]. Every value read back is [clamped]; unknown fields are ignored and a field that does not
 * decode keeps its default, so older or newer app versions (or a hand-edited backup) never break the reader.
 * The input and screen fields (swipe direction, tap zones, volume keys, page-turn animation, fullscreen, keep-screen-on,
 * orientation, external-link confirmation; F-032, F-035, F-044, C-005) arrived with M6.
 *
 * Changes of default (C-083, C-105, C-106): a field is stored only when it differs from the default or is in [chosen],
 * so a field missing from the stored value was never changed by the reader (or, before [chosen] existed, was set back
 * to the default then) and decodes to today's default. Settings stored before C-105 have no [chosen]: a missing theme
 * moves to SYSTEM and a missing keep-screen-on to off, while any value they do store was the reader's choice and is kept.
 */
@Serializable
data class ReaderSettings(
    val fontScale: Double = 1.0,
    val fontFamily: String = PUBLISHER,
    val lineHeight: Double = 1.5,
    val marginPercent: Int = 5,
    val align: Align = Align.PUBLISHER,
    val theme: ReadingThemeId = ReadingThemeId.SYSTEM,
    val flow: Flow = Flow.PAGED,
    /** null = follow the system brightness; otherwise the reader window's brightness, 0.05..1. */
    val brightness: Double? = null,
    val pageNumbers: PageNumbers = PageNumbers.BOOK_PAGES,
    val swipeDirection: SwipeDirection = SwipeDirection.AUTO,
    /** F-032: tapping the screen edges turns pages; off by default. */
    val tapZones: Boolean = false,
    val volumeKeys: VolumeKeys = VolumeKeys.OFF,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val fullscreen: Boolean = true,
    /** C-106: off by default; the phone's own screen timeout applies. */
    val keepScreenOn: Boolean = false,
    val orientation: OrientationLock = OrientationLock.SYSTEM,
    /** F-044: ask before opening a link that leaves the book. */
    val confirmExternalLinks: Boolean = true,
    /** F-060: the colour a new highlight or underline gets (the last one chosen), a [HighlightColors] id. */
    val highlightColor: String = HighlightColors.YELLOW,
    /** C-085: Scroll mode's "Chapters" (display panel); stored values without it decode to CONTINUOUS. */
    val scrollChapters: ScrollChapters = ScrollChapters.CONTINUOUS,
    /** C-095: the sheet's last tab, where it opens next; stored values without it decode to DISPLAY. */
    val sheetTab: ReaderSheetTab = ReaderSheetTab.DISPLAY,
    /**
     * C-105/C-106: the fields the reader has set (their JSON names). They are stored even when equal to today's
     * default, so a later change of default keeps the reader's choice. See [withChoicesFrom].
     */
    val chosen: Set<String> = emptySet(),
) {
    fun clamped(): ReaderSettings = copy(
        fontScale = tenth(fontScale.coerceIn(MIN_FONT, MAX_FONT)),
        fontFamily = fontFamily.takeIf { it in FONTS } ?: PUBLISHER,
        lineHeight = tenth(lineHeight.coerceIn(1.3, 2.0)),
        marginPercent = marginPercent.coerceIn(0, 10),
        brightness = brightness?.let { if (it.isNaN()) null else (it.coerceIn(0.05, 1.0) * 100).roundToInt() / 100.0 },
        highlightColor = highlightColor.takeIf { it in HighlightColors.ALL } ?: HighlightColors.YELLOW,
    )

    /** F-070: one 10 % step up or down, staying within 80 %..260 %. */
    fun stepFont(delta: Int): ReaderSettings = copy(fontScale = fontScale + delta * 0.1).clamped()

    /** These settings as the reader changed them from [before]: every field that differs is added to [chosen]. */
    fun withChoicesFrom(before: ReaderSettings): ReaderSettings {
        val a = full(before); val b = full(this)
        val changed = b.keys.filter { it != CHOSEN && a[it] != b[it] }
        return copy(chosen = before.chosen + chosen + changed)
    }

    /** The fields that differ from the defaults, and the [chosen] ones whatever their value. */
    fun encode(): String {
        val d = full(ReaderSettings())
        return JsonObject(full(this).filter { (k, v) -> v != d[k] || k in chosen }).toString()
    }

    companion object {
        const val KEY = "reader.settings"
        const val PUBLISHER = "publisher"
        const val LITERATA = "literata"
        const val SOURCE_SANS = "source-sans"
        const val ATKINSON = "atkinson"
        val FONTS = listOf(PUBLISHER, LITERATA, SOURCE_SANS, ATKINSON)
        const val MIN_FONT = 0.8
        const val MAX_FONT = 2.6

        private const val CHOSEN = "chosen"
        private val Full = Json { encodeDefaults = true }
        private fun full(s: ReaderSettings) = Full.encodeToJsonElement(serializer(), s).jsonObject

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
                // C-083: before "pageNumbers" the choice was "pageIndicatorScope": BOOK (the old default) takes the new
                // default, Book pages; CHAPTER (a choice the reader made) stays screen pages of the chapter.
                pageNumbers = str("pageNumbers")?.let { v -> PageNumbers.entries.firstOrNull { it.name == v } }
                    ?: if (str("pageIndicatorScope") == IndicatorScope.CHAPTER.name) PageNumbers.SCREEN_CHAPTER else d.pageNumbers,
                swipeDirection = enum("swipeDirection", SwipeDirection.entries.toTypedArray(), d.swipeDirection),
                tapZones = bool("tapZones", d.tapZones),
                volumeKeys = enum("volumeKeys", VolumeKeys.entries.toTypedArray(), d.volumeKeys),
                pageAnimation = enum("pageAnimation", PageAnimation.entries.toTypedArray(), d.pageAnimation),
                fullscreen = bool("fullscreen", d.fullscreen),
                keepScreenOn = bool("keepScreenOn", d.keepScreenOn),
                orientation = enum("orientation", OrientationLock.entries.toTypedArray(), d.orientation),
                confirmExternalLinks = bool("confirmExternalLinks", d.confirmExternalLinks),
                highlightColor = str("highlightColor") ?: d.highlightColor,
                scrollChapters = enum("scrollChapters", ScrollChapters.entries.toTypedArray(), d.scrollChapters),
                sheetTab = enum("sheetTab", ReaderSheetTab.entries.toTypedArray(), d.sheetTab),
                chosen = runCatching { o[CHOSEN]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet() }
                    .getOrNull() ?: emptySet(),
            ).clamped()
        }
    }
}
