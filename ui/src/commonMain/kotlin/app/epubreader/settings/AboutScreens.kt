package app.epubreader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.LibraryBook
import app.epubreader.ui.Copy

/** F-100 about: version, privacy (F-092, F-110), licences. */
@Composable
fun AboutScreen(version: String, onLicences: () -> Unit, onBack: () -> Unit) {
    Page(Copy.about, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Copy.appName, style = MaterialTheme.typography.titleLarge)
            Text(Copy.version(version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Copy.aboutBody, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text(Copy.privacyTitle, style = MaterialTheme.typography.titleMedium)
            Copy.privacyPoints.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
            HorizontalDivider()
            Text(Copy.licencesTitle, style = MaterialTheme.typography.titleMedium)
            Text(Copy.licencesBody, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onLicences) { Text(Copy.showLicences) }
        }
    }
}

/** docs/licenses.md, shipped in the app's assets. */
@Composable
fun LicencesScreen(blocks: List<LicenceBlock>, onBack: () -> Unit) {
    Page(Copy.licencesTitle, onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(blocks) { b ->
                when (b) {
                    is LicenceBlock.Heading -> Text(b.text, Modifier.padding(top = 8.dp),
                        style = if (b.level == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                    is LicenceBlock.Paragraph -> Text(b.text, style = MaterialTheme.typography.bodyMedium)
                    is LicenceBlock.Row -> Column {
                        Text(b.cells.first(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        b.cells.drop(1).zip(b.header.drop(1)).filter { it.first.isNotEmpty() }.forEach { (v, h) ->
                            Text("$h: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** F-025: books removed from the library only; "Show" brings one back. */
@Composable
fun HiddenBooksScreen(books: List<LibraryBook>, onUnhide: (String) -> Unit, onBack: () -> Unit) {
    Page(Copy.hiddenBooks, onBack) {
        if (books.isEmpty()) Text(Copy.noHiddenBooks, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
        LazyColumn(Modifier.fillMaxSize()) {
            items(books, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Copy.displayFolder(b.path.substringBeforeLast('/')), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                    TextButton(onClick = { onUnhide(b.id) }) { Text(Copy.showAgain) }
                }
                HorizontalDivider()
            }
        }
    }
}
