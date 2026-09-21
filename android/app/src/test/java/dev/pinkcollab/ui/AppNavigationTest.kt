package dev.pinkcollab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test
    fun workspacesReturnsToTasks() {
        assertEquals(AppRoute.Tasks, AppRoute.Resources.back())
    }

    @Test
    fun browserReturnsToWorkspaces() {
        assertEquals(AppRoute.Resources, AppRoute.Browser("host", "F:/code").back())
    }

    @Test
    fun routeStateRoundTripsWithItsRequiredPayload() {
        val routes = listOf(
            AppRoute.Tasks,
            AppRoute.Resources,
            AppRoute.Browser("host-1", "F:/code/PinkCollab"),
        )

        routes.forEach { route -> assertEquals(route, restoreRoute(route.savedState())) }
    }

    @Test
    fun incompleteSavedRouteFallsBackToTasks() {
        assertEquals(AppRoute.Tasks, restoreRoute(listOf("browser", "host-without-path")))
    }

    @Test
    fun legacyCreateRouteResumesAtItsDirectory() {
        assertEquals(
            AppRoute.Browser("host-1", "F:/code/PinkCollab"),
            restoreRoute(listOf("create", "host-1", "F:/code/PinkCollab")),
        )
    }
}
