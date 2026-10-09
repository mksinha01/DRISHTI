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
import java.net.NetworkInterface
import java.net.Inet4Address

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
            var cleaned = esp32Ip.trim().removeSuffix("/")
            
            // Try auto-discovery if default/provided IP is unreachable
            try {
                val checkUrl = if (cleaned.startsWith("http")) "$cleaned/status" else "http://$cleaned/status"
                val req = Request.Builder().url(checkUrl).build()
                val checkClient = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build()
                val res = checkClient.newCall(req).execute()
                if (!res.isSuccessful) throw Exception("Unreachable")
            } catch (e: Exception) {
                Log.w(tag, "IP $cleaned unreachable. Scanning subnet automatically...")
                val discovered = autoDiscoverEsp32Ip()
                if (discovered != null) {
                    cleaned = discovered
                }
            }

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

    private suspend fun autoDiscoverEsp32Ip(): String? = withContext(Dispatchers.IO) {
        try {
            var localIp: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address) {
                        localIp = addr.hostAddress
                        break
                    }
                }
                if (localIp != null) break
            }

            if (localIp != null && localIp.contains(".")) {
                val prefix = localIp.substringBeforeLast(".")
                val deferreds = (1..254).map { i ->
                    async {
                        val testIp = "$prefix.$i"
                        if (testIp == localIp) return@async null
                        try {
                            val request = Request.Builder().url("http://$testIp/status").build()
                            val scanClient = OkHttpClient.Builder()
                                .connectTimeout(800, TimeUnit.MILLISECONDS)
                                .readTimeout(800, TimeUnit.MILLISECONDS)
                                .build()
                            val response = scanClient.newCall(request).execute()
                            if (response.isSuccessful && response.body?.string()?.contains("\"status\":\"online\"") == true) {
                                return@async testIp
                            }
                        } catch (e: Exception) {}
                        null
                    }
                }
                val foundIp = deferreds.awaitAll().firstOrNull { it != null }
                if (foundIp != null) {
                    Log.i(tag, "Auto-discovered ESP32 at $foundIp")
                    return@withContext foundIp
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Auto-discovery failed", e)
        }
        null
    }
}
