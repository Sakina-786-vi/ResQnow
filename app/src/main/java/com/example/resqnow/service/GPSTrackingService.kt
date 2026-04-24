package com.example.resqnow.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.example.resqnow.R
import com.example.resqnow.data.model.GpsState
import com.example.resqnow.data.repository.HighwayDatabase
import com.example.resqnow.ui.home.MainActivity
import com.example.resqnow.util.PreferencesManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import android.location.Geocoder

class GPSTrackingService : Service() {

    companion object {
        const val TAG = "HighwaySOS"
        const val ACTION_START   = "com.example.resqnow.START"
        const val ACTION_STOP    = "com.example.resqnow.STOP"
        const val ACTION_SMS_NOW = "com.example.resqnow.SMS_NOW"
        const val BROADCAST_GPS_UPDATE  = "com.example.resqnow.GPS_UPDATE"
        const val BROADCAST_SVC_STOPPED = "com.example.resqnow.SERVICE_STOPPED"
        const val CHANNEL_TRACKING  = "sos_tracking"
        const val NOTIF_ID_TRACKING = 1001
        const val GPS_INTERVAL_MS         = 30_000L
        const val GPS_FASTEST_INTERVAL_MS = 15_000L
        const val GPS_MIN_DISPLACEMENT_M  = 100f
        const val GRID_EXPIRY_MS          = 5 * 60 * 1000L
    }

    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefs: PreferencesManager
    private lateinit var notifManager: NotificationManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val localGrid    = HashMap<String, Pair<MutableSet<String>, Long>>()
    private var deviceToken  = ""

