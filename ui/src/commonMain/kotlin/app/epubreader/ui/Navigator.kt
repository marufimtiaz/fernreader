package app.epubreader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Route {
    data object Home : Route
    data object Reader : Route
}

/** Minimal route stack (master plan: no navigation library). Main thread only. */
class Navigator {
    private var stack by mutableStateOf(listOf<Route>(Route.Home))
    val top: Route get() = stack.last()
    fun push(r: Route) { stack = stack + r }
    fun pop(): Boolean = if (stack.size > 1) { stack = stack.dropLast(1); true } else false
}
