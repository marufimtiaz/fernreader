package app.epubreader.core.data

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.CoroutineContext

/**
 * All platforms use the bundled SQLite (same version everywhere, FTS5 available).
 * [queryContext] runs Room queries; tests pass a test dispatcher so virtual time covers Room.
 */
fun buildDatabase(
    builder: RoomDatabase.Builder<AppDatabase>,
    queryContext: CoroutineContext = Dispatchers.IO,
): AppDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(queryContext)
    .build()
