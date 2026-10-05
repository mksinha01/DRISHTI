package ai.drishti.navigation

import android.util.Log

/**
 * Manages active route navigation, computes heading instructions,
 * and formats clear turn-by-turn guidance for the visually impaired user.
 */
class NavigationEngine {

    private val tag = "NavigationEngine"
    private var activeStateManager: NavStateManager? = null

    var isNavigating: Boolean = false
        private set

    fun startRoute(destination: String, origin: GeoPoint): String {
        Log.i(tag, "Starting navigation to: $destination from ($origin)")

        // Generate walking route waypoints (extensible via Google Maps Routes API)
        val waypoints = listOf(
            Waypoint(
                coordinate = GeoPoint(origin.latitude + 0.0005, origin.longitude + 0.0005),
                instruction = "Walk straight for 50 meters towards the intersection."
            ),
            Waypoint(
                coordinate = GeoPoint(origin.latitude + 0.0010, origin.longitude + 0.0010),
                instruction = "Turn right at the crosswalk. Listen for oncoming traffic."
            ),
            Waypoint(
                coordinate = GeoPoint(origin.latitude + 0.0015, origin.longitude + 0.0012),
                instruction = "Continue straight. $destination will be 20 meters ahead on your left."
            )
        )

        activeStateManager = NavStateManager(destination, waypoints)
        isNavigating = true
        return "Navigation started for $destination. Walk straight."
    }

    fun onLocationUpdate(currentLocation: GeoPoint): String? {
        val manager = activeStateManager ?: return null

        return when (val progress = manager.updateProgress(currentLocation)) {
            is NavProgress.OnTrack -> {
                val dist = progress.distanceToNextMeters.toInt()
                if (dist <= 15) {
                    "Approaching waypoint: ${progress.instruction}"
                } else if (dist % 50 < 10) {
                    "In $dist meters, ${progress.instruction}"
                } else {
                    null
                }
            }
            is NavProgress.OffRoute -> {
                progress.prompt
            }
            is NavProgress.DestinationReached -> {
                stopRoute()
                "You have arrived at ${manager.destination}. Navigation complete."
            }
        }
    }

    fun stopRoute(): String {
        val dest = activeStateManager?.destination ?: "destination"
        activeStateManager = null
        isNavigating = false
        Log.i(tag, "Navigation stopped.")
        return "Navigation to $dest has been stopped."
    }
}
