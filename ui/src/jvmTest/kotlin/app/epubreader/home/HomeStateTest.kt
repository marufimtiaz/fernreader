package app.epubreader.home

import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeStateTest {
    @Test fun recent_lists_opened_books_and_forget_removes_them() = runTest {
        val db = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()), StandardTestDispatcher(testScheduler))
        val repo = LibraryRepository(db, HybridClock(System::currentTimeMillis))
        val home = HomeState(repo, backgroundScope)
        val ref = SourceRef.Content("content://p/1")
        repo.recordOpened(OpenedBookInfo("id-1", "Alice", listOf("Carroll"), ref, 1))
        assertEquals(listOf("Alice"), home.recent.first { it.isNotEmpty() }.map { it.title })
        home.forget(ref)
        home.recent.first { it.isEmpty() }
    }
}
