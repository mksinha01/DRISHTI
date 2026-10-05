package ai.drishti.mode

import android.util.Log

/**
 * Type-safe state representation of Drishti's operational modes.
 */
sealed class DrishtiMode {
    abstract val name: String
    abstract val spokenConfirmation: String

    data object Assistant : DrishtiMode() {
        override val name: String = "ASSISTANT"
        override val spokenConfirmation: String = "Assistant mode active. How can I help you?"
    }

    data class Navigation(val destination: String? = null) : DrishtiMode() {
        override val name: String = "NAVIGATION"
        override val spokenConfirmation: String =
            if (destination != null) "Navigation mode active. Guiding to $destination."
            else "Navigation mode active. Where would you like to go?"
    }

    data class Ocr(val readingStatus: String = "ready") : DrishtiMode() {
        override val name: String = "OCR"
        override val spokenConfirmation: String = "Reading mode active. Point camera at text."
    }

    data class Emergency(val triggerReason: String, val secondsLeft: Int = 10) : DrishtiMode() {
        override val name: String = "EMERGENCY"
        override val spokenConfirmation: String = "Emergency alert initiated. Say cancel to abort."
    }
}

/**
 * State machine managing operational mode transitions and listener notifications.
 */
class ModeManager(initialMode: DrishtiMode = DrishtiMode.Assistant) {

    private val tag = "ModeManager"

    var currentMode: DrishtiMode = initialMode
        private set

    var currentConfig: ModeConfig = ModeConfig.defaultFor(initialMode)
        private set

    private var previousMode: DrishtiMode = DrishtiMode.Assistant

    private val listeners = mutableListOf<ModeChangeListener>()

    fun interface ModeChangeListener {
        fun onModeChanged(oldMode: DrishtiMode, newMode: DrishtiMode, spokenText: String)
    }

    fun addListener(listener: ModeChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ModeChangeListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun switchTo(newMode: DrishtiMode): Boolean {
        if (currentMode == newMode) {
            Log.d(tag, "Already in mode: ${newMode.name}")
            return false
        }

        // Keep track of non-emergency mode to restore upon cancellation
        if (currentMode !is DrishtiMode.Emergency) {
            previousMode = currentMode
        }

        val oldMode = currentMode
        currentMode = newMode
        currentConfig = ModeConfig.defaultFor(newMode)

        Log.i(tag, "Mode switched from ${oldMode.name} to ${newMode.name}")

        val spoken = newMode.spokenConfirmation
        notifyListeners(oldMode, newMode, spoken)
        return true
    }

    @Synchronized
    fun cancelEmergency(): Boolean {
        if (currentMode is DrishtiMode.Emergency) {
            Log.i(tag, "Emergency cancelled. Restoring previous mode: ${previousMode.name}")
            val oldMode = currentMode
            currentMode = previousMode
            currentConfig = ModeConfig.defaultFor(currentMode)
            notifyListeners(oldMode, currentMode, "Emergency alert cancelled. Returned to ${currentMode.name} mode.")
            return true
        }
        return false
    }

    private fun notifyListeners(oldMode: DrishtiMode, newMode: DrishtiMode, spoken: String) {
        for (listener in listeners) {
            try {
                listener.onModeChanged(oldMode, newMode, spoken)
            } catch (e: Exception) {
                Log.e(tag, "Error notifying mode listener", e)
            }
        }
    }
}
