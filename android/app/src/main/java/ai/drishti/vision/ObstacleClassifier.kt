package ai.drishti.vision

import java.util.Locale

/**
 * Normalized bounding box coordinates [0.0 .. 1.0]
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun centerX(): Float = (left + right) / 2.0f
    fun centerY(): Float = (top + bottom) / 2.0f
    fun width(): Float = right - left
    fun height(): Float = bottom - top
}

/**
 * Raw detection result from object detector.
 */
data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: BoundingBox
)

enum class RelativePosition(val clockDirection: String, val spokenSide: String) {
    LEFT("10 o'clock", "on your left"),
    CENTER("12 o'clock", "directly ahead"),
    RIGHT("2 o'clock", "on your right")
}

data class ObstacleAlert(
    val label: String,
    val position: RelativePosition,
    val estimatedDistanceMeters: Float,
    val spokenWarning: String,
    val isPriority: Boolean
)

/**
 * Evaluates raw vision detections, filters hazards, and calculates
 * position and proximity warnings for the visually impaired user.
 */
object ObstacleClassifier {

    private val HAZARD_CLASSES = setOf(
        "person", "bicycle", "car", "motorcycle", "bus", "truck",
        "traffic light", "fire hydrant", "stop sign", "bench",
        "dog", "chair", "couch", "dining table"
    )

    private val HIGH_PRIORITY_CLASSES = setOf("car", "bus", "truck", "motorcycle", "person")

    /**
     * Filters detections and produces prioritized obstacle alerts.
     */
    fun classify(
        detections: List<Detection>,
        minConfidence: Float = 0.45f,
        maxAlertDistanceMeters: Float = 4.0f
    ): List<ObstacleAlert> {
        val alerts = mutableListOf<ObstacleAlert>()

        for (det in detections) {
            val labelLower = det.label.lowercase(Locale.ROOT)
            if (det.confidence < minConfidence || !HAZARD_CLASSES.contains(labelLower)) {
                continue
            }

            val box = det.boundingBox
            val position = calculatePosition(box.centerX())
            val distance = estimateDistance(box.height())

            if (distance <= maxAlertDistanceMeters) {
                val isHighPriority = HIGH_PRIORITY_CLASSES.contains(labelLower) && distance < 2.5f
                val warning = generateWarningMessage(labelLower, position, distance)

                alerts.add(
                    ObstacleAlert(
                        label = labelLower,
                        position = position,
                        estimatedDistanceMeters = distance,
                        spokenWarning = warning,
                        isPriority = isHighPriority
                    )
                )
            }
        }

        // Return sorted by proximity (closest obstacles first)
        return alerts.sortedBy { it.estimatedDistanceMeters }
    }

    fun calculatePosition(normalizedCenterX: Float): RelativePosition {
        return when {
            normalizedCenterX < 0.35f -> RelativePosition.LEFT
            normalizedCenterX > 0.65f -> RelativePosition.RIGHT
            else -> RelativePosition.CENTER
        }
    }

    /**
     * Empirical distance estimation based on object bounding box height in camera frame.
     * Normalized height = 1.0 (object fills view vertically ~0.8m away).
     */
    fun estimateDistance(normalizedHeight: Float): Float {
        val safeHeight = normalizedHeight.coerceIn(0.05f, 1.0f)
        val distance = 0.8f / safeHeight
        return (distance * 10).toInt() / 10.0f // rounded to 1 decimal place
    }

    private fun generateWarningMessage(label: String, position: RelativePosition, distance: Float): String {
        val distText = if (distance < 1.5f) "very close" else "about ${distance} meters"
        return "Caution, $label $distText ${position.spokenSide}."
    }
}