    override fun onCreate() {
        super.onCreate()
        prefs         = PreferencesManager(this)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        notifManager  = getSystemService(NotificationManager::class.java)
        deviceToken   = prefs.getDeviceToken()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START   -> { startForegroundTracking(); beginLocationUpdates() }
            ACTION_STOP    -> stopTracking()
            ACTION_SMS_NOW -> triggerEmergencySms()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::locationCallback.isInitialized)
            fusedLocation.removeLocationUpdates(locationCallback)
    }

    private fun beginLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, GPS_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(GPS_FASTEST_INTERVAL_MS)
            setMinUpdateDistanceMeters(GPS_MIN_DISPLACEMENT_M)
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { processLocation(it) }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.d(TAG, "GPS started — 30s interval")
        }
    }

    private fun processLocation(location: android.location.Location) {
        val lat = location.latitude
        val lon = location.longitude
        val (highwayGuess, km) = HighwayDatabase.identifyHighway(lat, lon)
        val cellId        = HighwayDatabase.getGridCellId(lat, lon)
        registerInGrid(cellId)
        val localCount  = localGrid[cellId]?.first?.size ?: 1
        val serverCount = prefs.getServerCount(cellId)
        val count       = maxOf(1, localCount, serverCount)

        // Start with a corridor-based guess, then try to replace it with a real road/place name.
        prefs.saveGpsStateSync(lat, lon, highwayGuess, km, count)

        val state = GpsState(
            latitude        = lat, longitude = lon,
            accuracy        = location.accuracy, speed = location.speed,
            highwayName     = highwayGuess, kmMarker = km,
            gridCellId      = cellId, vehicleCount = count,
            isRumorDebunked = count >= HighwayDatabase.RUMOR_DEBUNK_THRESHOLD
        )
        broadcastUpdate(state)
        updateNotification(state)
        serviceScope.launch {
            // Enrich "highwayName" with a real, user-relevant road/place name (non-static).
            val realName = resolveRoadOrPlaceName(lat, lon)
            if (!realName.isNullOrBlank() && realName != state.highwayName) {
                val enriched = state.copy(highwayName = realName, kmMarker = if (state.kmMarker == 0) 0 else state.kmMarker)
                prefs.saveGpsStateSync(lat, lon, enriched.highwayName, enriched.kmMarker, enriched.vehicleCount)
                withContext(Dispatchers.Main) {
                    broadcastUpdate(enriched)
                    updateNotification(enriched)
                }
            }

            uploadGridData(state, cellId, highwayGuess, km, lat, lon)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveRoadOrPlaceName(lat: Double, lon: Double): String? {
        // Geocoder may fail if backend is unavailable; that's fine (we keep highwayGuess).
        return runCatching {
            val geocoder = Geocoder(this, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lon, 1) ?: return null
            val a = list.firstOrNull() ?: return null
            // Prefer a road name; fall back to locality/admin area.
            val road = a.thoroughfare?.takeIf { it.isNotBlank() && it.lowercase(Locale.getDefault()) != "unnamed road" }
            val place = a.subLocality?.takeIf { it.isNotBlank() }
                ?: a.locality?.takeIf { it.isNotBlank() }
                ?: a.adminArea?.takeIf { it.isNotBlank() }
            road ?: place
        }.getOrNull()
    }

    private fun registerInGrid(cellId: String) {
        val now   = System.currentTimeMillis()
        val entry = localGrid.getOrPut(cellId) { Pair(mutableSetOf(), now) }
        entry.first.add(deviceToken)
        localGrid[cellId] = Pair(entry.first, now)
        localGrid.entries.removeAll { (_, v) -> now - v.second > GRID_EXPIRY_MS }
    }

    private suspend fun uploadGridData(
        state: GpsState, cellId: String, highway: String,
        km: Int, lat: Double, lon: Double
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val url  = java.net.URL("https://api.highwaysos.in/v1/grid/report")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5_000; readTimeout = 5_000; doOutput = true
            }
            val body = """{"cell":"$cellId","hw":"$highway","km":$km,"lat":$lat,"lon":$lon,"token":"$deviceToken","ts":${System.currentTimeMillis()}}"""
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode == 200) {
                val resp  = conn.inputStream.bufferedReader().readText()
                val count = Regex(""""count"\s*:\s*(\d+)""").find(resp)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (count != null) {
                    prefs.saveServerCount(cellId, count)
                    val updated = state.copy(vehicleCount = count,
                        isRumorDebunked = count >= HighwayDatabase.RUMOR_DEBUNK_THRESHOLD)
                    withContext(Dispatchers.Main) { broadcastUpdate(updated); updateNotification(updated) }
                }
            }
            conn.disconnect()
        }.onFailure { Log.d(TAG, "Offline: ${it.message}") }
    }

    private fun broadcastUpdate(state: GpsState) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BROADCAST_GPS_UPDATE).apply {
                putExtra("lat", state.latitude); putExtra("lon", state.longitude)
                putExtra("highway", state.highwayName); putExtra("km", state.kmMarker)
                putExtra("count", state.vehicleCount); putExtra("debunked", state.isRumorDebunked)
                putExtra("speed", state.speed); putExtra("accuracy", state.accuracy)
            }
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifManager.createNotificationChannel(
                NotificationChannel(CHANNEL_TRACKING, "Highway SOS Active",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "GPS tracking"; setShowBadge(false)
                    enableLights(false); enableVibration(false)
                }
            )
        }
    }

    private fun startForegroundTracking() {
        startForeground(NOTIF_ID_TRACKING, buildNotification("Detecting highway...", 0, 0, false))
        prefs.setTrackingActiveSync(true)
    }

    private fun updateNotification(state: GpsState) =
        notifManager.notify(NOTIF_ID_TRACKING,
            buildNotification(state.highwayName, state.kmMarker, state.vehicleCount, state.isRumorDebunked))

    private fun buildNotification(highway: String, km: Int, count: Int, debunked: Boolean): Notification {
        fun pi(req: Int, intent: Intent, isActivity: Boolean = false): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (isActivity) PendingIntent.getActivity(this, req, intent, flags)
            else PendingIntent.getService(this, req, intent, flags)
        }
        val openApp = pi(0, Intent(this, MainActivity::class.java), true)
        val call112 = pi(1, Intent(Intent.ACTION_CALL, Uri.parse("tel:112")), true)
        // "SOS SMS" should prompt for contacts if not configured; launching an Activity is allowed from a notification click.
        val sendSms = pi(2, Intent(this, com.example.resqnow.ui.sos.SendSosSmsActivity::class.java), true)
        val stop    = pi(3, Intent(this, GPSTrackingService::class.java).setAction(ACTION_STOP))
        val body = when {
            highway.isEmpty() || highway == "Local Road" -> "GPS active · Scanning highways..."
            debunked  -> "$highway KM$km · ✅ $count vehicles — CLEAR"
            count > 0 -> "$highway KM$km · $count vehicles tracked"
            else      -> "$highway KM$km · Monitoring..."
        }
        return NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_sos_tile)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(body)
            .setOngoing(true).setColor(0xFFD32F2F.toInt()).setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, "📞 Call 112", call112)
            .addAction(0, "📲 SOS SMS",  sendSms)
            .addAction(0, "⏹ Stop",      stop)
            .build()
    }

    private fun triggerEmergencySms() {
        val (lat, lon, hwPair) = prefs.getLastGpsSync()
        val (highway, km) = hwPair
        val time = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date())
        val msg  = "🚨 HIGHWAY SOS EMERGENCY\nLocation: $highway KM $km\n" +
                "GPS: $lat, $lon\nMaps: https://maps.google.com/?q=$lat,$lon\n" +
                "Time: $time\n" +
                "Hospitals: CityCare Hospital - 0111111111; Metro Health - 0222222222; Sunrise Clinic - 0333333333; Green Valley Hospital - 0444444444\n" +
                "Police: Central Police Station - 0555555555; North Zone Police - 0666666666; East Gate Police - 0777777777; West Line Police - 0888888888\n" +
                "Sent via Highway SOS"
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) return

        val phones = prefs.getEmergencyContactPhones()
        if (phones.isEmpty() && !prefs.hasAnyEmergencyContact()) {
            // Still send to default helpline
        }

        val sms = SmsManager.getDefault()
        val destinations = (phones + "8097318543").distinct()
        destinations.forEach { phone ->
            runCatching { sms.sendTextMessage(phone, null, msg, null, null) }
        }
    }

    private fun stopTracking() {
        if (::locationCallback.isInitialized) fusedLocation.removeLocationUpdates(locationCallback)
        prefs.setTrackingActiveSync(false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_SVC_STOPPED))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
