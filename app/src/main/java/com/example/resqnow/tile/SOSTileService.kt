package com.example.resqnow.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.resqnow.service.GPSTrackingService
import com.example.resqnow.ui.home.MainActivity
import com.example.resqnow.util.PreferencesManager

class SOSTileService : TileService() {

    private val prefsManager by lazy { PreferencesManager(this) }

    override fun onStartListening() { super.onStartListening(); refreshTile() }
    override fun onTileAdded()      { super.onTileAdded(); refreshTile() }

    override fun onClick() {
        super.onClick()
        val isActive = prefsManager.getTrackingActiveSync()
        if (isActive) stopTracking() else {
            if (!hasLocationPermission()) { launchAppForPermissions(); return }
            startTracking()
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile    = qsTile ?: return
        val isActive = prefsManager.getTrackingActiveSync()
        val (_, _, hwPair) = prefsManager.getLastGpsSync()
        val (highway, km)  = hwPair
        val count = getSharedPreferences("sos_sync", MODE_PRIVATE).getInt("count", 0)
        if (isActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(com.example.resqnow.R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (highway.isNotEmpty() && highway != "Local Road")
                    "$highway KM$km · $count vehicles" else "GPS Active"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(com.example.resqnow.R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Tap to activate"
        }
        tile.updateTile()
    }

    private fun startTracking() {
        val intent = Intent(this, GPSTrackingService::class.java).setAction(GPSTrackingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopTracking() =
        startService(Intent(this, GPSTrackingService::class.java).setAction(GPSTrackingService.ACTION_STOP))

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchAppForPermissions() {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
}
