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

            // ── Observe audio levels for the meter ──
            observeAudioLevels()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioObserveJobs.forEach { it.cancel() }
            audioObserveJobs.clear()
            binding.viewAudioLevelMeter.setInputAudioLevel(0f)
            binding.viewAudioLevelMeter.setOutputAudioLevel(0f)
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
            if (service != null) {
                service.cameraManager.toggleCamera(service.preferenceManager.esp32IpAddress)
                val isEsp32 = service.cameraManager.activeSource == CameraSource.ESP32_WIFI
                binding.tvCameraStatus.text = if (isEsp32) getString(R.string.camera_status_esp32) else getString(R.string.camera_status_phone)
            } else {
                Toast.makeText(this, "Start service first to toggle camera", Toast.LENGTH_SHORT).show()
            }
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
        }
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
