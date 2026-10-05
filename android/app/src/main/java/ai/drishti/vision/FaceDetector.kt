package ai.drishti.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class FaceInfo(
    val boundingBox: Rect,
    val trackingId: Int?
)

/**
 * On-device face detection using Google ML Kit.
 * Respects user privacy: only detects presence and position; no photos or embeddings saved without consent.
 */
class FaceDetector {

    private val tag = "FaceDetector"

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .build()
    )

    suspend fun detectFaces(bitmap: Bitmap): List<FaceInfo> = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { faces: List<Face> ->
                    val faceList = faces.map { face ->
                        FaceInfo(
                            boundingBox = face.boundingBox,
                            trackingId = face.trackingId
                        )
                    }
                    continuation.resume(faceList)
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Face detection failed", e)
                    continuation.resume(emptyList())
                }
        } catch (e: Exception) {
            Log.e(tag, "Error processing image for face detection", e)
            continuation.resume(emptyList())
        }
    }

    fun close() {
        try {
            detector.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing face detector", e)
        }
    }
}
