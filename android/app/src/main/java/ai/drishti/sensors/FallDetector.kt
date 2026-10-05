package ai.drishti.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Accelerometer-based fall detection heuristic:
 * Detects sudden impact (> 25 m/s² ~= 2.5g) followed by sudden stillness.
 */
class FallDetector(
    context: Context,
    private val onFallDetected: () -> Unit
) : SensorEventListener {

    private val tag = "FallDetector"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var impactDetected = false
    private var impactTimestamp = 0L

    companion object {
        const val IMPACT_THRESHOLD = 25.0f // m/s²
        const val STILLNESS_THRESHOLD = 11.5f // m/s² (near 1g gravity)
        const val STILLNESS_WINDOW_MS = 2000L
    }

    fun start() {
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(tag, "FallDetector registered with Accelerometer.")
        } else {
            Log.w(tag, "Accelerometer not available on this device.")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        impactDetected = false
        Log.i(tag, "FallDetector stopped.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        val now = System.currentTimeMillis()

        if (!impactDetected) {
            if (magnitude > IMPACT_THRESHOLD) {
                impactDetected = true
                impactTimestamp = now
                Log.w(tag, "Potential fall impact detected (magnitude: $magnitude)")
            }
        } else {
            // Check if stillness is observed within window
            if (now - impactTimestamp < STILLNESS_WINDOW_MS) {
                if (magnitude < STILLNESS_THRESHOLD) {
                    Log.w(tag, "Post-impact stillness verified. Triggering fall alert!")
                    impactDetected = false
                    onFallDetected()
                }
            } else {
                // Window expired without stillness confirmation
                impactDetected = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
