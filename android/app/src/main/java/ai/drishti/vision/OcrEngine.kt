package ai.drishti.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class OcrResult(
    val fullText: String,
    val lineCount: Int,
    val confidenceScore: Float
)

/**
 * On-device optical character recognition engine using Google ML Kit.
 */
class OcrEngine {

    private val tag = "OcrEngine"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText: Text ->
                    val rawText = visionText.text
                    val lines = visionText.textBlocks.flatMap { it.lines }
                    val result = OcrResult(
                        fullText = rawText.trim(),
                        lineCount = lines.size,
                        confidenceScore = if (rawText.isNotBlank()) 1.0f else 0.0f
                    )
                    Log.i(tag, "Extracted OCR text (${result.lineCount} lines, ${result.fullText.length} chars)")
                    continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "ML Kit text recognition failed", e)
                    continuation.resume(OcrResult("", 0, 0.0f))
                }
        } catch (e: Exception) {
            Log.e(tag, "Error preparing image for OCR", e)
            continuation.resume(OcrResult("", 0, 0.0f))
        }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing OCR recognizer", e)
        }
    }
}
