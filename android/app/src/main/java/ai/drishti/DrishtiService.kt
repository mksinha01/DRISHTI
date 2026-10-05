package ai.drishti

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ai.drishti.audio.LiveKitClient
import ai.drishti.audio.LocalTTS
import ai.drishti.camera.CameraManager
import ai.drishti.camera.CameraSource
import ai.drishti.emergency.*
import ai.drishti.mode.*
import ai.drishti.navigation.GeoPoint
import ai.drishti.navigation.NavigationEngine
import ai.drishti.sensors.FallDetector
import ai.drishti.sensors.LocationProvider
import ai.drishti.utils.PreferenceManager
import ai.drishti.vision.*
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground Service running 24/7 as Drishti's core brain on Android.
 * Orchestrates vision, speech, sensors, navigation, and emergency workflows.
 */
class DrishtiService : LifecycleService() {

    private val tag = "DrishtiService"
    private val binder = LocalBinder()

    companion object {
        const val ACTION_START = "ai.drishti.action.START"
        const val ACTION_STOP = "ai.drishti.action.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "drishti_service_channel"
    }

    var isRunning: Boolean = false
        private set

    // Subsystems
    lateinit var preferenceManager: PreferenceManager
    lateinit var modeManager: ModeManager
    lateinit var localTTS: LocalTTS
    lateinit var cameraManager: CameraManager
    lateinit var tfliteDetector: TFLiteDetector
    lateinit var faceDetector: FaceDetector
    lateinit var ocrEngine: OcrEngine
    lateinit var navigationEngine: NavigationEngine
    lateinit var emergencyMonitor: EmergencyMonitor
    lateinit var alertSender: AlertSender
    lateinit var contactStore: ContactStore
    lateinit var locationProvider: LocationProvider
    lateinit var fallDetector: FallDetector
    lateinit var liveKitClient: LiveKitClient

    var onVoiceConnectionChanged: ((Boolean) -> Unit)? = null
    var onCameraSourceChanged: ((CameraSource, message: String) -> Unit)? = null
    var onFrameForPreview: ((Bitmap) -> Unit)? = null

    val isVoiceConnected: Boolean
        get() = ::liveKitClient.isInitialized && liveKitClient.isConnected

    private var lastInferenceTimeMs = 0L
    private var lastFrameSendTimeMs = 0L
    private var lastPreviewFrameTimeMs = 0L

    inner class LocalBinder : Binder() {
        fun getService(): DrishtiService = this@DrishtiService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "Initializing Drishti Foreground Service (onCreate)...")
        startForegroundServiceWithNotification()
        initializeSubsystems()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.i(tag, "DrishtiService onStartCommand (action=$action, startId=$startId)")

        if (action == ACTION_STOP) {
            stopDrishtiSubsystems()
            updateForegroundNotification(isActive = false)
            isRunning = false
            return START_NOT_STICKY
        }

        // Always ensure foreground notification is posted within 5s of startForegroundService
        startForegroundServiceWithNotification(isActive = true)

