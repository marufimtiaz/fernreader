package app.epubreader.home

import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.SourceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Stub until Task 2.6 (recent books). */
class HomeState(private val library: LibraryRepository, private val scope: CoroutineScope) {
    fun forget(ref: SourceRef) { scope.launch { library.forget(ref) } }
}
