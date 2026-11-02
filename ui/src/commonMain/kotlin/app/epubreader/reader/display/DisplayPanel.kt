package app.epubreader.reader.display

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import app.epubreader.core.data.Flow
import app.epubreader.core.data.IndicatorScope
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ReadingThemeId
import app.epubreader.core.data.ScrollChapters
import app.epubreader.ui.Copy
import kotlin.math.roundToInt

/**
 * Display settings inside the reader (F-075). Anchored to the bottom over the page (it never reflows it); the upper
 * part of the page stays visible, so every change previews live. Coloured by the active reading theme (F-074).
 * [fonts]: the bundled faces by settings id, so each option is shown in its own face (F-071).
 */
@Composable
fun DisplayPanel(
    s: ReaderSettings,
    fonts: Map<String, FontFamily>,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
    /** C-004: Auto reading mode scrolls in landscape. */
    landscape: Boolean = false,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.navigationBarsPadding().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ThemeRow(s.theme) { id -> onChange { it.copy(theme = id) } }
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
            Segments(Copy.pageNumbers, listOf(IndicatorScope.BOOK to Copy.scopeBook, IndicatorScope.CHAPTER to Copy.scopeChapter), s.pageIndicatorScope) { sc ->
                onChange { it.copy(pageIndicatorScope = sc) }
            }
            BrightnessRow(s.brightness) { b -> onChange { it.copy(brightness = b) } }
        }
    }
}

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ThemeRow(current: ReadingThemeId, onPick: (ReadingThemeId) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (id in ReadingThemeId.entries) {
            val t = ReadingTheme.of(id)
            val selected = id == current
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton) { onPick(id) }
                    .semantics { this.selected = selected }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), CircleShape)
                        .then(Modifier.padding(0.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(Modifier.size(if (selected) 46.dp else 50.dp), shape = CircleShape, color = t.background) {
                        Box(contentAlignment = Alignment.Center) { Text("Aa", color = t.text, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
                    }
                }
                Text(Copy.themeName(id), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else null)
            }
        }
    }
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
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun BrightnessRow(brightness: Double?, onValue: (Double?) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(Copy.brightness, Modifier.weight(1f))
            Text(Copy.followSystem, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Switch(checked = brightness == null, onCheckedChange = { onValue(if (it) null else 0.5) })
        }
        Slider(
            value = (brightness ?: 0.5).toFloat(), onValueChange = { onValue(it.toDouble()) }, valueRange = 0.05f..1f,
            enabled = brightness != null, modifier = Modifier.semantics { contentDescription = Copy.brightness },
        )
    }
}
