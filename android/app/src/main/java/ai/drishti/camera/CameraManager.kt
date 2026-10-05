package ai.drishti.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope

enum class CameraSource {
    PHONE_BACK,
    PHONE_FRONT,
    ESP32_WIFI;

    val displayName: String
        get() = when (this) {
            PHONE_BACK -> "Phone Camera (Back Lens)"
            PHONE_FRONT -> "Phone Camera (Front Lens)"
            ESP32_WIFI -> "ESP32-CAM (Wi-Fi Stream)"
        }

    val shortName: String
        get() = when (this) {
            PHONE_BACK -> "Phone Back"
            PHONE_FRONT -> "Phone Front"
            ESP32_WIFI -> "ESP32-CAM"
        }

    val icon: String
        get() = when (this) {
            PHONE_BACK -> "📱"
            PHONE_FRONT -> "🤳"
            ESP32_WIFI -> "📷"
        }

    val isPhone: Boolean
        get() = this == PHONE_BACK || this == PHONE_FRONT

    companion object {
        // Backwards compatibility alias for code referencing PHONE_NATIVE
        val PHONE_NATIVE = PHONE_BACK
    }
}

/**
 * Manages dual-camera video feeds: ESP32-CAM (Wi-Fi MJPEG) with automatic
 * seamless fallback to Phone CameraX (Back / Front lens) upon disconnection.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val onSourceChanged: (CameraSource, message: String) -> Unit,
    private val onFrameProcessed: (Bitmap) -> Unit
) {

    private val tag = "CameraManager"
    var activeSource: CameraSource = CameraSource.PHONE_BACK
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
            if (activeSource.isPhone) {
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
                switchToPhoneBack("ESP32 camera lost. Switched to phone camera.")
            }
        )
    }

    fun start(preferredSource: CameraSource, esp32Ip: String) {
        when (preferredSource) {
            CameraSource.ESP32_WIFI -> switchToEsp32(esp32Ip)
            CameraSource.PHONE_FRONT -> switchToPhoneFront("Phone front camera active.")
            else -> switchToPhoneBack("Phone camera active.")
        }
    }

    fun switchToEsp32(esp32Ip: String) {
        Log.i(tag, "Switching camera source to ESP32-CAM ($esp32Ip)")
        phoneCamera?.stop()
        activeSource = CameraSource.ESP32_WIFI
        esp32Client?.startStream(esp32Ip)
        onSourceChanged(CameraSource.ESP32_WIFI, "Connected to ESP32 camera.")
    }

    fun switchToPhoneBack(announcement: String = "Switched to phone back camera.") {
        Log.i(tag, "Switching camera source to Phone Back CameraX")
        esp32Client?.stopStream()
        activeSource = CameraSource.PHONE_BACK
        phoneCamera?.switchLens(CameraSelector.LENS_FACING_BACK)
        phoneCamera?.start()
        onSourceChanged(CameraSource.PHONE_BACK, announcement)
    }

    fun switchToPhoneFront(announcement: String = "Switched to phone front camera.") {
        Log.i(tag, "Switching camera source to Phone Front CameraX")
        esp32Client?.stopStream()
        activeSource = CameraSource.PHONE_FRONT
        phoneCamera?.switchLens(CameraSelector.LENS_FACING_FRONT)
        phoneCamera?.start()
        onSourceChanged(CameraSource.PHONE_FRONT, announcement)
    }

    fun switchToPhoneCamera(announcement: String = "Switched to phone camera.") {
        switchToPhoneBack(announcement)
    }

    fun switchToSource(source: CameraSource, esp32Ip: String) {
        when (source) {
            CameraSource.PHONE_BACK -> switchToPhoneBack("Switched to phone back camera.")
            CameraSource.PHONE_FRONT -> switchToPhoneFront("Switched to phone front camera.")
            CameraSource.ESP32_WIFI -> switchToEsp32(esp32Ip)
        }
    }

    fun toggleCamera(esp32Ip: String) {
        if (activeSource == CameraSource.ESP32_WIFI) {
            switchToPhoneBack("Switched to phone camera.")
        } else {
            switchToEsp32(esp32Ip)
        }
    }

    fun cycleNextCamera(esp32Ip: String) {
        when (activeSource) {
            CameraSource.PHONE_BACK -> switchToEsp32(esp32Ip)
            CameraSource.ESP32_WIFI -> switchToPhoneFront("Switched to front camera.")
            CameraSource.PHONE_FRONT -> switchToPhoneBack("Switched to back camera.")
        }
    }

    fun stop() {
        esp32Client?.stopStream()
        phoneCamera?.stop()
        latestFrame = null
        Log.i(tag, "CameraManager stopped all capture feeds.")
    }
}
