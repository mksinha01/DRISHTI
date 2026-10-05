package ai.drishti.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Manages on-device TFLite model lifecycle and executes inference on camera frames.
 */
class TFLiteDetector(private val context: Context) {

    private val tag = "TFLiteDetector"
    private var interpreter: Interpreter? = null
    private val labels = mutableListOf<String>()

    private val inputImageWidth = 320
    private val inputImageHeight = 320
    private val modelFileName = "yolov8n_float16.tflite"

    init {
        loadLabels()
        initializeInterpreter()
    }

    private fun loadLabels() {
        try {
            context.assets.open("coco_labels.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            labels.add(line.trim())
                        }
                        line = reader.readLine()
                    }
                }
            }
            Log.i(tag, "Loaded ${labels.size} labels from assets.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to load labels from assets", e)
        }
    }

    private fun initializeInterpreter() {
        try {
            val assetFileDescriptor = context.assets.openFd(modelFileName)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(tag, "TFLite Interpreter initialized successfully for $modelFileName")
        } catch (e: Exception) {
            Log.w(tag, "Model $modelFileName not found in assets or failed to load. Will run in fallback mode.", e)
            interpreter = null
        }
    }

    /**
     * Executes object detection inference on a Bitmap frame.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        val activeInterpreter = interpreter
        if (activeInterpreter == null) {
            // Return empty list if model not loaded
            return emptyList()
        }

        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
            val inputBuffer = convertBitmapToByteBuffer(scaledBitmap)

            // YOLOv8 output tensor: [1, 84, 2100] (for 80 classes + 4 box coords)
            val outputArray = Array(1) { Array(84) { FloatArray(2100) } }
            activeInterpreter.run(inputBuffer, outputArray)

            processYoloOutput(outputArray[0])
        } catch (e: Exception) {
            Log.e(tag, "Inference error", e)
            emptyList()
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputImageWidth * inputImageHeight * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until inputImageWidth) {
            for (j in 0 until inputImageHeight) {
                val value = intValues[pixel++]
                // Normalize RGB [0..255] to [0.0..1.0]
                byteBuffer.putFloat(((value shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((value shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((value and 0xFF) / 255.0f)
            }
        }
        return byteBuffer
    }

    private fun processYoloOutput(output: Array<FloatArray>): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numPredictions = 2100
        val numClasses = 80

        for (i in 0 until numPredictions) {
            var maxConfidence = 0.0f
            var maxClassId = -1

            for (c in 0 until numClasses) {
                val score = output[4 + c][i]
                if (score > maxConfidence) {
                    maxConfidence = score
                    maxClassId = c
                }
            }

            if (maxConfidence >= 0.45f && maxClassId in labels.indices) {
                val cx = output[0][i] / inputImageWidth
                val cy = output[1][i] / inputImageHeight
                val w = output[2][i] / inputImageWidth
                val h = output[3][i] / inputImageHeight

                val left = (cx - w / 2).coerceIn(0.0f, 1.0f)
                val top = (cy - h / 2).coerceIn(0.0f, 1.0f)
                val right = (cx + w / 2).coerceIn(0.0f, 1.0f)
                val bottom = (cy + h / 2).coerceIn(0.0f, 1.0f)

                detections.add(
                    Detection(
                        label = labels[maxClassId],
                        confidence = maxConfidence,
                        boundingBox = BoundingBox(left, top, right, bottom)
                    )
                )
            }
        }
        return detections
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
