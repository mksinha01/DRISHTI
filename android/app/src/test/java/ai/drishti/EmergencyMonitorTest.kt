package ai.drishti

import ai.drishti.emergency.EmergencyMonitor
import org.junit.Assert.*
import org.junit.Test

class EmergencyMonitorTest {

    @Test
    fun testCancelEmergencyStopsAlert() {
        var tickCount = 0
        var alertFired = false
        var cancelled = false

        val monitor = EmergencyMonitor(
            onTick = { tickCount++ },
            onAlertFired = { alertFired = true },
            onCancelled = { cancelled = true }
        )

        assertFalse(monitor.isEmergencyActive)

        // Trigger
        monitor.triggerEmergency("voice_phrase", durationSeconds = 10)
        assertTrue(monitor.isEmergencyActive)
        assertTrue(tickCount > 0)

        // Cancel
        val result = monitor.cancelEmergency()
        assertTrue(result)
        assertFalse(monitor.isEmergencyActive)
        assertTrue(cancelled)
        assertFalse(alertFired)
    }

    @Test
    fun testDoubleCancellationReturnsFalse() {
        val monitor = EmergencyMonitor(
            onTick = {},
            onAlertFired = {},
            onCancelled = {}
        )

        // Not active, cancel should return false
        assertFalse(monitor.cancelEmergency())
    }
}
