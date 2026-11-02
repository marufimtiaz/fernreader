package app.epubreader.reader.search

import app.epubreader.ui.BarIcon
import app.epubreader.ui.IconLabel
import app.epubreader.ui.theme.AppIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.epubreader.ui.Copy

private sealed interface ListRow {
    data class Header(val label: String, val key: String) : ListRow
    data class Hit(val result: SearchResult, val key: String) : ListRow
}

/** A chapter header row whenever the label changes; hits before the first contents entry have none. */
private fun rowsOf(results: List<SearchResult>): List<ListRow> {
    val out = ArrayList<ListRow>(results.size + 16)
    var last: String? = null
    for (r in results) {
        val key = "${r.hit.spineIndex}:${r.hit.offset}"
        if (r.chapter != last && r.chapter != null) out += ListRow.Header(r.chapter, "c:$key")
        last = r.chapter
        out += ListRow.Hit(r, "h:$key")
    }
    return out
}

/**
 * Full-screen in-book search (F-050): field, whole-word chip, count line, results grouped by chapter. Results appear
 * as batches arrive. Coloured by the active reading theme.
 */
@Composable
fun SearchPanel(
    search: ReaderSearch, onDismiss: () -> Unit,
    /** C-083: a hit's book page label ([spineIndex], [offset] original) while Book pages are shown, else null. */
    pageOf: (Int, Int) -> String? = { _, _ -> null },
) {
    val st by search.state.collectAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalFocusManager.current
    val rows = remember(st.results) { rowsOf(st.results) }
    remember { search.shownRun(st.runId) } // a different search than last shown drops the saved position
    val list = rememberLazyListState(search.scrollIndex, search.scrollOffset)
    DisposableEffect(Unit) { onDispose { search.scrollIndex = list.firstVisibleItemIndex; search.scrollOffset = list.firstVisibleItemScrollOffset } }
    LaunchedEffect(Unit) { if (st.query.isBlank()) runCatching { focus.requestFocus() } }
    LaunchedEffect(st.runId) { if (search.shownRun(st.runId)) list.scrollToItem(0) }
    LaunchedEffect(st.runId, st.results.isNotEmpty()) { if (st.results.isNotEmpty()) { withFrameNanos { }; search.reportFirst(st.runId) } }
    LaunchedEffect(st.runId, st.done) { if (st.done) { withFrameNanos { }; search.reportDone(st.runId, st.total) } }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = st.query, onValueChange = search::setQuery,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    placeholder = { Text(Copy.searchInBook) },
                    trailingIcon = if (st.query.isNotEmpty()) ({
                        BarIcon(AppIcons.Close, Copy.clearSearch) { search.setQuery(""); runCatching { focus.requestFocus() } }
                    }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search.submit(); keyboard.clearFocus() }),
                )
                TextButton(onClick = onDismiss) { IconLabel(AppIcons.Close, Copy.close) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = st.wholeWord, onClick = search::toggleWholeWord, label = { Text(Copy.wholeWords) })
                if (st.submitted != null) Text(Copy.resultCount(st.total, st.capped, st.running), style = MaterialTheme.typography.labelLarge)
            }
            if (st.submitted == null) Recent(st.history, onPick = search::submit, onClear = search::clearHistory)
            else LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { if (it is ListRow.Hit) it.key else (it as ListRow.Header).key }) { row ->
                    when (row) {
                        is ListRow.Header -> Text(
                            row.label, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        is ListRow.Hit -> Column(Modifier.fillMaxWidth().clickable { search.select(row.result) }) {
                            Text(snippet(row.result), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            pageOf(row.result.hit.spineIndex, row.result.hit.offset)?.let { label ->
                                Text(Copy.bookPage(label), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun snippet(r: SearchResult): AnnotatedString {
    val h = r.hit
    val s = h.snippet
    val a = h.snippetHitStart.coerceIn(0, s.length)
    val b = h.snippetHitEnd.coerceIn(a, s.length)
    val mark = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
    return buildAnnotatedString {
        append(s.substring(0, a))
        withStyle(mark) { append(s.substring(a, b)) }
        append(s.substring(b))
    }
}

@Composable
private fun Recent(history: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (history.isEmpty()) {
        Text(Copy.noRecentSearches, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Copy.recentSearches, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onClear) { IconLabel(AppIcons.ClearHistory, Copy.clearHistory) }
            }
        }
        items(history, key = { it }) { q ->
            Text(q, Modifier.fillMaxWidth().clickable { onPick(q) }.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
