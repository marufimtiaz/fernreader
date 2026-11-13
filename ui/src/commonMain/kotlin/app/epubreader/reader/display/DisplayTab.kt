package app.epubreader.reader.display

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.epubreader.core.data.Align
import app.epubreader.core.data.BrightnessCurve
import app.epubreader.core.data.Flow
import app.epubreader.core.data.PageNumbers
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ReadingThemeId
import app.epubreader.core.data.ScrollChapters
import app.epubreader.reader.settings.keepsSideways
import app.epubreader.ui.Copy
import kotlin.math.roundToInt

/**
 * Display settings inside the reader (F-075), the first tab of [app.epubreader.reader.settings.ReaderSheet], whose
 * surface lies over the page: every change previews live. [fonts]: the bundled faces by settings id, so each option is
 * shown in its own face (F-071).
 */
@Composable
fun DisplayTab(
    s: ReaderSettings,
    fonts: Map<String, FontFamily>,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    scroll: ScrollState,
    /** C-004: Auto reading mode scrolls in landscape. */
    landscape: Boolean = false,
    /** C-109: the phone's brightness, which the slider's scale and its start when leaving "follow system" match. */
    brightness: ScreenBrightness = ScreenBrightness.Unknown,
    /**
     * C-116: Battery Saver applies now (C-107): the theme and Page numbers are greyed out, the saved choice still marked,
     * with a line under each saying why. Nothing else changes.
     */
    saving: Boolean = false,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeRow(s.theme, enabled = !saving) { id -> onChange { it.copy(theme = id) } }
            if (saving) Note(Copy.saverTheme)
        }
        TextSizeRow(s.fontScale, onStep = { d -> onChange { it.stepFont(d) } })
        FontRow(s.fontFamily, fonts) { f -> onChange { it.copy(fontFamily = f) } }
        SliderRow(Copy.lineSpacing, s.lineHeight.toString(), s.lineHeight.toFloat(), 1.3f..2.0f, steps = 6) { v ->
            onChange { it.copy(lineHeight = (v * 10).roundToInt() / 10.0) }
        }
        SliderRow(Copy.margins, "${s.marginPercent} %", s.marginPercent.toFloat(), 0f..10f, steps = 9) { v ->
            onChange { it.copy(marginPercent = v.roundToInt()) }
        }
        Segments(Copy.alignment, listOf(Align.PUBLISHER to Copy.alignPublisher, Align.START to Copy.alignStart, Align.JUSTIFY to Copy.alignJustify), s.align) { a ->
            onChange { it.copy(align = a) }
        }
        Segments(Copy.layout, FlowOptions, s.flow) { f ->
            onChange { it.copy(flow = f) }
        }
        if (s.scrolls(landscape)) Segments(Copy.scrollChapters, ChaptersOptions, s.scrollChapters) { v ->
            onChange { it.copy(scrollChapters = v) }
        }
        // C-083: book pages (the default) or screen pages, for the whole book or the chapter.
        Segments(Copy.pageNumbers, PageNumberOptions, s.pageNumbers, enabled = !saving,
            hint = if (saving) Copy.saverPageNumbers else Copy.pageNumbersHint(s.pageNumbers)) { p ->
            onChange { it.copy(pageNumbers = p) }
        }
        BrightnessRow(s.brightness, brightness) { b -> onChange { it.copy(brightness = b) } }
        ExtraDimRow(s.extraDim, s.extraDimLevel, onSwitch = { on -> onChange { it.copy(extraDim = on) } }) { level ->
            onChange { it.copy(extraDimLevel = level) }
        }
        SwitchRow(Copy.followBatterySaver, Copy.followBatterySaverHint, s.followBatterySaver) { v -> onChange { it.copy(followBatterySaver = v) } } // C-107
    }
}

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ThemeRow(current: ReadingThemeId, enabled: Boolean, onPick: (ReadingThemeId) -> Unit) {
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (id in ReadingThemeId.entries) { // C-105: System first, then the four themes
            val selected = id == current
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.RadioButton) { onPick(id) }
                    .semantics { this.selected = selected }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(if (selected) 46.dp else 50.dp).clip(CircleShape)) {
                        if (id == ReadingThemeId.SYSTEM) {
                            // Light and Black side by side: the two themes System shows.
                            Row(Modifier.matchParentSize()) {
                                Swatch(ReadingTheme.Light, "A", Modifier.weight(1f), Alignment.CenterEnd)
                                Swatch(ReadingTheme.Black, "a", Modifier.weight(1f), Alignment.CenterStart)
                            }
                        } else Swatch(ReadingTheme.of(id, systemDark = false), "Aa", Modifier.matchParentSize(), Alignment.Center)
                    }
                }
                Text(Copy.themeName(id), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else null)
            }
        }
    }
}

/** C-107: a labelled switch with a hint under the label. */
@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label(label)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun Swatch(t: ReadingTheme, text: String, modifier: Modifier, align: Alignment) =
    Box(modifier.fillMaxHeight().background(t.background), contentAlignment = align) {
        Text(text, color = t.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }

@Composable
private fun TextSizeRow(scale: Double, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(Copy.textSize, Modifier.weight(1f))
        FilledTonalButton(onClick = { onStep(-1) }, enabled = scale > ReaderSettings.MIN_FONT,
            modifier = Modifier.semantics { contentDescription = Copy.smallerText }) { Text("A", fontSize = 14.sp) }
        Text("${(scale * 100).roundToInt()} %", Modifier.width(72.dp), style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        FilledTonalButton(onClick = { onStep(+1) }, enabled = scale < ReaderSettings.MAX_FONT,
            modifier = Modifier.semantics { contentDescription = Copy.largerText }) { Text("A", fontSize = 20.sp) }
    }
}

@Composable
private fun FontRow(current: String, fonts: Map<String, FontFamily>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(Copy.font)
        Row(Modifier.keepsSideways().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in ReaderSettings.FONTS) FilterChip(
                selected = id == current, onClick = { onPick(id) },
                label = { Text(Copy.fontName(id), fontFamily = fonts[id], fontSize = 16.sp) },
            )
        }
    }
}

