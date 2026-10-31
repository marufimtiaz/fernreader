package app.epubreader.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.display.DisplayPanel
import app.epubreader.reader.display.ReadingTheme
import app.epubreader.reader.search.SearchPanel
import app.epubreader.reader.contents.ContentsPanel
import app.epubreader.reader.controls.BookmarkFilledIcon
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.epubreader.ui.Copy
import app.epubreader.reader.controls.ControlActions
import app.epubreader.reader.controls.ReaderBottomBar
import app.epubreader.reader.controls.ReaderTopBar
import app.epubreader.reader.settings.ReaderSettingsSheet
import app.epubreader.reader.selection.HighlightColorRow
import app.epubreader.reader.annotations.AnnotationSheetPanel
import app.epubreader.reader.contents.HighlightsList

/**
 * Reader chrome drawn over the WebView (which lies underneath, full size). Nothing here reflows the page.
 * While loading or on error the screen is opaque (P-015: frame + progress at once). The controls overlay is in [app.epubreader.reader.controls].
 */
@Composable
fun ReaderScreen(
    c: ReaderController, onBack: () -> Unit, onForget: (SourceRef) -> Unit, probe: EventProbe, fonts: Map<String, FontFamily> = emptyMap(),
    /** Bookmark dates (locale and time zone are the platform's). */ formatDate: (Long) -> String = { it.toString() },
) {
    val s by c.state.collectAsState()
    val settings by c.settings.collectAsState()
    val page by c.page.collectAsState()
    LaunchedEffect(Unit) { withFrameNanos { }; probe.ev("reader_frame", "") }
    // F-074: the reader's own surfaces (loading screen, bars, panels) follow the reading theme, not the app theme.
    MaterialTheme(colorScheme = ReadingTheme.of(settings.theme).colorScheme()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        LaunchedEffect(landscape) { c.setLandscape(landscape) } // C-004: AUTO flow follows the orientation
        when {
            s.error != null -> Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    val err = s.error
                    Text(when (err) { is ReaderError.Open -> Copy.openFailure(err.reason); ReaderError.FileChanged -> Copy.fileChanged; else -> Copy.engineError }, style = MaterialTheme.typography.bodyLarge)
                    if (err is ReaderError.Engine) Text(err.message, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onBack) { Text(Copy.back) }
                        if (err is ReaderError.Open && err.reason == OpenFailure.NO_ACCESS) s.source?.let { src ->
                            TextButton(onClick = { onForget(src); onBack() }) { Text(Copy.removeFromRecent) }
                        }
                    }
                }
            }
            s.loading -> Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        val actions = ControlActions(
            back = onBack, search = c::showSearch, bookmark = c::toggleBookmark, contents = c::showToc,
            display = c::showDisplay, settings = c::showSettings, seek = c::seek,
        )
        if (s.chromeVisible && s.error == null) {
            ReaderTopBar(chapterTitle(s.toc, s.currentTocHref, s.title), s.title, s.bookmarked, actions, Modifier.align(Alignment.TopStart))
        }
        // F-041: the page marker, over the page's top-right corner; visible without the controls, never moves the text.
        if (s.bookmarked && !s.chromeVisible && !s.loading && s.error == null) {
            Icon(
                BookmarkFilledIcon, Copy.bookmarkedPage,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp).size(36.dp).offset(y = (-6).dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // F-034: always visible, in the page's bottom margin (32 px). Paged mode keeps that margin free of text; in scroll
        // mode text scrolls through it, so the label sits on a strip of page colour and the text passes under it.
        if (!s.loading && s.error == null) Copy.pageLabel(page)?.let { label ->
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().height(32.dp)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
        }
        if (s.chromeVisible && s.error == null) {
            ReaderBottomBar(Copy.pageLabel(page), s.fraction, actions, Modifier.align(Alignment.BottomCenter))
        }
        if (s.displayVisible && s.error == null) {
            LaunchedEffect(Unit) { withFrameNanos { }; probe.ev("display_shown", "") }
            DisplayPanel(settings, fonts, onChange = c::updateSettings, modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (s.settingsVisible && s.error == null) ReaderSettingsSheet(settings, onChange = c::updateSettings, modifier = Modifier.align(Alignment.BottomCenter))
        if (s.tocVisible) ContentsPanel(
            s.title, s.contentsTab, c::selectContentsTab, s.toc, s.currentTocHref, c::onTocSelected,
            s.bookmarks, page, formatDate, c::onBookmarkSelected, c::removeBookmark, c::hideToc, probe,
            highlights = { HighlightsList(s.annotations, c::onAnnotationSelected) { a -> c.removeAnnotation(a.id) } },
        )
        if (s.searchVisible && s.error == null) SearchPanel(c.searcher, onDismiss = c::hideSearch)
        // F-060: after Highlight/Underline, one more tap gives the new annotation another of the six colours.
        s.highlightPicker?.let { p ->
            LaunchedEffect(p) { kotlinx.coroutines.delay(8_000); c.dismissHighlightPicker() } // it goes away on its own too
            if (s.error == null) HighlightColorRow(p.color, c::recolorHighlight, Modifier.align(Alignment.BottomCenter))
        }
        // F-061: the sheet of a tapped highlight (over the page, like the display panel).
        s.annotationSheet?.let { sh ->
            if (s.error == null) AnnotationSheetPanel(
                sh, onColor = c::setAnnotationColor, onStyle = c::setAnnotationStyle, onNote = c::editNote,
                onRemove = { c.removeAnnotation(sh.id) }, onDone = c::closeAnnotationSheet, modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        s.notice?.let {
            val undo = it == Notice.HighlightRemoved
            LaunchedEffect(it) { kotlinx.coroutines.delay(if (undo) 5_000 else 3_000); c.dismissNotice() }
            Snackbar(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                action = if (undo) { { TextButton(onClick = c::undoRemoveAnnotation) { Text(Copy.undo) } } } else null,
            ) {
                Text(when (it) { Notice.HitNotFound -> Copy.hitNotFound; Notice.NoAppForLink -> Copy.noAppForLink; Notice.MissingTarget -> Copy.missingTarget
                    Notice.BookmarkAdded -> Copy.bookmarkAdded; Notice.BookmarkRemoved -> Copy.bookmarkRemoved; Notice.HighlightRemoved -> Copy.highlightRemoved })
            }
        }
        // F-044: confirmation naming the link's host; Back or a tap outside cancels.
        s.externalLink?.let { l ->
            AlertDialog(
                onDismissRequest = c::cancelExternalLink,
                title = { Text(Copy.openLinkTitle(l.label, l.mail)) },
                text = { Text(Copy.openLinkBody(l.mail)) },
                confirmButton = { TextButton(onClick = c::confirmExternalLink) { Text(Copy.openLink) } },
                dismissButton = { TextButton(onClick = c::cancelExternalLink) { Text(Copy.cancel) } },
            )
        }
    }
    }
}
