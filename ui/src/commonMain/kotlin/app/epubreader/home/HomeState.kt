package app.epubreader.home

import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.RecentBook
import app.epubreader.core.data.SourceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeState(private val library: LibraryRepository, private val scope: CoroutineScope) {
    val recent: StateFlow<List<RecentBook>> = library.recent().stateIn(scope, SharingStarted.Eagerly, emptyList())
    fun forget(ref: SourceRef) { scope.launch { library.forget(ref) } }
}
