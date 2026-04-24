package com.example.resqnow.util

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Centralized preferences — SharedPreferences for sync access.
 * Services, TileService, BroadcastReceiver all need sync reads.
 */
class PreferencesManager(private val context: Context) {

    private val sync: SharedPreferences =
        context.getSharedPreferences("sos_sync", Context.MODE_PRIVATE)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sos_prefs", Context.MODE_PRIVATE)
    private val grid: SharedPreferences =
        context.getSharedPreferences("sos_grid", Context.MODE_PRIVATE)

    // ── Tracking ──────────────────────────────────────────────────────

    fun getTrackingActiveSync(): Boolean = sync.getBoolean("tracking_active", false)
    fun setTrackingActiveSync(active: Boolean) =
        sync.edit().putBoolean("tracking_active", active).apply()

    // ── GPS State ─────────────────────────────────────────────────────

    fun saveGpsStateSync(lat: Double, lon: Double, highway: String, km: Int, count: Int) {
        sync.edit()
            .putFloat("lat", lat.toFloat())
            .putFloat("lon", lon.toFloat())
            .putString("highway", highway)
            .putInt("km", km)
            .putInt("count", count)
            .apply()
    }

    /** Returns Triple(lat, lon, Pair(highway, km)) */
    fun getLastGpsSync(): Triple<Double, Double, Pair<String, Int>> = Triple(
        sync.getFloat("lat", 0f).toDouble(),
        sync.getFloat("lon", 0f).toDouble(),
        Pair(sync.getString("highway", "") ?: "", sync.getInt("km", 0))
    )

    fun getLastVehicleCount(): Int = sync.getInt("count", 0)

    // ── Grid / Server Counts ──────────────────────────────────────────

    fun getServerCount(cellId: String): Int = grid.getInt("count_$cellId", 0)
    fun saveServerCount(cellId: String, count: Int) =
        grid.edit().putInt("count_$cellId", count).apply()

    // ── App Config ────────────────────────────────────────────────────

    fun isOnboardingDone(): Boolean    = prefs.getBoolean("onboarding_done", false)
    fun isFirstLaunch(): Boolean       = prefs.getBoolean("first_launch", true)

    fun setOnboardingDone() = prefs.edit()
        .putBoolean("onboarding_done", true)
        .putBoolean("first_launch", false)
        .apply()

    fun saveRegisteredUserId(id: Long) =
        prefs.edit().putLong("registered_user_id", id).apply()

    fun getRegisteredUserId(): Long = prefs.getLong("registered_user_id", -1L)

    fun isUserRegistered(): Boolean = getRegisteredUserId() > 0

    fun saveFcmToken(token: String) =
        prefs.edit().putString("fcm_token", token).apply()

    fun getFcmToken(): String? = prefs.getString("fcm_token", null)

    fun getDeviceToken(): String {
        val t = prefs.getString("device_token", null)
        if (t != null) return t
        val token = "sos_" + UUID.randomUUID().toString().take(8)
        prefs.edit().putString("device_token", token).apply()
        return token
    }

    // ── Emergency contacts (4 slots) ─────────────────────────────────

    fun saveEmergencyContacts(contacts: List<Pair<String, String>>) {
        // Expect exactly 4 slots; blanks allowed but we'll persist them as-is.
        val e = prefs.edit()
        for (i in 0 until 4) {
            val (name, phone) = contacts.getOrNull(i) ?: ("" to "")
            e.putString("ec_${i}_name", name.trim())
            e.putString("ec_${i}_phone", phone.trim())
        }
        e.apply()
    }

    fun getEmergencyContacts(): List<Pair<String, String>> =
        (0 until 4).map { i ->
            (prefs.getString("ec_${i}_name", "") ?: "") to (prefs.getString("ec_${i}_phone", "") ?: "")
        }

    fun hasAnyEmergencyContact(): Boolean =
        getEmergencyContacts().any { (_, phone) -> phone.filter { it.isDigit() }.length >= 6 }

    fun getEmergencyContactPhones(): List<String> =
        getEmergencyContacts()
            .map { it.second.trim() }
            .filter { it.isNotBlank() }

    // ── Session ───────────────────────────────────────────────────────

    fun saveActiveSessionId(id: Long) = sync.edit().putLong("session_id", id).apply()
    fun getActiveSessionId(): Long     = sync.getLong("session_id", -1L)
    fun saveTotalKmThisSession(km: Double) =
        sync.edit().putFloat("session_km", km.toFloat()).apply()
    fun getTotalKmThisSession(): Double = sync.getFloat("session_km", 0f).toDouble()
}
