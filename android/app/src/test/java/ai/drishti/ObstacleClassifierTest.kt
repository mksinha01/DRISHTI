package ai.drishti

import ai.drishti.vision.BoundingBox
import ai.drishti.vision.Detection
import ai.drishti.vision.ObstacleClassifier
import ai.drishti.vision.RelativePosition
import org.junit.Assert.*
import org.junit.Test

class ObstacleClassifierTest {

    @Test
    fun testPositionCalculation() {
        val left = ObstacleClassifier.calculatePosition(0.2f)
        assertEquals(RelativePosition.LEFT, left)

        val center = ObstacleClassifier.calculatePosition(0.5f)
        assertEquals(RelativePosition.CENTER, center)

        val right = ObstacleClassifier.calculatePosition(0.8f)
        assertEquals(RelativePosition.RIGHT, right)
    }

    @Test
    fun testDistanceEstimation() {
        // Full height object = close distance
        val distClose = ObstacleClassifier.estimateDistance(0.8f)
        assertTrue(distClose < 1.5f)

        // Small height object = far distance
        val distFar = ObstacleClassifier.estimateDistance(0.2f)
        assertTrue(distFar > 3.0f)
    }

    @Test
    fun testHazardFiltering() {
        val detections = listOf(
            // Non-hazard object
            Detection(label = "banana", confidence = 0.9f, boundingBox = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f)),
            // Low confidence hazard
            Detection(label = "car", confidence = 0.2f, boundingBox = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f)),
            // Valid high confidence hazard
            Detection(label = "person", confidence = 0.85f, boundingBox = BoundingBox(0.4f, 0.1f, 0.6f, 0.9f))
        )

        val alerts = ObstacleClassifier.classify(detections)
        assertEquals(1, alerts.size)
        assertEquals("person", alerts[0].label)
        assertEquals(RelativePosition.CENTER, alerts[0].position)
        assertTrue(alerts[0].spokenWarning.contains("person"))
    }

    @Test
    fun testPriorityAlertForCloseVehicle() {
        val carDetection = listOf(
            Detection(label = "car", confidence = 0.88f, boundingBox = BoundingBox(0.1f, 0.1f, 0.3f, 0.9f))
        )

        val alerts = ObstacleClassifier.classify(carDetection)
        assertEquals(1, alerts.size)
        assertTrue(alerts[0].isPriority)
        assertEquals(RelativePosition.LEFT, alerts[0].position)
    }
}
