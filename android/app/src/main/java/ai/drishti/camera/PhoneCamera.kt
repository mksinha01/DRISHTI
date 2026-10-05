package ai.drishti.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX wrapper for on-device phone camera capture.
 * Provides high-speed frames for TFLite inference and fallback video feed.
 */
class PhoneCamera(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameAvailable: (Bitmap) -> Unit
) {

    private val tag = "PhoneCamera"
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return

        if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                isRunning = true
                Log.i(tag, "Phone CameraX started successfully.")
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize CameraX provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        val executor = cameraExecutor ?: Executors.newSingleThreadExecutor().also { cameraExecutor = it }

        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            try {
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    onFrameAvailable(bitmap)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error processing camera frame", e)
            } finally {
                imageProxy.close()
            }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        } catch (e: Exception) {
            Log.e(tag, "Use case binding failed", e)
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // Rotate bitmap according to sensor orientation
        val rotation = image.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    fun stop() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            cameraExecutor?.shutdown()
            cameraExecutor = null
            isRunning = false
            Log.i(tag, "Phone CameraX stopped.")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping CameraX", e)
        }
    }
}
