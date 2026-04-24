package com.example.resqnow.ui.home

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.resqnow.data.model.EmergencyFacility
import com.example.resqnow.data.model.GpsState
import com.example.resqnow.data.repository.HighwayDatabase
import com.example.resqnow.service.GPSTrackingService
import com.example.resqnow.util.PreferencesManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

sealed class RumorStatus {
    object NoData : RumorStatus()
    data class Monitoring(val count: Int) : RumorStatus()
    data class Debunked(val count: Int, val highway: String, val km: Int) : RumorStatus()
    data class Alert(val message: String) : RumorStatus()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsManager = PreferencesManager(application)

    private val _gpsState         = MutableLiveData<GpsState>()
    val gpsState: LiveData<GpsState> = _gpsState

    private val _isTracking       = MutableLiveData(false)
    val isTracking: LiveData<Boolean> = _isTracking

    private val _lastUpdateTime   = MutableLiveData("")
    val lastUpdateTime: LiveData<String> = _lastUpdateTime

    private val _rumorStatus      = MutableLiveData<RumorStatus>(RumorStatus.NoData)
    val rumorStatus: LiveData<RumorStatus> = _rumorStatus

    private val _nearbyFacilities = MutableLiveData<List<EmergencyFacility>>()
    val nearbyFacilities: LiveData<List<EmergencyFacility>> = _nearbyFacilities

    private val gpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                GPSTrackingService.BROADCAST_GPS_UPDATE -> {
                    val state = GpsState(
                        latitude        = intent.getDoubleExtra("lat", 0.0),
                        longitude       = intent.getDoubleExtra("lon", 0.0),
                        highwayName     = intent.getStringExtra("highway") ?: "",
                        kmMarker        = intent.getIntExtra("km", 0),
                        vehicleCount    = intent.getIntExtra("count", 0),
                        isRumorDebunked = intent.getBooleanExtra("debunked", false),
                        speed           = intent.getFloatExtra("speed", 0f),
                        accuracy        = intent.getFloatExtra("accuracy", 0f)
                    )
                    _gpsState.postValue(state)
                    _lastUpdateTime.postValue(
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                    updateRumorStatus(state)
                    loadNearbyFacilities(state.latitude, state.longitude)
                }
                GPSTrackingService.BROADCAST_SVC_STOPPED -> _isTracking.postValue(false)
            }
        }
    }

    init {
        _isTracking.value = prefsManager.getTrackingActiveSync()
        LocalBroadcastManager.getInstance(application).registerReceiver(
            gpsReceiver, IntentFilter().apply {
                addAction(GPSTrackingService.BROADCAST_GPS_UPDATE)
                addAction(GPSTrackingService.BROADCAST_SVC_STOPPED)
            }
        )
        val (lat, lon, hwPair) = prefsManager.getLastGpsSync()
        if (lat != 0.0) {
            val (highway, km) = hwPair
            _gpsState.value = GpsState(latitude = lat, longitude = lon, highwayName = highway, kmMarker = km)
            loadNearbyFacilities(lat, lon)
        }
    }

    fun startTracking(context: Context) {
        _isTracking.value = true
        val intent = Intent(context, GPSTrackingService::class.java).setAction(GPSTrackingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stopTracking(context: Context) {
        _isTracking.value = false
        context.startService(Intent(context, GPSTrackingService::class.java).setAction(GPSTrackingService.ACTION_STOP))
    }

    private fun updateRumorStatus(state: GpsState) {
        _rumorStatus.postValue(when {
            state.vehicleCount == 0  -> RumorStatus.NoData
            state.isRumorDebunked    -> RumorStatus.Debunked(state.vehicleCount, state.highwayName, state.kmMarker)
            else                     -> RumorStatus.Monitoring(state.vehicleCount)
        })
    }

    private fun loadNearbyFacilities(lat: Double, lon: Double) {
        viewModelScope.launch {
            // Avoid hardcoded/static facilities list. UI uses Maps intent for nearby lookup.
            _nearbyFacilities.postValue(emptyList())
        }
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(gpsReceiver)
    }
}
