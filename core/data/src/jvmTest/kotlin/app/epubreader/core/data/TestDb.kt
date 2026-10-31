package app.epubreader.core.data

import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** [queryContext]: tests that need `advanceUntilIdle()` to cover Room pass their test dispatcher (R15). */
fun testDb(queryContext: CoroutineContext = Dispatchers.IO): AppDatabase =
    buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()), queryContext)
