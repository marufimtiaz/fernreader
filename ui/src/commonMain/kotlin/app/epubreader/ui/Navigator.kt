package app.epubreader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Route {
    /** F-001: why the app needs storage access, before any library UI. */
    data object Onboarding : Route
    data object Library : Route
    data object Reader : Route
}

/** Minimal route stack (master plan: no navigation library). Main thread only. */
class Navigator(start: Route = Route.Library) {
    private var stack by mutableStateOf(listOf(start))
    val top: Route get() = stack.last()
    fun push(r: Route) { stack = stack + r }
    fun pop(): Boolean = if (stack.size > 1) { stack = stack.dropLast(1); true } else false
    /** Replaces the bottom of the stack (e.g. onboarding → library once access is granted); routes above it stay. */
    fun replaceRoot(r: Route) { stack = listOf(r) + stack.drop(1) }
}