@Composable
private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onValue: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(label, Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = v, onValueChange = onValue, valueRange = range, steps = steps, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** C-085: scroll mode's chapter choices, shown under Reading mode only while the book scrolls ([scrolls]). */
internal val ChaptersOptions = listOf(ScrollChapters.CONTINUOUS to Copy.chaptersContinuous, ScrollChapters.ONE_AT_A_TIME to Copy.chaptersOneAtATime)

/** C-085: the reading mode is Scroll, or Auto resolving to scroll ([landscape], C-004). */
internal fun ReaderSettings.scrolls(landscape: Boolean): Boolean = flow == Flow.SCROLLED || flow == Flow.AUTO && landscape

/** F-031: the reading-mode choices; C-084: shown only here, in the display panel. */
internal val FlowOptions = listOf(Flow.PAGED to Copy.flowPaged, Flow.SCROLLED to Copy.flowScrolled, Flow.AUTO to Copy.flowAuto)

/** C-083: the Page numbers choices in the display panel (short; the hint under them says what the selected one means). */
internal val PageNumberOptions = listOf(PageNumbers.BOOK_PAGES to Copy.segmentBookPages, PageNumbers.SCREEN_BOOK to Copy.segmentScreenPages, PageNumbers.SCREEN_CHAPTER to Copy.segmentChapterPages)

/** A line under a row, e.g. why it is greyed out. */
@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** [enabled] false greys the row out and ignores taps; [hint] is shown under it (e.g. why it is off). */
@Composable
internal fun <T> Segments(label: String, options: List<Pair<T, String>>, current: T, enabled: Boolean = true, hint: String? = null, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, Modifier.alpha(if (enabled) 1f else 0.38f))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (v, text) ->
                SegmentedButton(selected = v == current, onClick = { onPick(v) }, enabled = enabled, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                    Text(text, maxLines = 1)
                }
            }
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * C-109: the slider is on the system slider's perceptual scale ([BrightnessCurve]): the same position gives the same
 * screen brightness, and its end is the panel's full brightness. [brightness] is the stored window brightness (linear),
 * null while following the system; the greyed-out slider then shows where the system's is. Switching "follow system"
 * off starts from the system's current brightness, so the screen doesn't change.
 */
@Composable
private fun BrightnessRow(brightness: Double?, screen: ScreenBrightness, onValue: (Double?) -> Unit) {
    val following = brightness == null
    val system = remember(following) { if (following) screen.current() else null }
    val linear = brightness ?: system ?: BrightnessCurve.toLinear(0.5, screen.min, 1.0)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(Copy.brightness, Modifier.weight(1f))
            Text(Copy.followSystem, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Switch(checked = following, onCheckedChange = { follow ->
                onValue(if (follow) null else (screen.current() ?: linear).coerceIn(screen.min, 1.0))
            })
        }
        Slider(
            value = BrightnessCurve.toPosition(linear, screen.min, 1.0).toFloat(),
            onValueChange = { onValue(BrightnessCurve.toLinear(it.toDouble(), screen.min, 1.0)) },
            enabled = !following, modifier = Modifier.keepsSideways().semantics { contentDescription = Copy.brightness },
        )
    }
}

/** C-110: Extra dim's slider runs from light dimming at its start ([ReaderSettings.DIM_LIGHT]) to strong at its end. */
internal fun dimPosition(level: Double): Float =
    ((ReaderSettings.DIM_LIGHT - level) / (ReaderSettings.DIM_LIGHT - ReaderSettings.DIM_STRONG)).toFloat().coerceIn(0f, 1f)

/** C-110: the dim level at slider position [position] (0..1), to the hundredth. */
internal fun dimLevel(position: Float): Double =
    ((ReaderSettings.DIM_LIGHT - position.coerceIn(0f, 1f) * (ReaderSettings.DIM_LIGHT - ReaderSettings.DIM_STRONG)) * 100).roundToInt() / 100.0

/**
 * C-110 "Extra dim", under Brightness: a switch (off by default) and how far the page dims, from light to strong; the
 * value shown is the page's level (70 % .. 15 % of normal). It dims on top of either brightness mode and previews live;
 * the slider is greyed out while the switch is off and keeps its level.
 */
@Composable
private fun ExtraDimRow(on: Boolean, level: Double, onSwitch: (Boolean) -> Unit, onLevel: (Double) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(Copy.extraDim, Modifier.weight(1f))
            Text(Copy.extraDimLevel(level), Modifier.alpha(if (on) 1f else 0.38f), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Switch(checked = on, onCheckedChange = onSwitch, modifier = Modifier.semantics { contentDescription = Copy.extraDim })
        }
        Slider(
            value = dimPosition(level), onValueChange = { onLevel(dimLevel(it)) }, enabled = on,
            modifier = Modifier.keepsSideways().semantics { contentDescription = Copy.extraDimSlider },
        )
    }
}
