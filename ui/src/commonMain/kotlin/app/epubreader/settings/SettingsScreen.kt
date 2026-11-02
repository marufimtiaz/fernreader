package app.epubreader.settings

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.ui.Copy
import app.epubreader.ui.BarIcon
import app.epubreader.ui.theme.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer

/**
 * One entry of the settings screen (F-100). The screen is built from a list of [SettingsSection]s (see
 * `settingsSections`), so a later milestone adds its settings by adding items there; nothing else changes.
 */
sealed interface SettingItem {
    val id: String

    /** One value out of a few; the current one is shown under the title, a dialog changes it. */
    /** C-086: shown in front of the title. */
    val icon: ImageVector?

    data class Choice(override val id: String, val title: String, val options: List<Pair<String, String>>, val selected: String,
                      val onSelect: (String) -> Unit, val summary: String? = null, val enabled: Boolean = true,
                      override val icon: ImageVector? = null) : SettingItem
    data class Toggle(override val id: String, val title: String, val summary: String?, val checked: Boolean, val onChange: (Boolean) -> Unit,
                      override val icon: ImageVector? = null) : SettingItem
    data class Action(override val id: String, val title: String, val summary: String?, val onClick: () -> Unit,
                      override val icon: ImageVector? = null) : SettingItem
}

data class SettingsSection(val title: String, val items: List<SettingItem>)

/** A full-screen page with a back arrow and a title (settings, about, hidden books, licences, book details). */
@Composable
fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(AppIcons.Back, Copy.back, onClick = onBack)
                Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

@Composable
fun SettingsScreen(sections: List<SettingsSection>, onBack: () -> Unit) {
    var choosing by remember { mutableStateOf<SettingItem.Choice?>(null) }
    Page(Copy.settings, onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            sections.forEachIndexed { i, s ->
                if (i > 0) item(key = "div-${s.title}") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item(key = "h-${s.title}") {
                    Text(s.title, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                items(s.items, key = { it.id }) { item ->
                    when (item) {
                        is SettingItem.Choice -> Row2(item.icon, item.title, item.summary ?: item.options.firstOrNull { it.first == item.selected }?.second, item.enabled) { choosing = item }
                        is SettingItem.Action -> Row2(item.icon, item.title, item.summary, onClick = item.onClick)
                        is SettingItem.Toggle -> Row(
                            Modifier.fillMaxWidth().clickable { item.onChange(!item.checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LeadingIcon(item.icon)
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                item.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Switch(item.checked, item.onChange, Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
    choosing?.let { c ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(c.title) },
            text = {
                Column {
                    c.options.forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().selectable(value == c.selected, role = Role.RadioButton) { choosing = null; c.onSelect(value) }
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(value == c.selected, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = null }) { Text(Copy.cancel) } },
        )
    }
}

@Composable
private fun Row2(icon: ImageVector?, title: String, summary: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.38f).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIcon(icon)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** C-086: a settings row's icon (24 dp, in the secondary text colour), with the gap before the title. */
@Composable
private fun LeadingIcon(icon: ImageVector?) {
    if (icon == null) return
    Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(16.dp))
}
