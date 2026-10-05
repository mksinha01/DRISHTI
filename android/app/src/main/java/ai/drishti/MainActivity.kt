package ai.drishti

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ai.drishti.camera.CameraSource
import ai.drishti.databinding.ActivityMainBinding
import ai.drishti.mode.DrishtiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var drishtiService: DrishtiService? = null
    private var isServiceBound = false
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val audioObserveJobs = mutableListOf<Job>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DrishtiService.LocalBinder
            drishtiService = binder.getService()
            isServiceBound = true
            updateUiState()

            // Observe mode changes
            drishtiService?.modeManager?.addListener { _, newMode, _ ->
                runOnUiThread { updateModeDisplay(newMode) }
            }

            // Observe voice connection status
            drishtiService?.onVoiceConnectionChanged = { connected ->
                runOnUiThread { updateVoiceStatus(connected) }
            }

            // Observe camera source changes
            drishtiService?.onCameraSourceChanged = { source, message ->
                runOnUiThread { updateCameraDisplay(source, isActive = true, customMessage = message) }
            }

            // Observe live frames for preview
            drishtiService?.onFrameForPreview = { bitmap ->
                runOnUiThread {
                    binding.ivCameraPreview.setImageBitmap(bitmap)
                    if (binding.layoutCameraPlaceholder.visibility != View.GONE) {
                        binding.layoutCameraPlaceholder.visibility = View.GONE
                        binding.ivCameraPreview.visibility = View.VISIBLE
                    }
                }
            }

            // ── Observe audio levels for the meter ──
            observeAudioLevels()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioObserveJobs.forEach { it.cancel() }
            audioObserveJobs.clear()
            binding.viewAudioLevelMeter.setInputAudioLevel(0f)
            binding.viewAudioLevelMeter.setOutputAudioLevel(0f)
            binding.ivCameraPreview.setImageBitmap(null)
            binding.ivCameraPreview.visibility = View.GONE
            binding.layoutCameraPlaceholder.visibility = View.VISIBLE
            drishtiService = null
            isServiceBound = false
            updateUiState()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted && audioGranted) {
            Toast.makeText(this, "Permissions granted. Starting Drishti.", Toast.LENGTH_SHORT).show()
            startDrishtiService()
        } else {
            Toast.makeText(this, "Camera and Audio permissions are required for Drishti.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        checkAndRequestPermissions()
    }

    private fun setupListeners() {
        binding.btnToggleService.setOnClickListener {
            val service = drishtiService
            if (isServiceBound && service != null && service.isRunning) {
                stopDrishtiService()
            } else {
                checkAndRequestPermissions()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            val service = drishtiService
            if (service != null && service.isRunning) {
                service.cameraManager.toggleCamera(service.preferenceManager.esp32IpAddress)
                updateCameraDisplay(service.cameraManager.activeSource, isActive = true)
            } else {
                val prefManager = drishtiService?.preferenceManager ?: ai.drishti.utils.PreferenceManager(this)
                prefManager.useEsp32Camera = !prefManager.useEsp32Camera
                val nextSource = if (prefManager.useEsp32Camera) CameraSource.ESP32_WIFI else CameraSource.PHONE_BACK
                updateCameraDisplay(nextSource, isActive = false)
                val name = if (prefManager.useEsp32Camera) "ESP32-CAM" else "Phone Camera"
                Toast.makeText(this, "Default camera set to $name", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSwitchCamera.setOnLongClickListener {
            showCameraSelectionDialog()
            true
        }

        binding.cardCameraStatus.setOnClickListener {
            showCameraSelectionDialog()
        }

        binding.cardCameraViewport.setOnClickListener {
            showCameraSelectionDialog()
        }

        binding.btnEmergencySOS.setOnClickListener {
            drishtiService?.let { service ->
                if (service.emergencyMonitor.isEmergencyActive) {
                    service.handleUserVoiceInput("cancel")
                    binding.btnEmergencySOS.text = getString(R.string.trigger_emergency)
                } else {
                    service.handleUserVoiceInput("help emergency")
                    binding.btnEmergencySOS.text = getString(R.string.cancel_emergency)
                }
            } ?: Toast.makeText(this, "Service not running", Toast.LENGTH_SHORT).show()
        }

        binding.tvLiveKitStatus.setOnClickListener {
            showServerConfigDialog()
        }
    }

    private fun showServerConfigDialog() {
        val prefManager = drishtiService?.preferenceManager ?: ai.drishti.utils.PreferenceManager(this)
        val currentUrl = prefManager.tokenServerUrl

        val input = android.widget.EditText(this).apply {
            setText(currentUrl)
            hint = "http://127.0.0.1:8000"
            setSingleLine()
            setPadding(40, 30, 40, 30)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Token Server URL")
            .setMessage("Set the backend token server URL for LiveKit voice companion.\n\n• USB cable (ADB reverse): http://127.0.0.1:8000\n• Wi-Fi Local PC: http://10.113.162.33:8000\n• Emulator: http://10.0.2.2:8000")
            .setView(input)
            .setPositiveButton("Save & Reconnect") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    prefManager.tokenServerUrl = newUrl
                    Toast.makeText(this, "Connecting to $newUrl...", Toast.LENGTH_SHORT).show()
                    drishtiService?.connectLiveKit(newUrl)
                }
            }
            .setNeutralButton("Use 127.0.0.1") { _, _ ->
                val adbUrl = "http://127.0.0.1:8000"
                prefManager.tokenServerUrl = adbUrl
                Toast.makeText(this, "Reconnecting via ADB reverse...", Toast.LENGTH_SHORT).show()
                drishtiService?.connectLiveKit(adbUrl)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        binding.viewAudioLevelMeter.startMeter()
    }

    private fun checkAndRequestPermissions() {
        val required = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startDrishtiService()
        }
    }

    private fun startDrishtiService() {
        val serviceIntent = Intent(this, DrishtiService::class.java).apply {
            action = DrishtiService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // If service is already in memory, resume immediately
        drishtiService?.let {
            if (!it.isRunning) {
                it.resumeSubsystems()
            }
            updateUiState()
        }
    }

    private fun stopDrishtiService() {
        audioObserveJobs.forEach { it.cancel() }
        audioObserveJobs.clear()
        binding.viewAudioLevelMeter.setInputAudioLevel(0f)
        binding.viewAudioLevelMeter.setOutputAudioLevel(0f)

        // Stop subsystems immediately
        drishtiService?.stopDrishtiSubsystems()

        binding.ivCameraPreview.setImageBitmap(null)
        binding.ivCameraPreview.visibility = View.GONE
        binding.layoutCameraPlaceholder.visibility = View.VISIBLE

        val stopIntent = Intent(this, DrishtiService::class.java).apply {
            action = DrishtiService.ACTION_STOP
        }
        try {
            startService(stopIntent)
        } catch (e: Exception) {
            Log.w(tag, "Failed to send stop intent: ${e.message}")
        }

        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(tag, "Failed to unbind service: ${e.message}")
            }
            isServiceBound = false
        }
        drishtiService = null
        updateUiState()
    }

    private fun updateUiState() {
        val service = drishtiService
        val running = isServiceBound && service != null && service.isRunning
        val prefManager = service?.preferenceManager ?: ai.drishti.utils.PreferenceManager(this)
        val currentSource = service?.cameraManager?.activeSource
            ?: if (prefManager.useEsp32Camera) CameraSource.ESP32_WIFI else CameraSource.PHONE_BACK

        updateCameraDisplay(currentSource, isActive = running)

        if (running) {
            binding.btnToggleService.text = getString(R.string.stop_service)
            service?.let {
                updateModeDisplay(it.modeManager.currentMode)
                updateVoiceStatus(it.isVoiceConnected)
            }
        } else {
            binding.btnToggleService.text = getString(R.string.start_service)
            binding.tvCurrentMode.text = "STOPPED"
            binding.tvModeDetail.text = "Tap Start to launch Drishti"
            updateVoiceStatus(false)
            binding.ivCameraPreview.visibility = View.GONE
            binding.layoutCameraPlaceholder.visibility = View.VISIBLE
        }
    }

    private fun updateCameraDisplay(source: CameraSource, isActive: Boolean, customMessage: String? = null) {
        val prefManager = drishtiService?.preferenceManager ?: ai.drishti.utils.PreferenceManager(this)

        // 1. Top Indicator Card
        binding.tvCameraStatus.text = "${source.icon} ${source.shortName}"

        if (customMessage != null && (customMessage.contains("lost", ignoreCase = true) || customMessage.contains("fail", ignoreCase = true))) {
            binding.tvCameraSubtext.text = getString(R.string.camera_subtext_fallback)
        } else if (isActive) {
            binding.tvCameraSubtext.text = when (source) {
                CameraSource.PHONE_BACK -> getString(R.string.camera_subtext_active_phone_back)
                CameraSource.PHONE_FRONT -> getString(R.string.camera_subtext_active_phone_front)
                CameraSource.ESP32_WIFI -> "🟢 Active • ${prefManager.esp32IpAddress}"
            }
        } else {
            binding.tvCameraSubtext.text = getString(R.string.camera_subtext_inactive)
        }

        // 2. Video Preview Floating Badge
        val statusLabel = if (isActive) "LIVE" else "STANDBY"
        binding.tvLiveCameraBadge.text = "CURRENT: ${source.displayName.uppercase()} • $statusLabel"
        binding.viewLiveIndicatorDot.setBackgroundResource(
            if (isActive) R.drawable.indicator_dot_green else R.drawable.indicator_dot_gray
        )

        // 3. Viewport Bottom Info
        binding.tvPreviewSourceInfo.text = when (source) {
            CameraSource.PHONE_BACK -> "Source: Phone Back Lens (CameraX) • Tap to change"
            CameraSource.PHONE_FRONT -> "Source: Phone Front Lens (CameraX) • Tap to change"
            CameraSource.ESP32_WIFI -> "Source: ESP32-CAM (${prefManager.esp32IpAddress}) • Tap to change"
        }

        // 4. Placeholder guidance
        binding.tvPlaceholderIcon.text = source.icon
        binding.tvPlaceholderTitle.text = "${source.displayName} Standby"

        // 5. Switch Camera Button label
        binding.btnSwitchCamera.text = if (source.isPhone) {
            getString(R.string.switch_camera_to_esp32)
        } else {
            getString(R.string.switch_camera_to_phone)
        }
    }

    private fun showCameraSelectionDialog() {
        val prefManager = drishtiService?.preferenceManager ?: ai.drishti.utils.PreferenceManager(this)
        val currentSource = drishtiService?.cameraManager?.activeSource
            ?: if (prefManager.useEsp32Camera) CameraSource.ESP32_WIFI else CameraSource.PHONE_BACK

        val options = arrayOf(
            "📱 Phone Camera (Back Lens)\n   Standard obstacle & navigation view",
            "🤳 Phone Camera (Front Lens)\n   Front-facing view for reading & selfie",
            "📷 ESP32-CAM (Wi-Fi Stream)\n   External wireless camera stream (${prefManager.esp32IpAddress})"
        )

        val selectedIndex = when (currentSource) {
            CameraSource.PHONE_BACK -> 0
            CameraSource.PHONE_FRONT -> 1
            CameraSource.ESP32_WIFI -> 2
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Select Active Camera")
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                dialog.dismiss()
                val targetSource = when (which) {
                    0 -> {
                        prefManager.useEsp32Camera = false
                        CameraSource.PHONE_BACK
                    }
                    1 -> {
                        prefManager.useEsp32Camera = false
                        CameraSource.PHONE_FRONT
                    }
                    else -> {
                        prefManager.useEsp32Camera = true
                        CameraSource.ESP32_WIFI
                    }
                }

                val service = drishtiService
                if (service != null && service.isRunning) {
                    service.cameraManager.switchToSource(targetSource, prefManager.esp32IpAddress)
                    updateCameraDisplay(targetSource, isActive = true)
                } else {
                    updateCameraDisplay(targetSource, isActive = false)
                }
                Toast.makeText(this, "Camera selected: ${targetSource.shortName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateVoiceStatus(connected: Boolean) {
        binding.tvLiveKitStatus.text = if (connected) {
            getString(R.string.livekit_status_connected)
        } else {
            getString(R.string.livekit_status_disconnected)
        }
    }

    private fun updateModeDisplay(mode: DrishtiMode) {
        binding.tvCurrentMode.text = mode.name
        binding.tvModeDetail.text = when (mode) {
            is DrishtiMode.Assistant -> "Listening for voice queries and scanning path"
            is DrishtiMode.Navigation -> "Guiding along active route with obstacle spotting"
            is DrishtiMode.Ocr -> "Reading camera frames and interpreting text"
            is DrishtiMode.Emergency -> "EMERGENCY COUNTDOWN ACTIVE. Tap SOS or say Cancel"
        }

        if (mode is DrishtiMode.Emergency) {
            binding.btnEmergencySOS.text = getString(R.string.cancel_emergency)
        } else {
            binding.btnEmergencySOS.text = getString(R.string.trigger_emergency)
        }
    }

    private fun observeAudioLevels() {
        val service = drishtiService ?: return
        val meter = binding.viewAudioLevelMeter

        audioObserveJobs.forEach { it.cancel() }
        audioObserveJobs.clear()

        // Input (mic) level
        val inJob = uiScope.launch {
            service.liveKitClient.inputAudioLevel
                .distinctUntilChanged { a, b -> kotlin.math.abs(a - b) < 0.005f }
                .collect { level ->
                    meter.setInputAudioLevel(level)
                }
        }
        audioObserveJobs.add(inJob)

        // Output (agent) level
        val outJob = uiScope.launch {
            service.liveKitClient.outputAudioLevel
                .distinctUntilChanged { a, b -> kotlin.math.abs(a - b) < 0.005f }
                .collect { level ->
                    meter.setOutputAudioLevel(level)
                }
        }
        audioObserveJobs.add(outJob)
    }

    override fun onDestroy() {
        uiScope.cancel()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
