package ai.drishti.navigation

import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double)

data class Waypoint(
    val coordinate: GeoPoint,
    val instruction: String,
    val distanceThresholdMeters: Float = 15.0f
)

sealed class NavProgress {
    data class OnTrack(
        val currentStepIndex: Int,
        val instruction: String,
        val distanceToNextMeters: Float,
        val bearingDegrees: Float
    ) : NavProgress()

    data class OffRoute(
        val deviationDistanceMeters: Float,
        val prompt: String = "You appear to be off route. Recalculating directions."
    ) : NavProgress()

    data object DestinationReached : NavProgress()
}

/**
 * Pure state manager for waypoint navigation and off-route detection.
 * Separated from Android framework for clean testing and modularity.
 */
class NavStateManager(
    val destination: String,
    val waypoints: List<Waypoint>
) {
    var currentStepIndex: Int = 0
        private set

    val isCompleted: Boolean
        get() = currentStepIndex >= waypoints.size

    fun updateProgress(currentLocation: GeoPoint): NavProgress {
        if (isCompleted || waypoints.isEmpty()) {
            return NavProgress.DestinationReached
        }

        val targetWaypoint = waypoints[currentStepIndex]
        val distanceToTarget = calculateDistanceMeters(currentLocation, targetWaypoint.coordinate)

        // Check if user reached this waypoint
        if (distanceToTarget <= targetWaypoint.distanceThresholdMeters) {
            currentStepIndex++
            if (isCompleted) {
                return NavProgress.DestinationReached
            }
            val nextWaypoint = waypoints[currentStepIndex]
            val newDist = calculateDistanceMeters(currentLocation, nextWaypoint.coordinate)
            val bearing = calculateBearing(currentLocation, nextWaypoint.coordinate)
            return NavProgress.OnTrack(
                currentStepIndex = currentStepIndex,
                instruction = nextWaypoint.instruction,
                distanceToNextMeters = newDist,
                bearingDegrees = bearing
            )
        }

        // Check for severe off-route deviation (> 50 meters from current target)
        if (distanceToTarget > 60.0f && currentStepIndex > 0) {
            return NavProgress.OffRoute(deviationDistanceMeters = distanceToTarget)
        }

        val bearing = calculateBearing(currentLocation, targetWaypoint.coordinate)
        return NavProgress.OnTrack(
            currentStepIndex = currentStepIndex,
            instruction = targetWaypoint.instruction,
            distanceToNextMeters = distanceToTarget,
            bearingDegrees = bearing
        )
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6371000.0

        fun calculateDistanceMeters(start: GeoPoint, end: GeoPoint): Float {
            val dLat = Math.toRadians(end.latitude - start.latitude)
            val dLon = Math.toRadians(end.longitude - start.longitude)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(start.latitude)) * cos(Math.toRadians(end.latitude)) *
                    sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return (EARTH_RADIUS_METERS * c).toFloat()
        }

        fun calculateBearing(start: GeoPoint, end: GeoPoint): Float {
            val lat1 = Math.toRadians(start.latitude)
            val lat2 = Math.toRadians(end.latitude)
            val dLon = Math.toRadians(end.longitude - start.longitude)

            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            val initialBearing = Math.toDegrees(atan2(y, x))
            return ((initialBearing + 360) % 360).toFloat()
        }
    }
}
