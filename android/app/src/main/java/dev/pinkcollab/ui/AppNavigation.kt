package dev.pinkcollab.ui

import androidx.compose.runtime.saveable.Saver

internal sealed interface AppRoute {
    data object Tasks : AppRoute
    data object Resources : AppRoute
    data class Browser(val hostId: String, val path: String) : AppRoute
}

internal fun AppRoute.back(): AppRoute = when (this) {
    AppRoute.Tasks -> AppRoute.Tasks
    AppRoute.Resources -> AppRoute.Tasks
    is AppRoute.Browser -> AppRoute.Resources
}

internal fun AppRoute.savedState(): List<String> = when (this) {
    AppRoute.Tasks -> listOf("sessions")
    AppRoute.Resources -> listOf("resources")
    is AppRoute.Browser -> listOf("browser", hostId, path)
}

internal fun restoreRoute(state: List<String>): AppRoute = when (state.firstOrNull()) {
    "sessions" -> AppRoute.Tasks
    "resources" -> AppRoute.Resources
    "browser" -> state.getOrNull(1)?.let { hostId ->
        state.getOrNull(2)?.let { path -> AppRoute.Browser(hostId, path) }
    } ?: AppRoute.Tasks
    // Older builds persisted the removed first-prompt page as "create". Resume at its
    // directory instead of discarding the user's navigation state after an upgrade.
    "create" -> state.getOrNull(1)?.let { hostId ->
        state.getOrNull(2)?.let { cwd -> AppRoute.Browser(hostId, cwd) }
    } ?: AppRoute.Tasks
    else -> AppRoute.Tasks
}

internal val AppRouteSaver = Saver<AppRoute, List<String>>(
    save = { it.savedState() },
    restore = ::restoreRoute,
)
