package ai.drishti.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class CameraSource {
    ESP32_WIFI,
    PHONE_NATIVE
}

/**
 * Manages dual-camera video feeds: ESP32-CAM (Wi-Fi MJPEG) with automatic
 * seamless fallback to Phone CameraX upon disconnection.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val onSourceChanged: (CameraSource, message: String) -> Unit,
    private val onFrameProcessed: (Bitmap) -> Unit
) {

    private val tag = "CameraManager"
    var activeSource: CameraSource = CameraSource.PHONE_NATIVE
        private set

    var latestFrame: Bitmap? = null
        private set

    private var esp32Client: Esp32StreamClient? = null
    private var phoneCamera: PhoneCamera? = null

    init {
        initializeClients()
    }

    private fun initializeClients() {
        phoneCamera = PhoneCamera(context, lifecycleOwner) { bitmap ->
            if (activeSource == CameraSource.PHONE_NATIVE) {
                latestFrame = bitmap
                onFrameProcessed(bitmap)
            }
        }

        esp32Client = Esp32StreamClient(
            scope = scope,
            onFrameReceived = { bitmap ->
                if (activeSource == CameraSource.ESP32_WIFI) {
                    latestFrame = bitmap
                    onFrameProcessed(bitmap)
                }
            },
            onStreamError = { error ->
                Log.w(tag, "ESP32 feed interrupted ($error). Auto-switching to phone camera.")
                switchToPhoneCamera("ESP32 camera lost. Switched to phone camera.")
            }
        )
    }

    fun start(preferredSource: CameraSource, esp32Ip: String) {
        if (preferredSource == CameraSource.ESP32_WIFI) {
            switchToEsp32(esp32Ip)
        } else {
            switchToPhoneCamera("Phone camera active.")
        }
    }

    fun switchToEsp32(esp32Ip: String) {
        Log.i(tag, "Switching camera source to ESP32-CAM ($esp32Ip)")
        phoneCamera?.stop()
        activeSource = CameraSource.ESP32_WIFI
        esp32Client?.startStream(esp32Ip)
        onSourceChanged(CameraSource.ESP32_WIFI, "Connected to ESP32 camera.")
    }

    fun switchToPhoneCamera(announcement: String = "Switched to phone camera.") {
        Log.i(tag, "Switching camera source to Phone CameraX")
        esp32Client?.stopStream()
        activeSource = CameraSource.PHONE_NATIVE
        phoneCamera?.start()
        onSourceChanged(CameraSource.PHONE_NATIVE, announcement)
    }

    fun toggleCamera(esp32Ip: String) {
        if (activeSource == CameraSource.PHONE_NATIVE) {
            switchToEsp32(esp32Ip)
        } else {
            switchToPhoneCamera("Switched to phone camera.")
        }
    }

    fun stop() {
        esp32Client?.stopStream()
        phoneCamera?.stop()
        latestFrame = null
        Log.i(tag, "CameraManager stopped all capture feeds.")
    }
}
