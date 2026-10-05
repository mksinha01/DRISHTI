package ai.drishti.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * On-device Android TextToSpeech engine.
 * Provides zero-latency speech for obstacle alerts and offline fallbacks.
 */
class LocalTTS(
    context: Context,
    private val onInitialized: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private val tag = "LocalTTS"
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(tag, "US English language not supported by local TTS; using device default.")
                tts?.setLanguage(Locale.getDefault())
            }
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            tts?.setSpeechRate(1.0f)
            isReady = true
            Log.i(tag, "Local TTS initialized successfully.")
            onInitialized?.invoke()
        } else {
            Log.e(tag, "Failed to initialize local TextToSpeech engine (status=$status)")
        }
    }

    /**
     * Speaks text with priority. If flush=true, interrupts ongoing speech (crucial for safety alerts).
     */
    fun speak(text: String, flush: Boolean = true) {
        if (!isReady || tts == null) {
            Log.w(tag, "Local TTS not ready, dropping announcement: $text")
            return
        }

        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, System.currentTimeMillis().toString())
        Log.d(tag, "Spoke: $text (flush=$flush)")
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        Log.i(tag, "Local TTS shut down.")
    }
}