        if (!::preferenceManager.isInitialized) {
            initializeSubsystems()
        } else if (!isRunning) {
            resumeSubsystems()
        }
        isRunning = true

        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification(isActive: Boolean = true) {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Drishti Background Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Drishti voice and vision engine running in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val title = if (isActive) "Drishti is active" else "Drishti is paused"
        val text = if (isActive) "Monitoring obstacles and listening for voice commands" else "Voice and vision paused. Tap Start in app to resume."
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(isActive)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to startForeground with specific types, falling back to basic: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (fallbackErr: Exception) {
                Log.e(tag, "Critical startForeground error: ${fallbackErr.message}", fallbackErr)
            }
        }
    }

    fun updateForegroundNotification(isActive: Boolean) {
        try {
            val channelId = CHANNEL_ID
            val title = if (isActive) "Drishti is active" else "Drishti is paused"
            val text = if (isActive) "Monitoring obstacles and listening for voice commands" else "Voice and vision paused. Tap Start in app to resume."
            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(isActive)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(tag, "Could not update notification: ${e.message}")
        }
    }

    private fun initializeSubsystems() {
        preferenceManager = PreferenceManager(this)
        modeManager = ModeManager()
        contactStore = ContactStore(this)
        alertSender = AlertSender(this)
        navigationEngine = NavigationEngine()
        tfliteDetector = TFLiteDetector(this)
        faceDetector = FaceDetector()
        ocrEngine = OcrEngine()
        locationProvider = LocationProvider(this)

        // Local TextToSpeech
        localTTS = LocalTTS(this) {
            localTTS.speak("Welcome to Drishti. Assistant mode active. How can I help you?", flush = true)
        }

        // Mode Manager Listener
        modeManager.addListener { _, newMode, spokenText ->
            localTTS.speak(spokenText, flush = true)
            // Broadcast mode change to LiveKit agent
            val packet = JSONObject().apply {
                put("type", "MODE_CHANGE")
                put("mode", newMode.name.lowercase())
            }
            liveKitClient.sendData(packet.toString())
        }

        // Emergency Monitor
        emergencyMonitor = EmergencyMonitor(
            onTick = { secondsLeft ->
                localTTS.speak("Emergency in $secondsLeft seconds. Say cancel to stop.", flush = true)
            },
            onAlertFired = { reason ->
                executeEmergencyDispatch(reason)
            },
            onCancelled = {
                modeManager.cancelEmergency()
            }
        )

        // Fall Detector
        fallDetector = FallDetector(this) {
            Log.w(tag, "Fall detected by sensor! Initiating emergency protocol.")
            modeManager.switchTo(DrishtiMode.Emergency("fall_detected", 15))
            emergencyMonitor.triggerEmergency("fall_detected", 15)
        }
        fallDetector.start()

        // Location Updates
        locationProvider.startLocationUpdates { point ->
            if (navigationEngine.isNavigating) {
                val instruction = navigationEngine.onLocationUpdate(point)
                if (instruction != null) {
                    localTTS.speak(instruction, flush = false)
                }
            }
        }

        // Camera Manager
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            scope = lifecycleScope,
            onSourceChanged = { source, message ->
                localTTS.speak(message, flush = false)
                onCameraSourceChanged?.invoke(source, message)
            },
            onFrameProcessed = { bitmap ->
                handleCameraFrame(bitmap)
            }
        )

        val defaultSource = if (preferenceManager.useEsp32Camera) CameraSource.ESP32_WIFI else CameraSource.PHONE_BACK
        cameraManager.start(defaultSource, preferenceManager.esp32IpAddress)

        // LiveKit Client
        liveKitClient = LiveKitClient(
            context = this,
            scope = lifecycleScope,
            onDataReceived = { message ->
                handleAgentMessage(message)
            },
            onConnectionChanged = { connected ->
                val statusText = if (connected) "Voice connected to cloud." else "Cloud voice offline. Local safety alerts active."
                Log.i(tag, statusText)
                onVoiceConnectionChanged?.invoke(connected)
            }
        )

        // Connect to LiveKit Room
        connectLiveKit()
    }

    /**
     * Connects or reconnects the LiveKit voice client with candidate fallback.
     */
    fun connectLiveKit(customTokenUrl: String? = null) {
        if (customTokenUrl != null && customTokenUrl.isNotBlank()) {
            preferenceManager.tokenServerUrl = customTokenUrl.trim()
        }
        liveKitClient.disconnect()
        val sessionRoom = "drishti-${System.currentTimeMillis() % 1000000}"
        Log.i(tag, "Connecting LiveKit voice session for room: $sessionRoom")
        liveKitClient.connect(
            serverUrl = preferenceManager.liveKitServerUrl,
            tokenServerBaseUrl = preferenceManager.tokenServerUrl,
            roomName = sessionRoom,
            userName = preferenceManager.userName,
            onResolvedTokenServerUrl = { workingUrl ->
                preferenceManager.tokenServerUrl = workingUrl
                Log.i(tag, "Persisted active token server URL: $workingUrl")
            }
        )
    }

    private fun handleCameraFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()

        // 0. Smooth UI preview update (~20 FPS)
        if (now - lastPreviewFrameTimeMs >= 50L) {
            lastPreviewFrameTimeMs = now
            onFrameForPreview?.invoke(bitmap)
        }

        val interval = modeManager.currentConfig.obstacleInferenceIntervalMs

        if (now - lastInferenceTimeMs < interval) {
            return
        }
        lastInferenceTimeMs = now

        // 1. In Assistant or Navigation mode: run obstacle inference
        if (modeManager.currentMode is DrishtiMode.Assistant || modeManager.currentMode is DrishtiMode.Navigation) {
            val detections = tfliteDetector.detect(bitmap)
            if (detections.isNotEmpty()) {
                val alerts = ObstacleClassifier.classify(
                    detections = detections,
                    maxAlertDistanceMeters = modeManager.currentConfig.maxProximityAlertDistanceMeters
                )

                val priorityAlert = alerts.firstOrNull { it.isPriority } ?: alerts.firstOrNull()
                if (priorityAlert != null) {
                    Log.i(tag, "Obstacle warning: ${priorityAlert.spokenWarning}")
                    localTTS.speak(priorityAlert.spokenWarning, flush = true)

                    // Inform agent of obstacle
                    val data = JSONObject().apply {
                        put("type", "OBSTACLE_WARNING")
                        put("obstacle", priorityAlert.label)
                        put("position", priorityAlert.position.spokenSide)
                        put("distance", priorityAlert.estimatedDistanceMeters)
                    }
                    liveKitClient.sendData(data.toString())
                }
            }
        }

        // 2. Periodically dispatch camera frame to cloud agent (every 3.5s) for instant vision scene Q&A
        if (now - lastFrameSendTimeMs >= 3500L) {
            lastFrameSendTimeMs = now
            sendCameraFrameToAgent(bitmap)
        }
    }

    private fun sendCameraFrameToAgent(bitmap: Bitmap) {
        if (!::liveKitClient.isInitialized || !liveKitClient.isConnected) return
        try {
            // Downscale to 480x360 with 55% JPEG compression for low latency WebRTC transfer (~15-20KB)
            val scaled = Bitmap.createScaledBitmap(bitmap, 480, 360, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            val packet = JSONObject().apply {
                put("type", "CAMERA_FRAME")
                put("image_base64", base64)
                put("timestamp", System.currentTimeMillis())
            }
            liveKitClient.sendData(packet.toString())
            Log.d(tag, "Dispatched camera frame to cloud agent (${base64.length} chars)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to send camera frame to agent", e)
        }
    }

    fun handleUserVoiceInput(utterance: String) {
        val action = VoiceIntentRouter.parse(utterance, modeManager.currentMode)
        Log.i(tag, "Parsed voice action: $action")

        when (action) {
            is VoiceAction.EmergencyCancel -> {
                emergencyMonitor.cancelEmergency()
            }
            is VoiceAction.EmergencyTrigger -> {
                modeManager.switchTo(DrishtiMode.Emergency(action.reason, 10))
                emergencyMonitor.triggerEmergency(action.reason, 10)
            }
            is VoiceAction.QueryActiveMode -> {
                localTTS.speak("You are currently in ${modeManager.currentMode.name} mode.", flush = true)
            }
            is VoiceAction.SwitchMode -> {
                modeManager.switchTo(action.targetMode)
            }
            is VoiceAction.NavigateToDestination -> {
                val origin = locationProvider.lastKnownLocation ?: GeoPoint(28.6139, 77.2090)
                modeManager.switchTo(DrishtiMode.Navigation(action.destination))
                val prompt = navigationEngine.startRoute(action.destination, origin)
                localTTS.speak(prompt, flush = true)
            }
            is VoiceAction.OcrAction -> {
                executeOcrAction(action.subAction)
            }
            is VoiceAction.GeneralConversation -> {
                // Sent to cloud agent via LiveKit room audio
            }
        }
    }

    private fun executeOcrAction(subAction: String) {
        val frame = cameraManager.latestFrame
        if (frame == null) {
            localTTS.speak("Camera frame not available. Please wait.", flush = true)
            return
        }

        localTTS.speak("Reading text in front of camera...", flush = true)
        lifecycleScope.launch(Dispatchers.Default) {
            val result = ocrEngine.extractText(frame)
            if (result.fullText.isBlank()) {
                localTTS.speak("No clear text detected. Try adjusting camera angle.", flush = true)
            } else {
                localTTS.speak(result.fullText, flush = true)

                // Transmit extracted text to agent for summarization or Q&A
                val data = JSONObject().apply {
                    put("type", "OCR_RESULT")
                    put("text", result.fullText)
                    put("subAction", subAction)
                }
                liveKitClient.sendData(data.toString())
            }
        }
    }

    private fun executeEmergencyDispatch(reason: String) {
        localTTS.speak("Dispatching emergency alerts with your location to emergency contacts.", flush = true)
        locationProvider.getCurrentLocationSingleShot { point ->
            val payload = EmergencyPayload(
                userName = preferenceManager.userName,
                latitude = point.latitude,
                longitude = point.longitude,
                triggerReason = reason,
                timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            )
            val contacts = contactStore.getContacts()
            alertSender.sendEmergencyAlert(contacts, payload)
        }
    }

    private fun handleAgentMessage(messageJson: String) {
        try {
            val json = JSONObject(messageJson)
            when (json.optString("type")) {
                "REQUEST_FRAME" -> {
                    val frame = cameraManager.latestFrame
                    if (frame != null) {
                        sendCameraFrameToAgent(frame)
                    }
                }
                "REQUEST_OCR" -> executeOcrAction("read")
                "REQUEST_NAVIGATION" -> {
                    val dest = json.optString("destination", "Station")
                    handleUserVoiceInput("take me to $dest")
                }
                "SWITCH_MODE" -> {
                    val target = json.optString("target", "assistant")
                    handleUserVoiceInput("switch to $target")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing agent message", e)
        }
    }

    fun stopDrishtiSubsystems() {
        try {
            if (::fallDetector.isInitialized) fallDetector.stop()
            if (::locationProvider.isInitialized) locationProvider.stopLocationUpdates()
            if (::cameraManager.isInitialized) cameraManager.stop()
            if (::liveKitClient.isInitialized) liveKitClient.disconnect()
            if (::localTTS.isInitialized) localTTS.stop()
            updateForegroundNotification(isActive = false)
            isRunning = false
            Log.i(tag, "Drishti subsystems stopped.")
        } catch (e: Exception) {
            Log.w(tag, "Error stopping subsystems: ${e.message}")
        }
    }

    fun resumeSubsystems() {
        Log.i(tag, "Resuming Drishti subsystems...")
        try {
            val defaultSource = if (preferenceManager.useEsp32Camera) CameraSource.ESP32_WIFI else CameraSource.PHONE_BACK
            cameraManager.start(defaultSource, preferenceManager.esp32IpAddress)
            onCameraSourceChanged?.invoke(cameraManager.activeSource, "Active")
            connectLiveKit()
            fallDetector.start()
            localTTS.speak("Welcome back. Drishti is active.", flush = true)
            updateForegroundNotification(isActive = true)
            isRunning = true
        } catch (e: Exception) {
            Log.e(tag, "Error resuming subsystems: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        Log.i(tag, "Stopping Drishti Foreground Service (onDestroy)...")
        stopDrishtiSubsystems()
        try {
            if (::tfliteDetector.isInitialized) tfliteDetector.close()
            if (::faceDetector.isInitialized) faceDetector.close()
            if (::ocrEngine.isInitialized) ocrEngine.close()
            if (::localTTS.isInitialized) localTTS.shutdown()
        } catch (e: Exception) {
            Log.w(tag, "Error closing detectors: ${e.message}")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
