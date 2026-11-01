package app.epubreader.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SwipeDirection
import app.epubreader.core.data.VolumeKeys
import app.epubreader.reader.display.FlowOptions
import app.epubreader.reader.display.Label
import app.epubreader.reader.display.Segments
import app.epubreader.ui.Copy
import app.epubreader.settings.pageAnimationApplies

/**
 * Reader settings (M6): page turning, screen and link behaviour. Bottom-anchored over the page like the display panel,
 * scrollable; every change goes through [onChange] (applied and stored at once). The page-number scope lives in the
 * display panel.
 */
@Composable
fun ReaderSettingsSheet(
    s: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.navigationBarsPadding().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(Copy.readerSettings, style = MaterialTheme.typography.titleMedium)
            Header(Copy.settingsPageTurning)
            // F-031: also here (and in Settings → Reading), not only in the display panel; all three edit `flow`.
            Segments(Copy.layout, FlowOptions, s.flow) { f -> onChange { it.copy(flow = f) } }
            Segments(
                Copy.pageAnimation, listOf(PageAnimation.SLIDE to Copy.animSlide, PageAnimation.NONE to Copy.animNone), s.pageAnimation,
                enabled = s.pageAnimationApplies, hint = Copy.appliesToPageMode.takeUnless { s.pageAnimationApplies },
            ) { v -> onChange { it.copy(pageAnimation = v) } }
            Segments(
                Copy.swipeDirection,
                listOf(SwipeDirection.AUTO to Copy.swipeAuto, SwipeDirection.LTR to Copy.swipeLtr, SwipeDirection.RTL to Copy.swipeRtl),
                s.swipeDirection,
            ) { v -> onChange { it.copy(swipeDirection = v) } }
            SwitchRow(Copy.tapZones, s.tapZones) { v -> onChange { it.copy(tapZones = v) } }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Segments(
                    Copy.volumeKeys,
                    listOf(VolumeKeys.OFF to Copy.volumeOff, VolumeKeys.ON to Copy.volumeOn, VolumeKeys.REVERSED to Copy.volumeReversed),
                    s.volumeKeys,
                ) { v -> onChange { it.copy(volumeKeys = v) } }
                Text(Copy.volumeHint(s.volumeKeys), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Header(Copy.settingsScreen)
            SwitchRow(Copy.fullscreen, s.fullscreen) { v -> onChange { it.copy(fullscreen = v) } }
            SwitchRow(Copy.keepScreenOn, s.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }
            Segments(
                Copy.orientation,
                listOf(OrientationLock.SYSTEM to Copy.orientSystem, OrientationLock.PORTRAIT to Copy.orientPortrait, OrientationLock.LANDSCAPE to Copy.orientLandscape),
                s.orientation,
            ) { v -> onChange { it.copy(orientation = v) } }
            Header(Copy.settingsLinks)
            SwitchRow(Copy.confirmLinks, s.confirmExternalLinks) { v -> onChange { it.copy(confirmExternalLinks = v) } }
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
