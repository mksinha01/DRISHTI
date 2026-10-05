package ai.drishti.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import ai.drishti.navigation.GeoPoint

/**
 * FusedLocationProviderClient wrapper for location tracking and single-shot queries.
 */
class LocationProvider(context: Context) {

    private val tag = "LocationProvider"
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null

    var lastKnownLocation: GeoPoint? = null
        private set

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(intervalMs: Long = 3000L, onLocationReceived: (GeoPoint) -> Unit) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                val point = GeoPoint(loc.latitude, loc.longitude)
                lastKnownLocation = point
                onLocationReceived(point)
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            Log.i(tag, "Requested continuous high-accuracy location updates.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start location updates", e)
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
            locationCallback = null
            Log.i(tag, "Stopped location updates.")
        }
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocationSingleShot(onResult: (GeoPoint) -> Unit) {
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val point = GeoPoint(loc.latitude, loc.longitude)
                    lastKnownLocation = point
                    onResult(point)
                } else {
                    // Fallback to default coordinates (New Delhi)
                    onResult(lastKnownLocation ?: GeoPoint(28.6139, 77.2090))
                }
            }.addOnFailureListener {
                onResult(lastKnownLocation ?: GeoPoint(28.6139, 77.2090))
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed single shot location request", e)
            onResult(lastKnownLocation ?: GeoPoint(28.6139, 77.2090))
        }
    }
}
