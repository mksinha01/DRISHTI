package ai.drishti.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Handles provisioning Wi-Fi hotspot credentials to the ESP32-CAM.
 */
class WifiProvisioner(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()
) {

    private val tag = "WifiProvisioner"

    suspend fun provision(esp32ApUrl: String, ssid: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("ssid", ssid)
                .add("password", pass)
                .build()

            val request = Request.Builder()
                .url("$esp32ApUrl/provision")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                Log.i(tag, "Provisioning response code: ${response.code}")
                success
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to provision ESP32 credentials", e)
            false
        }
    }
}
