package app.fernreader.settings

import app.fernreader.core.data.BackupException
import app.fernreader.core.data.BackupFileV1
import app.fernreader.core.data.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class BackupProblem { NOT_A_BACKUP, TOO_NEW, READ_FAILED, WRITE_FAILED, RESTORE_FAILED }

/** A file the user picked or opened from a file manager (F-090), waiting for "Restore". */
data class RestoreOffer(val file: BackupFileV1, val createdAt: Long, val books: Int, val collections: Int)

data class BackupUi(
    val offer: RestoreOffer? = null,
    val busy: Boolean = false,
    val result: RestoreResult? = null,
    val savedAs: String? = null,
    val problem: BackupProblem? = null,
)

/** F-090 dialogs: restore offer → result, save confirmation, and what went wrong. File I/O is the app's. */
class BackupState(
    private val parse: (String) -> BackupFileV1,
    private val restore: suspend (BackupFileV1) -> RestoreResult,
    /** Re-reads settings held in memory (reader, app, library) after a restore. */
    private val onRestored: suspend () -> Unit,
    private val scope: CoroutineScope,
    /** Where the file is parsed (R-005: a backup can be megabytes of JSON). */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui

    /** Checks [text] (parsed once, off the main thread) and offers to restore it, or says why it can't. */
    fun offer(text: String) {
        scope.launch {
            _ui.value = withContext(compute) {
                try {
                    val f = parse(text)
                    BackupUi(offer = RestoreOffer(f, f.createdAt, f.books.count { !it.deleted }, f.collections.count { !it.deleted }))
                } catch (e: BackupException.TooNew) {
                    BackupUi(problem = BackupProblem.TOO_NEW)
                } catch (e: BackupException) {
                    BackupUi(problem = BackupProblem.NOT_A_BACKUP)
                }
            }
        }
    }

    fun confirm() {
        val o = _ui.value.offer ?: return
        _ui.value = BackupUi(busy = true)
        scope.launch {
            _ui.value = try {
                val r = restore(o.file)
                onRestored()
                BackupUi(result = r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupUi(problem = BackupProblem.RESTORE_FAILED)
            }
        }
    }

    fun readFailed() { _ui.value = BackupUi(problem = BackupProblem.READ_FAILED) }
    fun saved(name: String) { _ui.value = BackupUi(savedAs = name) }
    fun saveFailed() { _ui.value = BackupUi(problem = BackupProblem.WRITE_FAILED) }
    fun dismiss() { _ui.value = BackupUi() }
}
