package ai.drishti

import ai.drishti.navigation.GeoPoint
import ai.drishti.navigation.NavProgress
import ai.drishti.navigation.NavStateManager
import ai.drishti.navigation.Waypoint
import org.junit.Assert.*
import org.junit.Test

class NavStateManagerTest {

    @Test
    fun testDistanceAndBearingCalculation() {
        val start = GeoPoint(28.6139, 77.2090) // Connaught Place
        val end = GeoPoint(28.6149, 77.2090)   // North slightly

        val distance = NavStateManager.calculateDistanceMeters(start, end)
        assertTrue(distance > 100 && distance < 120)

        val bearing = NavStateManager.calculateBearing(start, end)
        // Directly north is ~0 or 360 degrees
        assertTrue(bearing < 10.0f || bearing > 350.0f)
    }

    @Test
    fun testWaypointProgression() {
        val waypoints = listOf(
            Waypoint(GeoPoint(28.6140, 77.2090), "Step 1: Walk forward", 10.0f),
            Waypoint(GeoPoint(28.6150, 77.2090), "Step 2: Turn right", 10.0f)
        )

        val nav = NavStateManager("Railway Station", waypoints)
        assertEquals(0, nav.currentStepIndex)
        assertFalse(nav.isCompleted)

        // User is at start
        val prog1 = nav.updateProgress(GeoPoint(28.6135, 77.2090))
        assertTrue(prog1 is NavProgress.OnTrack)
        assertEquals(0, (prog1 as NavProgress.OnTrack).currentStepIndex)

        // User reaches Step 1 waypoint
        val prog2 = nav.updateProgress(GeoPoint(28.6140, 77.2090))
        assertTrue(prog2 is NavProgress.OnTrack)
        assertEquals(1, (prog2 as NavProgress.OnTrack).currentStepIndex)

        // User reaches Step 2 waypoint (destination)
        val prog3 = nav.updateProgress(GeoPoint(28.6150, 77.2090))
        assertTrue(prog3 is NavProgress.DestinationReached)
        assertTrue(nav.isCompleted)
    }

    @Test
    fun testOffRouteDetection() {
        val waypoints = listOf(
            Waypoint(GeoPoint(28.6140, 77.2090), "Step 1", 10.0f),
            Waypoint(GeoPoint(28.6150, 77.2090), "Step 2", 10.0f)
        )

        val nav = NavStateManager("Destination", waypoints)
        // First reach waypoint 1
        nav.updateProgress(GeoPoint(28.6140, 77.2090))
        assertEquals(1, nav.currentStepIndex)

        // User deviates significantly (> 100m away in longitude)
        val offRouteProg = nav.updateProgress(GeoPoint(28.6140, 77.2200))
        assertTrue(offRouteProg is NavProgress.OffRoute)
    }
}
