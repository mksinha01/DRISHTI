package ai.drishti.emergency

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages the emergency countdown window.
 * Provides a cancellation window before alerting contacts.
 */
class EmergencyMonitor(
    private val onTick: (secondsLeft: Int) -> Unit,
    private val onAlertFired: (reason: String) -> Unit,
    private val onCancelled: () -> Unit
) {

    private val tag = "EmergencyMonitor"
    private var handler: Handler? = null
    private var countdownSeconds = 10
    private var activeReason: String = ""
    var isEmergencyActive: Boolean = false
        private set

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!isEmergencyActive) return

            countdownSeconds--
            onTick(countdownSeconds)

            if (countdownSeconds <= 0) {
                isEmergencyActive = false
                Log.w(tag, "Emergency countdown expired. Firing alert: $activeReason")
                onAlertFired(activeReason)
            } else {
                handler?.postDelayed(this, 1000L)
            }
        }
    }

    fun triggerEmergency(reason: String, durationSeconds: Int = 10) {
        if (isEmergencyActive) {
            Log.d(tag, "Emergency already active.")
            return
        }

        isEmergencyActive = true
        activeReason = reason
        countdownSeconds = durationSeconds

        Log.w(tag, "Triggered emergency ($reason) with $durationSeconds second cancel window.")
        onTick(countdownSeconds)

        val looper = try {
            Looper.myLooper() ?: Looper.getMainLooper()
        } catch (e: Exception) {
            null
        }

        if (looper != null) {
            handler = Handler(looper)
            handler?.postDelayed(countdownRunnable, 1000L)
        }
    }

    fun cancelEmergency(): Boolean {
        if (!isEmergencyActive) {
            return false
        }

        isEmergencyActive = false
        handler?.removeCallbacks(countdownRunnable)
        handler = null
        Log.i(tag, "Emergency successfully cancelled by user.")
        onCancelled()
        return true
    }
}
