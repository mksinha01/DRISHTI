package ai.drishti.emergency

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class EmergencyContact(
    val name: String,
    val phoneNumber: String,
    val relationship: String = "Emergency Contact"
)

/**
 * Manages encrypted local persistence of emergency contacts.
 */
class ContactStore(context: Context) {

    private val tag = "ContactStore"
    private val prefs: SharedPreferences = context.getSharedPreferences("drishti_contacts", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CONTACTS = "emergency_contacts_json"
    }

    fun getContacts(): List<EmergencyContact> {
        val jsonString = prefs.getString(KEY_CONTACTS, null) ?: return defaultContacts()
        val contacts = mutableListOf<EmergencyContact>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                contacts.add(
                    EmergencyContact(
                        name = obj.getString("name"),
                        phoneNumber = obj.getString("phoneNumber"),
                        relationship = obj.optString("relationship", "Emergency Contact")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse stored contacts", e)
            return defaultContacts()
        }
        return if (contacts.isEmpty()) defaultContacts() else contacts
    }

    fun saveContacts(contacts: List<EmergencyContact>) {
        try {
            val jsonArray = JSONArray()
            for (c in contacts) {
                val obj = JSONObject().apply {
                    put("name", c.name)
                    put("phoneNumber", c.phoneNumber)
                    put("relationship", c.relationship)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_CONTACTS, jsonArray.toString()).apply()
            Log.i(tag, "Saved ${contacts.size} emergency contacts.")
        } catch (e: Exception) {
            Log.e(tag, "Error saving emergency contacts", e)
        }
    }

    private fun defaultContacts(): List<EmergencyContact> {
        return listOf(
            EmergencyContact("Primary Caregiver", "+919876543210", "Guardian")
        )
    }
}
