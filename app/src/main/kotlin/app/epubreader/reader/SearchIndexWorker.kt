package app.epubreader.reader

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.epubreader.EpubReaderApp
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.zip.ZipReader
import app.epubreader.core.search.SearchIndexes
import app.epubreader.core.search.ZipBookText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finishes the search indexes that were left unfinished (a book closed mid-build, the process was killed) while the
 * device is idle and charging (5.1). Enqueued unique/KEEP when a book closes with an incomplete index.
 */
class SearchIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as EpubReaderApp).graph
        IndexFinisher(
            incomplete = graph.search::incomplete,
            filePath = graph.library::filePath,
            exists = { File(it).isFile },
            finish = { id, path -> finishIndex(graph.search, id, path) },
        ).run()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "search-index"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SearchIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).setRequiresCharging(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Opens the book file, builds (or resumes, or after a changed file rebuilds: [SearchIndexes.ensureBuilt] compares the
 * central-directory digest) its index and waits until the build ends.
 */
private suspend fun finishIndex(indexes: SearchIndexes, bookId: String, path: String) = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val zip = ZipReader.open(openFileSource(path))
    try {
        indexes.ensureBuilt(bookId, zip.centralDirectoryDigest) { ZipBookText(zip) }
        indexes.progress(bookId).first { it.complete || !it.running }
    } finally {
        zip.close()
    }
}

/**
 * The selection logic of [SearchIndexWorker]: every incomplete index whose book still has a readable file is finished;
 * a book without a file, or one that fails, is skipped and the others still run.
 */
class IndexFinisher(
    private val incomplete: () -> List<String>,
    private val filePath: suspend (bookId: String) -> String?,
    private val exists: (path: String) -> Boolean,
    private val finish: suspend (bookId: String, path: String) -> Unit,
    private val warn: (String) -> Unit = { android.util.Log.w("EPR", it) },
) {
    /** Returns the ids of the books that were finished without an error. */
    suspend fun run(): List<String> {
        val done = ArrayList<String>()
        for (id in incomplete()) {
            val path = try { filePath(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (path == null || !exists(path)) continue
            try {
                finish(id, path)
                done += id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("search index job: $id failed: $e")
            }
        }
        return done
    }
}
