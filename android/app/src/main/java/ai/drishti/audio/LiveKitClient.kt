package ai.drishti.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.participant.LocalParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Manages WebRTC connectivity with LiveKit Cloud for bi-directional audio,
 * video streaming, and data channel communication with the AI Agent.
 */
class LiveKitClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDataReceived: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {

    private val tag = "LiveKitClient"
    private var room: Room? = null
    private var localAudioTrack: LocalAudioTrack? = null
    private val httpClient = OkHttpClient()

    var isConnected: Boolean = false
        private set

    // ─── Audio level monitoring ───────────────────────────────────────
    private val _inputAudioLevel = MutableStateFlow(0f)
    private val _outputAudioLevel = MutableStateFlow(0f)

    /** Microphone input level [0..1], updated in real-time by LiveKit SDK. */
    val inputAudioLevel: StateFlow<Float> = _inputAudioLevel.asStateFlow()

    /** Agent/remote audio output level [0..1]. */
    val outputAudioLevel: StateFlow<Float> = _outputAudioLevel.asStateFlow()

    private var audioMonitorJob: Job? = null

    /**
     * Connects to LiveKit room by obtaining a token from token server.
     */
    fun connect(
        serverUrl: String,
        tokenServerBaseUrl: String,
        roomName: String,
        userName: String,
        onResolvedTokenServerUrl: ((String) -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(tag, "Fetching LiveKit JWT token (base: $tokenServerBaseUrl, room: $roomName, user: $userName)")
                val tokenResult = fetchToken(tokenServerBaseUrl, roomName, userName)
                if (tokenResult == null) {
                    Log.e(tag, "Failed to retrieve LiveKit token from any candidate server. Running in offline fallback.")
                    onConnectionChanged(false)
                    return@launch
                }
                val (token, workingUrl) = tokenResult
                onResolvedTokenServerUrl?.invoke(workingUrl)

                val activeRoom = LiveKit.create(
                    appContext = context.applicationContext,
                    options = RoomOptions(adaptiveStream = true, dynacast = true)
                )
                room = activeRoom

                // Subscribe to room events from SharedFlow
                scope.launch {
                    activeRoom.events.events.collect { event ->
                        when (event) {
                            is RoomEvent.Connected -> {
                                Log.i(tag, "Successfully connected to LiveKit room: ${activeRoom.name}")
                                isConnected = true
                                onConnectionChanged(true)
                                routeAudioToSpeaker()
                                scope.launch { publishMicrophone() }

                                // ── Start high-frequency audio level monitoring ──
                                audioMonitorJob?.cancel()
                                audioMonitorJob = scope.launch(Dispatchers.Default) {
                                    while (isConnected && room != null) {
                                        try {
                                            val currentRoom = room ?: break
                                            val localP = currentRoom.localParticipant
                                            val micRaw = localP.audioLevel
                                            val micLevel = if (micRaw > 0.001f) micRaw else (if (localP.isSpeaking) 0.35f else 0f)
                                            _inputAudioLevel.value = micLevel.coerceIn(0f, 1f)

                                            val remoteMax = currentRoom.remoteParticipants.values
                                                .maxOfOrNull { it.audioLevel } ?: 0f
                                            val anyRemoteSpeaking = currentRoom.remoteParticipants.values.any { it.isSpeaking }
                                            val agentLevel = if (remoteMax > 0.001f) remoteMax else (if (anyRemoteSpeaking) 0.45f else 0f)
                                            _outputAudioLevel.value = agentLevel.coerceIn(0f, 1f)
                                        } catch (_: Exception) {
                                            // Handle transient sync during disconnect
                                        }
                                        delay(50L)
                                    }
                                }
                            }
                            is RoomEvent.ActiveSpeakersChanged -> {
                                var localLvl = 0f
                                var remoteLvl = 0f
                                for (speaker in event.speakers) {
                                    val lvl = speaker.audioLevel
                                    if (speaker is LocalParticipant) {
                                        if (lvl > localLvl) localLvl = lvl
                                    } else {
                                        if (lvl > remoteLvl) remoteLvl = lvl
                                    }
                                }
                                if (localLvl > 0f) _inputAudioLevel.value = localLvl.coerceIn(0f, 1f)
                                if (remoteLvl > 0f) _outputAudioLevel.value = remoteLvl.coerceIn(0f, 1f)
                            }
                            is RoomEvent.Disconnected -> {
                                Log.w(tag, "Disconnected from LiveKit room")
                                isConnected = false
                                audioMonitorJob?.cancel()
                                audioMonitorJob = null
                                _inputAudioLevel.value = 0f
                                _outputAudioLevel.value = 0f
                                onConnectionChanged(false)
                            }
                            is RoomEvent.DataReceived -> {
                                val message = String(event.data)
                                Log.d(tag, "Received data packet from agent: $message")
                                onDataReceived(message)
                            }
                            is RoomEvent.TrackSubscribed -> {
                                Log.i(tag, "Subscribed to remote track: ${event.track.sid} (${event.track.kind}) from ${event.participant.identity?.value ?: event.participant.name}")
                                routeAudioToSpeaker()
                            }
                            is RoomEvent.ParticipantConnected -> {
                                Log.i(tag, "Participant joined room: ${event.participant.identity?.value ?: event.participant.name}")
                            }
                            is RoomEvent.ParticipantDisconnected -> {
                                Log.i(tag, "Participant left room: ${event.participant.identity?.value ?: event.participant.name}")
                                _outputAudioLevel.value = 0f
                            }
                            else -> Unit
                        }
                    }
                }

                activeRoom.connect(serverUrl, token)
            } catch (e: Exception) {
                Log.e(tag, "LiveKit room connection failed", e)
                isConnected = false
                onConnectionChanged(false)
            }
        }
    }

    private fun routeAudioToSpeaker() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // If headphones or headset are connected, prefer them over built-in speaker
                val headsetDevice = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                val targetDevice = headsetDevice ?: audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (targetDevice != null) {
                    audioManager.setCommunicationDevice(targetDevice)
                    Log.i(tag, "Audio communication device set to ${targetDevice.productName} (type ${targetDevice.type})")
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }

            // Ensure voice call and music stream volumes are at high level
            val maxVoiceVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVoiceVol * 0.9).toInt(), 0)
            val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusicVol * 0.9).toInt(), 0)
        } catch (e: Exception) {
            Log.w(tag, "Failed to route audio to speaker: ${e.message}")
        }
    }

    private suspend fun publishMicrophone() {
        val activeRoom = room ?: return
        try {
            activeRoom.localParticipant.setMicrophoneEnabled(true)
            Log.i(tag, "Microphone enabled and audio track published to room.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to enable microphone", e)
        }
    }

    fun publishVideoTrack(videoTrack: LocalVideoTrack) {
        val activeRoom = room ?: return
        scope.launch {
            try {
                activeRoom.localParticipant.publishVideoTrack(videoTrack)
                Log.i(tag, "Published video track to room.")
            } catch (e: Exception) {
                Log.e(tag, "Failed to publish video track", e)
            }
        }
    }

    fun sendData(jsonString: String) {
        val activeRoom = room ?: return
        scope.launch(Dispatchers.IO) {
            try {
                activeRoom.localParticipant.publishData(jsonString.toByteArray())
                if (jsonString.length > 200) {
                    Log.d(tag, "Sent data packet to agent (${jsonString.length} bytes)")
                } else {
                    Log.d(tag, "Sent data packet to agent: $jsonString")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to send data channel message", e)
            }
        }
    }

    fun disconnect() {
        audioMonitorJob?.cancel()
        audioMonitorJob = null
        _inputAudioLevel.value = 0f
        _outputAudioLevel.value = 0f
        try {
            room?.disconnect()
            room?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error disconnecting room", e)
        }
        try {
            localAudioTrack?.stop()
            localAudioTrack?.dispose()
        } catch (e: Exception) {
            Log.w(tag, "Error disposing audio track", e)
        }
        localAudioTrack = null
        room = null
        isConnected = false
        onConnectionChanged(false)
        Log.i(tag, "LiveKit room disconnected and cleaned up.")
    }

    private fun fetchToken(primaryUrl: String, roomName: String, userName: String): Pair<String, String>? {
        // Smart candidates list: Try primary first, then ADB USB reverse loopback, then Wi-Fi host IP, then emulator
        val candidates = linkedSetOf(
            primaryUrl,
            "http://127.0.0.1:8000",
            "http://10.113.162.33:8000",
            "http://10.0.2.2:8000"
        )

        for (candidate in candidates) {
            val cleanBase = candidate.trim().trimEnd('/')
            if (cleanBase.isEmpty()) continue
            try {
                Log.i(tag, "Attempting token fetch from candidate: $cleanBase")
                val encodedRoom = java.net.URLEncoder.encode(roomName, "UTF-8")
                val encodedUser = java.net.URLEncoder.encode(userName, "UTF-8")
                val url = "$cleanBase/token?room=$encodedRoom&user=$encodedUser"
                val request = Request.Builder().url(url).get().build()

                val fastClient = httpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(2500))
                    .readTimeout(java.time.Duration.ofMillis(3500))
                    .build()

                fastClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            if (json.has("token")) {
                                val token = json.getString("token")
                                Log.i(tag, "Successfully acquired LiveKit token from $cleanBase")
                                return Pair(token, cleanBase)
                            }
                        }
                    } else {
                        Log.w(tag, "Candidate $cleanBase responded with HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Candidate $cleanBase unreachable: ${e.message}")
            }
        }
        Log.e(tag, "All token server candidates failed.")
        return null
    }
}
