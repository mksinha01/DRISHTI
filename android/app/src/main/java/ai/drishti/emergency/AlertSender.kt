package ai.drishti.emergency

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log

data class EmergencyPayload(
    val userName: String,
    val latitude: Double,
    val longitude: Double,
    val triggerReason: String,
    val timestamp: String
)

/**
 * Dispatches multi-channel emergency notifications:
 * 1. SMS with Google Maps coordinates.
 * 2. WhatsApp intent with alert message.
 * 3. Direct phone dialer to primary contact.
 */
class AlertSender(private val context: Context) {

    private val tag = "AlertSender"

    fun sendEmergencyAlert(contacts: List<EmergencyContact>, payload: EmergencyPayload): Boolean {
        if (contacts.isEmpty()) {
            Log.w(tag, "No emergency contacts configured to receive alert.")
            return false
        }

        val mapsUrl = "https://maps.google.com/?q=${payload.latitude},${payload.longitude}"
        val message = "EMERGENCY ALERT: ${payload.userName} triggered Drishti emergency (${payload.triggerReason}). " +
                "Location: $mapsUrl at ${payload.timestamp}"

        var anySent = false

        // 1. Send SMS to all contacts
        for (contact in contacts) {
            val sent = sendSms(contact.phoneNumber, message)
            if (sent) anySent = true
        }

        // 2. Launch WhatsApp Intent if available
        sendWhatsApp(contacts.first().phoneNumber, message)

        // 3. Dial primary contact
        dialEmergencyContact(contacts.first().phoneNumber)

        return anySent
    }

    private fun sendSms(phoneNumber: String, text: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(text)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            Log.i(tag, "Emergency SMS dispatched to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to send SMS to $phoneNumber", e)
            false
        }
    }

    private fun sendWhatsApp(phoneNumber: String, text: String) {
        try {
            val formattedNumber = phoneNumber.replace("+", "").replace(" ", "")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$formattedNumber&text=${Uri.encode(text)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(tag, "Dispatched WhatsApp alert intent.")
        } catch (e: Exception) {
            Log.w(tag, "WhatsApp app not available or intent dispatch failed", e)
        }
    }

    private fun dialEmergencyContact(phoneNumber: String) {
        try {
            val dialIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            Log.i(tag, "Dispatched emergency phone call to $phoneNumber")
        } catch (e: Exception) {
            Log.e(tag, "Failed to trigger phone call", e)
        }
    }
}
