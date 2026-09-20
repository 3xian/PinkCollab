package dev.pinkcollab.ui

import androidx.compose.runtime.saveable.Saver

internal sealed interface AppRoute {
    data object Tasks : AppRoute
    data object Resources : AppRoute
    data class Browser(val hostId: String, val path: String) : AppRoute
    data class CreateTask(val hostId: String, val cwd: String) : AppRoute
    data class PairHost(val url: String = "", val token: String = "") : AppRoute
}

internal fun AppRoute.back(): AppRoute = when (this) {
    AppRoute.Tasks -> AppRoute.Tasks
    AppRoute.Resources -> AppRoute.Tasks
    is AppRoute.PairHost -> AppRoute.Resources
    is AppRoute.Browser -> AppRoute.Resources
    is AppRoute.CreateTask -> AppRoute.Browser(hostId, cwd)
}

internal fun AppRoute.savedState(): List<String> = when (this) {
    AppRoute.Tasks -> listOf("tasks")
    AppRoute.Resources -> listOf("resources")
    is AppRoute.Browser -> listOf("browser", hostId, path)
    is AppRoute.CreateTask -> listOf("create", hostId, cwd)
    is AppRoute.PairHost -> listOf("pair", url, token)
}

internal fun restoreRoute(state: List<String>): AppRoute = when (state.firstOrNull()) {
    "resources" -> AppRoute.Resources
    "browser" -> state.getOrNull(1)?.let { hostId ->
        state.getOrNull(2)?.let { path -> AppRoute.Browser(hostId, path) }
    } ?: AppRoute.Tasks
    "create" -> state.getOrNull(1)?.let { hostId ->
        state.getOrNull(2)?.let { cwd -> AppRoute.CreateTask(hostId, cwd) }
    } ?: AppRoute.Tasks
    "pair" -> AppRoute.PairHost(state.getOrNull(1).orEmpty(), state.getOrNull(2).orEmpty())
    else -> AppRoute.Tasks
}

internal val AppRouteSaver = Saver<AppRoute, List<String>>(
    save = { it.savedState() },
    restore = ::restoreRoute,
)
