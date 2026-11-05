package app.epubreader.reader.settings

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ReaderSheetTab
import app.epubreader.reader.display.DisplayTab
import app.epubreader.ui.Copy
import kotlinx.coroutines.flow.filterNotNull

/**
 * C-095: the reader's one settings sheet, tabs Display and General, switched by a tap or a sideways swipe. Anchored to
 * the bottom over the page (it never reflows it). One height for both tabs, the display panel's, tab row included, so
 * the page above stays visible as far as it did and changes preview live; the sheet does not jump between tabs. In a
 * short window it stops below the status bar, which keeps the tabs in reach. Coloured by the reading theme (F-074).
 * A tab's rows are composed only while it is on screen ([HorizontalPager] with no pages beyond the viewport).
 * [s]'s `sheetTab` is the tab to show; [onTab] reports a tap or a finished swipe.
 */
@Composable
fun ReaderSheet(
    s: ReaderSettings,
    fonts: Map<String, FontFamily>,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onTab: (ReaderSheetTab) -> Unit,
    modifier: Modifier = Modifier,
    /** C-004: Auto reading mode scrolls in landscape. */
    landscape: Boolean = false,
) {
    val tabs = ReaderSheetTab.entries
    val pager = rememberPagerState(initialPage = s.sheetTab.ordinal) { tabs.size }
    // Here, not in the tabs: a tab's rows go away when it leaves the screen, and it comes back where it was.
    val displayScroll = rememberScrollState()
    val generalScroll = rememberScrollState()
    LaunchedEffect(s.sheetTab) { if (pager.targetPage != s.sheetTab.ordinal) pager.animateScrollToPage(s.sheetTab.ordinal) }
    // Where the pager comes to rest is the tab, also when a swipe interrupted the animation to a tapped one.
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage.takeUnless { pager.isScrollInProgress } }.filterNotNull().collect { onTab(tabs[it]) }
    }
    Surface(
        modifier.statusBarsPadding().fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.navigationBarsPadding().heightIn(max = 460.dp)) {
            TabRow(selectedTabIndex = pager.targetPage, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                for (t in tabs) Tab(
                    selected = t.ordinal == pager.targetPage, onClick = { onTab(t) },
                    text = { Text(when (t) { ReaderSheetTab.DISPLAY -> Copy.display; ReaderSheetTab.GENERAL -> Copy.general }) },
                )
            }
            HorizontalPager(pager, Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
                when (tabs[page]) {
                    ReaderSheetTab.DISPLAY -> DisplayTab(s, fonts, onChange, displayScroll, landscape)
                    ReaderSheetTab.GENERAL -> GeneralTab(s, onChange, generalScroll)
                }
            }
        }
    }
}

/**
 * C-095: a sideways drag that starts here is not a tab swipe. Working sliders win the drag by themselves, as the inner
 * gesture; this covers a greyed-out slider, which ignores it, and a scrolling row at its end, which hands the rest on.
 */
internal fun Modifier.keepsSideways(): Modifier =
    nestedScroll(KeepSidewaysScroll).pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume() } }

private object KeepSidewaysScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available.copy(y = 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available.copy(y = 0f)
}
