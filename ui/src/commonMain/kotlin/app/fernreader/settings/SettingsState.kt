package app.fernreader.settings

import app.fernreader.core.data.LibraryBook
import app.fernreader.core.data.SettingsPort
import app.fernreader.core.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** F-011: books whose files all lie outside the newly chosen folder; the user may hide them (reading data is kept). */
data class OutsideOffer(val folder: String, val bookIds: List<String>)

data class SettingsUi(
    /** F-011 discovery folder; null = everywhere. */
    val folder: String? = null,
    val outside: OutsideOffer? = null,
    /** F-025 books removed from the library only. */
    val hidden: List<LibraryBook> = emptyList(),
)

/** State behind the library parts of the settings screen (F-011, F-025 un-hide). Host-tested. */
class SettingsState(
    private val store: SettingsPort,
    private val books: Flow<List<LibraryBook>>,
    hidden: Flow<List<LibraryBook>>,
    private val hide: suspend (List<String>) -> Unit,
    private val unhide: suspend (List<String>) -> Unit,
    private val rescan: () -> Unit,
    private val scope: CoroutineScope,
) {
    private val local = MutableStateFlow(SettingsUi())

    val ui: StateFlow<SettingsUi> = combine(local, hidden) { l, h -> l.copy(hidden = h) }.stateIn(scope, SharingStarted.Eagerly, SettingsUi())

    init {
        scope.launch { local.value = local.value.copy(folder = store.get(SettingsStore.LIBRARY_SCOPE)?.takeIf { it != EVERYWHERE }) }
    }

    /** F-011: scans now look only in [path]; books already listed from elsewhere are offered for hiding, never dropped. */
    fun chooseFolder(path: String) {
        val dir = path.trimEnd('/').ifEmpty { "/" }
        local.value = local.value.copy(folder = dir, outside = null)
        scope.launch {
            store.put(SettingsStore.LIBRARY_SCOPE, dir)
            val outside = books.first().filter { b ->
                val local = b.paths.filter { it.startsWith("/") }
                local.isNotEmpty() && local.none { it.startsWith("$dir/") }
            }.map { it.id }
            if (outside.isNotEmpty()) local.value = local.value.copy(outside = OutsideOffer(dir, outside))
            rescan()
        }
    }

    fun useEverywhere() {
        local.value = local.value.copy(folder = null, outside = null)
        scope.launch { store.put(SettingsStore.LIBRARY_SCOPE, EVERYWHERE); rescan() }
    }

    fun hideOutside() {
        val o = local.value.outside ?: return
        local.value = local.value.copy(outside = null)
        scope.launch { hide(o.bookIds) }
    }

    fun keepOutside() { local.value = local.value.copy(outside = null) }

    fun unhide(bookId: String) { scope.launch { unhide(listOf(bookId)) } }

    /** Re-reads stored values (after a restore). */
    fun reload() { scope.launch { local.value = local.value.copy(folder = store.get(SettingsStore.LIBRARY_SCOPE)?.takeIf { it != EVERYWHERE }) } }

    companion object { const val EVERYWHERE = "everywhere" }
}

/** F-011: the system folder picker returns a tree URI; its document id names the folder on shared storage. */
object FolderPaths {
    /** "primary:Books" → "/storage/emulated/0/Books"; "1A2B-3C4D:x" → "/storage/1A2B-3C4D/x"; other providers → null. */
    fun fromTreeDocumentId(id: String): String? {
        val volume = id.substringBefore(':', missingDelimiterValue = "")
        if (volume.isEmpty()) return null
        val rel = id.substringAfter(':').trim('/')
        val base = when {
            volume == "primary" -> "/storage/emulated/0"
            volume == "home" -> "/storage/emulated/0/Documents"
            Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$").matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}
