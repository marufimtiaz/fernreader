package app.epubreader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.reader.ReaderEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-build smoke test (tools/smoke.sh). Functional only, no timing assertions (C-015).
 * M1: the app starts and the pre-warmed reader host page becomes ready. M2 extends it (open, turn, back).
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test fun app_starts_and_reader_host_is_ready(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var engine: ReaderEngine
            sc.onActivity { engine = it.reader }
            withTimeout(15_000) { engine.isReady.first { it } }
        }
    }
}
