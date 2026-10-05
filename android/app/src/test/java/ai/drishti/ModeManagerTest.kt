package ai.drishti

import ai.drishti.mode.DrishtiMode
import ai.drishti.mode.ModeManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModeManagerTest {

    private lateinit var modeManager: ModeManager

    @Before
    fun setUp() {
        modeManager = ModeManager(DrishtiMode.Assistant)
    }

    @Test
    fun testInitialModeIsAssistant() {
        assertEquals("ASSISTANT", modeManager.currentMode.name)
        assertTrue(modeManager.currentMode is DrishtiMode.Assistant)
    }

    @Test
    fun testSwitchToNavigation() {
        val changed = modeManager.switchTo(DrishtiMode.Navigation("Central Market"))
        assertTrue(changed)
        assertTrue(modeManager.currentMode is DrishtiMode.Navigation)
        assertEquals("Central Market", (modeManager.currentMode as DrishtiMode.Navigation).destination)
    }

    @Test
    fun testSwitchToOcr() {
        modeManager.switchTo(DrishtiMode.Ocr())
        assertTrue(modeManager.currentMode is DrishtiMode.Ocr)
        assertEquals("OCR", modeManager.currentMode.name)
    }

    @Test
    fun testEmergencyAndCancelRestoresPreviousMode() {
        // Start in Navigation mode
        modeManager.switchTo(DrishtiMode.Navigation("Hospital"))
        assertEquals("NAVIGATION", modeManager.currentMode.name)

        // Trigger Emergency
        modeManager.switchTo(DrishtiMode.Emergency("voice_trigger", 10))
        assertEquals("EMERGENCY", modeManager.currentMode.name)

        // Cancel Emergency -> Should restore Navigation mode
        val cancelled = modeManager.cancelEmergency()
        assertTrue(cancelled)
        assertEquals("NAVIGATION", modeManager.currentMode.name)
        assertEquals("Hospital", (modeManager.currentMode as DrishtiMode.Navigation).destination)
    }

    @Test
    fun testListenerCallback() {
        var callbackReceived = false
        var spokenTextReceived = ""

        modeManager.addListener { oldMode, newMode, spokenText ->
            callbackReceived = true
            spokenTextReceived = spokenText
        }

        modeManager.switchTo(DrishtiMode.Ocr())
        assertTrue(callbackReceived)
        assertTrue(spokenTextReceived.contains("Reading mode active"))
    }
}
