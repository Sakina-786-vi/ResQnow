package com.example.resqnow.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.resqnow.data.repository.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EmergencySmsHelper {

    const val DEFAULT_HELPLINE = "8097318543"
    private const val MAX_RETRIES = 1

    data class SendResult(
        val sentCount: Int,
        val failedCount: Int,
        val invalidCount: Int
    )

    suspend fun getUserEmergencyPhones(
        context: Context,
        prefs: PreferencesManager
    ): List<String> {
        val userId = prefs.getRegisteredUserId()
        val fromDb = if (userId > 0) {
            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(context)
                    .emergencyContactDao()
                    .getForUser(userId)
                    .map { it.phone }
            }
        } else {
            emptyList()
        }

        val fromPrefs = prefs.getEmergencyContactPhones()
        return (fromDb + fromPrefs)
            .map { normalizePhone(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    suspend fun getSmsDestinations(
        context: Context,
        prefs: PreferencesManager
    ): List<String> {
        return (getUserEmergencyPhones(context, prefs) + DEFAULT_HELPLINE).distinct()
    }

    fun buildEmergencyMessage(prefs: PreferencesManager): String {
        val (lat, lon, hwPair) = prefs.getLastGpsSync()
        val (highway, km) = hwPair
        val time = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date())
        return """
🚨 RESQNOW EMERGENCY
Location: $highway KM $km
GPS: $lat, $lon
Maps: https://maps.google.com/?q=$lat,$lon
Time: $time
Hospitals: CityCare Hospital - 0111111111; Metro Health - 0222222222; Sunrise Clinic - 0333333333; Green Valley Hospital - 0444444444
Police: Central Police Station - 0555555555; North Zone Police - 0666666666; East Gate Police - 0777777777; West Line Police - 0888888888
Sent via ResQnow
        """.trimIndent()
    }

    fun canSendSms(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun sendEmergencySmsAutomatically(
        context: Context,
        phones: List<String>,
        message: String
    ): SendResult {
        if (!canSendSms(context)) return SendResult(0, phones.size, 0)

        val recipients = phones.map { normalizePhone(it) }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty()) return SendResult(0, 0, 0)

        val sms = SmsManager.getDefault()
        var sent = 0
        var failed = 0
        var invalid = 0

        recipients.forEach { phone ->
            if (!isLikelyPhoneNumber(phone)) {
                invalid++
            } else {
                var ok = false
                repeat(MAX_RETRIES + 1) {
                    runCatching {
                        val parts = sms.divideMessage(message)
                        sms.sendMultipartTextMessage(phone, null, parts, null, null)
                        ok = true
                    }
                    if (ok) return@repeat
                }
                if (ok) sent++ else failed++
            }
        }

        return SendResult(sent, failed, invalid)
    }

    fun openSmsComposer(
        context: Context,
        phones: List<String>,
        message: String
    ): Boolean {
        val recipients = phones.map { normalizePhone(it) }.filter { it.isNotEmpty() }.distinct()
        val uri = Uri.parse("smsto:${recipients.joinToString(";")}")
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    }

    private fun isLikelyPhoneNumber(phone: String): Boolean {
        val digits = phone.filter { it.isDigit() }
        return digits.length in 8..15
    }

    private fun normalizePhone(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val body = if (trimmed.startsWith("+")) {
            "+" + trimmed.drop(1).filter { it.isDigit() }
        } else {
            trimmed.filter { it.isDigit() }
        }
        return body
    }
}
