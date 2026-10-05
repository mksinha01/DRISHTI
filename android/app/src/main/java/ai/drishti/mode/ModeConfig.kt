package ai.drishti.mode

/**
 * Holds configuration parameters and constraints for each operational mode.
 */
data class ModeConfig(
    val speechRate: Float = 1.0f,
    val obstacleInferenceIntervalMs: Long = 300L,
    val emergencyCountdownSeconds: Int = 10,
    val autoOcrCaptureDelayMs: Long = 1000L,
    val maxProximityAlertDistanceMeters: Float = 3.0f
) {
    companion object {
        fun defaultFor(mode: DrishtiMode): ModeConfig {
            return when (mode) {
                is DrishtiMode.Assistant -> ModeConfig(
                    speechRate = 1.0f,
                    obstacleInferenceIntervalMs = 300L
                )
                is DrishtiMode.Navigation -> ModeConfig(
                    speechRate = 1.05f,
                    obstacleInferenceIntervalMs = 200L,
                    maxProximityAlertDistanceMeters = 4.0f
                )
                is DrishtiMode.Ocr -> ModeConfig(
                    speechRate = 0.95f,
                    obstacleInferenceIntervalMs = 600L
                )
                is DrishtiMode.Emergency -> ModeConfig(
                    speechRate = 1.1f,
                    emergencyCountdownSeconds = 10
                )
            }
        }
    }
}
