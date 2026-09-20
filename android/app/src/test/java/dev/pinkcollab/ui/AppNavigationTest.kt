package dev.pinkcollab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test
    fun workspacesReturnsToTasks() {
        assertEquals(AppRoute.Tasks, AppRoute.Resources.back())
    }

    @Test
    fun nestedPagesReturnOneLevelAtATime() {
        assertEquals(AppRoute.Browser("host", "F:/code"), AppRoute.CreateTask("host", "F:/code").back())
        assertEquals(AppRoute.Resources, AppRoute.Browser("host", "F:/code").back())
        assertEquals(AppRoute.Resources, AppRoute.PairHost().back())
    }

    @Test
    fun routeStateRoundTripsWithItsRequiredPayload() {
        val routes = listOf(
            AppRoute.Tasks,
            AppRoute.Resources,
            AppRoute.Browser("host-1", "F:/code/PinkCollab"),
            AppRoute.CreateTask("host-1", "F:/code/PinkCollab"),
            AppRoute.PairHost("https://gateway.example", "secret"),
        )

        routes.forEach { route -> assertEquals(route, restoreRoute(route.savedState())) }
    }

    @Test
    fun incompleteSavedRouteFallsBackToTasks() {
        assertEquals(AppRoute.Tasks, restoreRoute(listOf("browser", "host-without-path")))
    }
}
