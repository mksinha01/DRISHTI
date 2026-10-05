package ai.drishti.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Connects to the ESP32-CAM HTTP MJPEG stream at http://<ip>/stream.
 * Decodes frames continuously into Bitmaps and delivers them via callback.
 */
class Esp32StreamClient(
    private val scope: CoroutineScope,
    private val onFrameReceived: (Bitmap) -> Unit,
    private val onStreamError: (Throwable) -> Unit
) {

    private val tag = "Esp32StreamClient"
    private var streamJob: Job? = null
    var isStreaming: Boolean = false
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun startStream(esp32Ip: String) {
        stopStream()
        isStreaming = true

        streamJob = scope.launch(Dispatchers.IO) {
            val cleaned = esp32Ip.trim().removeSuffix("/")
            val url = when {
                cleaned.endsWith("/stream") -> if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) cleaned else "http://$cleaned"
                cleaned.startsWith("http://") || cleaned.startsWith("https://") -> "$cleaned/stream"
                else -> "http://$cleaned/stream"
            }
            Log.i(tag, "Connecting to ESP32 MJPEG stream at $url")

            while (isActive && isStreaming) {
                try {
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        throw IllegalStateException("Stream request failed with HTTP ${response.code}")
                    }

                    val inputStream = response.body?.byteStream()
                        ?: throw IllegalStateException("Empty response body from ESP32")

                    readMjpegStream(inputStream)
                } catch (e: Exception) {
                    if (isActive && isStreaming) {
                        Log.w(tag, "ESP32 stream disconnected: ${e.message}. Retrying in 3 seconds...")
                        withContext(Dispatchers.Main) {
                            onStreamError(e)
                        }
                        delay(3000L)
                    }
                }
            }
        }
    }

    private fun readMjpegStream(stream: InputStream) {
        val buffer = ByteArray(4096)
        val frameBuffer = ByteArrayOutputStream()
        var prevByte = 0

        while (isStreaming) {
            val bytesRead = stream.read(buffer)
            if (bytesRead == -1) break

            for (i in 0 until bytesRead) {
                val b = buffer[i].toInt() and 0xFF

                // Check for JPEG Start of Image (SOI): 0xFF 0xD8
                if (prevByte == 0xFF && b == 0xD8) {
                    frameBuffer.reset()
                    frameBuffer.write(0xFF)
                    frameBuffer.write(0xD8)
                } else if (prevByte == 0xFF && b == 0xD9) { // End of Image (EOI): 0xFF 0xD9
                    frameBuffer.write(b)
                    val jpegBytes = frameBuffer.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bitmap != null) {
                        onFrameReceived(bitmap)
                    }
                    frameBuffer.reset()
                } else {
                    frameBuffer.write(b)
                }
                prevByte = b
            }
        }
    }

    fun stopStream() {
        isStreaming = false
        streamJob?.cancel()
        streamJob = null
        Log.i(tag, "ESP32 MJPEG stream stopped.")
    }
}
