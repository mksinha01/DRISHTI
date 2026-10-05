package ai.drishti.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clean, high-contrast, dual-channel audio level visualizer for Drishti.
 *
 * Left Channel:  Microphone input ("YOU (MIC)") in Neon Cyan (#00E5FF)
 * Right Channel: AI Agent output ("AGENT (AI)") in Neon Emerald (#00E676)
 *
 * Features:
 *  - 5-bar responsive animated equalizer waves with harmonic physics
 *  - Precision horizontal volume level track with peak-hold dot
 *  - Real-time dB readout & pulsating active-speaking indicator
 *  - Smooth 60 FPS attack/decay interpolation
 *  - Ambient resting wave when silent
 */
class AudioLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ─── Color Palette ─────────────────────────────────────────────────────
    private val cCardBg       = Color.parseColor("#141720")
    private val cCardBorder   = Color.parseColor("#252B3A")
    private val cDivider      = Color.parseColor("#1F2432")
    private val cTrackBg      = Color.parseColor("#1B202D")

    // Input (Mic / You)
    private val cInputBright  = Color.parseColor("#00E5FF")   // Electric Cyan
    private val cInputDim     = Color.parseColor("#005B66")
    private val cInputHalo    = Color.parseColor("#2600E5FF")

    // Output (Agent / Groq LLM)
    private val cOutputBright = Color.parseColor("#00E676")   // Neon Emerald
    private val cOutputDim    = Color.parseColor("#00542B")
    private val cOutputHalo   = Color.parseColor("#2600E676")

    // Text & Guides
    private val cTextPrimary   = Color.parseColor("#E0E6F0")
    private val cTextMuted     = Color.parseColor("#748096")
    private val cTextActive    = Color.parseColor("#FFFFFF")

    // ─── Measurements (density scaled) ────────────────────────────────────
    private val den: Float = resources.displayMetrics.density
    private val cornerRadius = 14f * den
    private val barRadius    = 2.5f * den
    private val trackRadius  = 2f * den
    private val dotRadius    = 3.5f * den

    // ─── Paints ───────────────────────────────────────────────────────────
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cCardBg
        style = Paint.Style.FILL
    }
    private val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cCardBorder
        style = Paint.Style.STROKE
        strokeWidth = 1f * den
    }
    private val pDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cDivider
        strokeWidth = 1f * den
    }
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cTrackBg
        style = Paint.Style.FILL
    }
    private val pTextTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cTextPrimary
        textSize = 10.5f * den
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pTextDb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cTextMuted
        textSize = 9.5f * den
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val pDotIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cInputBright
        style = Paint.Style.FILL
    }
    private val pDotOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cOutputBright
        style = Paint.Style.FILL
    }
    private val pHaloIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cInputHalo
        style = Paint.Style.FILL
    }
    private val pHaloOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cOutputHalo
        style = Paint.Style.FILL
    }

    private val pWaveIn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pWaveOut = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLevelIn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLevelOut = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pPeakDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // ─── Dynamic Level State ──────────────────────────────────────────────
    @Volatile var rawInputLevel: Float = 0f; private set
    @Volatile var rawOutputLevel: Float = 0f; private set

    fun setInputAudioLevel(level: Float) {
        rawInputLevel = level.coerceIn(0f, 1f)
        if (!anim.isRunning) {
            post { startMeter() }
        }
    }

    fun setOutputAudioLevel(level: Float) {
        rawOutputLevel = level.coerceIn(0f, 1f)
        if (!anim.isRunning) {
            post { startMeter() }
        }
    }

    // Display smoothed values
    private var dispIn  = 0f
    private var dispOut = 0f

    // Peak levels & timers
    private var peakIn  = 0f
    private var peakOut = 0f
    private var peakTimeIn  = 0L
    private var peakTimeOut = 0L
    private val peakHoldMs  = 650L
    private val peakFadeMs  = 250f

    // Ambient phase for organic wave motion
    private var phase: Float = 0f

    // Harmonic weights for 5 equalizer wave bars
    private val harmonics = floatArrayOf(0.60f, 0.95f, 1.00f, 0.85f, 0.50f)

    // Animation ticker (~60 FPS)
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { tick() }
    }

    // ─── Cached Geometry ──────────────────────────────────────────────────
    private val cardRect = RectF()
    private val leftTrack = RectF()
    private val rightTrack = RectF()
    private val tempBarRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val strokeHalf = 0.5f * den
        cardRect.set(strokeHalf, strokeHalf, w.toFloat() - strokeHalf, h.toFloat() - strokeHalf)

        // Shaders for wave bars and level bars
        val waveTop = 28f * den
        val waveBot = 52f * den
        pWaveIn.shader = LinearGradient(0f, waveTop, 0f, waveBot,
            intArrayOf(cInputBright, cInputDim), null, Shader.TileMode.CLAMP)
        pWaveOut.shader = LinearGradient(0f, waveTop, 0f, waveBot,
            intArrayOf(cOutputBright, cOutputDim), null, Shader.TileMode.CLAMP)

        pLevelIn.shader = LinearGradient(0f, 0f, w / 2f, 0f,
            intArrayOf(cInputDim, cInputBright), null, Shader.TileMode.CLAMP)
        pLevelOut.shader = LinearGradient(w / 2f, 0f, w.toFloat(), 0f,
            intArrayOf(cOutputDim, cOutputBright), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Outer Container Card
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, pBg)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, pBorder)

        // 2. Middle Divider
        val midX = w / 2f
        canvas.drawLine(midX, 10f * den, midX, h - 10f * den, pDivider)

        // 3. Render Channels
        val sidePad = 14f * den
        val colW = midX - sidePad - 8f * den

        // ── LEFT CHANNEL: MIC (YOU) ──
        drawChannel(
            canvas = canvas,
            startX = sidePad,
            colW = colW,
            title = "YOU (MIC)",
            level = dispIn,
            peak = peakIn,
            isOutput = false,
            pWave = pWaveIn,
            pLevel = pLevelIn,
            pDot = pDotIn,
            pHalo = pHaloIn
        )

        // ── RIGHT CHANNEL: AGENT (AI) ──
        drawChannel(
            canvas = canvas,
            startX = midX + 8f * den,
            colW = colW,
            title = "AGENT (AI)",
            level = dispOut,
            peak = peakOut,
            isOutput = true,
            pWave = pWaveOut,
            pLevel = pLevelOut,
            pDot = pDotOut,
            pHalo = pHaloOut
        )
    }

    private fun drawChannel(
        canvas: Canvas,
        startX: Float,
        colW: Float,
        title: String,
        level: Float,
        peak: Float,
        isOutput: Boolean,
        pWave: Paint,
        pLevel: Paint,
        pDot: Paint,
        pHalo: Paint
    ) {
        val h = height.toFloat()
        val topY = 18f * den
        val isSpeaking = level > 0.015f

        // Perceptually expanded levels (square root expands low amplitude voice energy)
        val visualLevel = kotlin.math.sqrt(level.coerceIn(0f, 1f))
        val visualPeak = kotlin.math.sqrt(peak.coerceIn(0f, 1f))

        // ── A. Header Row: Dot Indicator + Title + dB readout ──
        val dotCenterX = startX + dotRadius
        val dotCenterY = topY - 3.5f * den

        // Glowing pulse halo when active
        if (isSpeaking) {
            val pulse = 1f + 0.35f * sin(phase * 4f)
            canvas.drawCircle(dotCenterX, dotCenterY, dotRadius * (1.6f * pulse), pHalo)
        }
        canvas.drawCircle(dotCenterX, dotCenterY, dotRadius, pDot)

        // Title text
        val titleX = dotCenterX + dotRadius + 6f * den
        pTextTitle.color = if (isSpeaking) cTextActive else cTextPrimary
        canvas.drawText(title, titleX, topY, pTextTitle)

        // dB readout on right of column
        val endX = startX + colW
        val dbText = formatDb(level)
        pTextDb.color = if (isSpeaking) (if (isOutput) cOutputBright else cInputBright) else cTextMuted
        canvas.drawText(dbText, endX, topY, pTextDb)

        // ── B. Equalizer Waveform Bars (Middle) ──
        val waveTopY = 27f * den
        val waveBotY = 53f * den
        val maxWaveH = waveBotY - waveTopY
        val numBars = harmonics.size
        val barGap = 4f * den
        val barW = ((colW * 0.48f) - (numBars - 1) * barGap) / numBars

        val waveStartX = startX
        for (i in 0 until numBars) {
            val bx = waveStartX + i * (barW + barGap)
            // Harmonic motion + gentle sinusoidal phase wobble
            val phaseOffset = i * 0.7f
            val wobble = sin(phase * 3f + phaseOffset) * 0.15f
            val activeH = (visualLevel * harmonics[i] * (1f + wobble)).coerceIn(0f, 1f) * maxWaveH

            // Baseline resting height so bars look alive
            val restH = 2.5f * den + sin(phase * 1.5f + i) * 0.8f * den
            val barH = if (level > 0.008f) activeH.coerceAtLeast(restH) else restH

            tempBarRect.set(bx, waveBotY - barH, bx + barW, waveBotY)
            canvas.drawRoundRect(tempBarRect, barRadius, barRadius, pWave)
        }

        // ── C. Horizontal Level Capsule Bar + Peak Marker (Bottom) ──
        val trackY = 60f * den
        val trackH = 4.5f * den
        val trackRect = if (!isOutput) leftTrack else rightTrack
        trackRect.set(startX, trackY, startX + colW, trackY + trackH)

        // Background Track
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, pTrack)

        // Filled active progress (scaled perceptually)
        val fillW = (colW * visualLevel).coerceIn(0f, colW)
        if (fillW > 1f) {
            tempBarRect.set(startX, trackY, startX + fillW, trackY + trackH)
            canvas.drawRoundRect(tempBarRect, trackRadius, trackRadius, pLevel)
        }

        // Floating Peak Marker
        val peakX = (startX + colW * visualPeak).coerceIn(startX, startX + colW - 2f * den)
        if (peak > 0.02f) {
            val pDotR = 2.2f * den
            canvas.drawCircle(peakX, trackY + trackH / 2f, pDotR, pPeakDot)
        }
    }

    private fun formatDb(level: Float): String {
        return if (level <= 0.008f) {
            "IDLE"
        } else {
            val db = 20.0 * log10(level.toDouble().coerceIn(0.0001, 1.0))
            String.format(Locale.US, "%.0f dB", db.coerceIn(-48.0, 0.0))
        }
    }

    // ─── Smooth Tick Physics (~60 FPS) ─────────────────────────────────────
    private fun tick() {
        phase += 0.05f
        if (phase > 1000f) phase = 0f

        // Fast attack (react instantly), smooth decay (glide down naturally)
        val attack = 0.38f
        val decay  = 0.14f

        val kIn = if (rawInputLevel > dispIn) attack else decay
        dispIn += (rawInputLevel - dispIn) * kIn

        val kOut = if (rawOutputLevel > dispOut) attack else decay
        dispOut += (rawOutputLevel - dispOut) * kOut

        dispIn  = dispIn.coerceIn(0f, 1f)
        dispOut = dispOut.coerceIn(0f, 1f)

        // Peak Hold & Fade logic
        val now = System.currentTimeMillis()
        if (rawInputLevel > peakIn) {
            peakIn = rawInputLevel
            peakTimeIn = now
        } else if (now - peakTimeIn > peakHoldMs) {
            val elapsed = now - peakTimeIn - peakHoldMs
            peakIn = (peakIn - (elapsed / peakFadeMs) * 0.04f).coerceAtLeast(dispIn)
        }

        if (rawOutputLevel > peakOut) {
            peakOut = rawOutputLevel
            peakTimeOut = now
        } else if (now - peakTimeOut > peakHoldMs) {
            val elapsed = now - peakTimeOut - peakHoldMs
            peakOut = (peakOut - (elapsed / peakFadeMs) * 0.04f).coerceAtLeast(dispOut)
        }

        invalidate()
    }

    // ─── Lifecycle & Animation Controls ────────────────────────────────────
    fun startMeter() {
        if (!anim.isRunning) anim.start()
    }

    fun stopMeter() {
        anim.cancel()
        dispIn = 0f
        dispOut = 0f
        peakIn = 0f
        peakOut = 0f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMeter()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMeter()
    }
}