package com.example.resqnow.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// ── GPS State (broadcast from GPSTrackingService to UI) ──────────────────

data class GpsState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val highwayName: String = "",
    val kmMarker: Int = 0,
    val gridCellId: String = "",
    val vehicleCount: Int = 0,
    val isRumorDebunked: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

// ── Room entity: 500m grid cell traffic count ────────────────────────────

@Entity(tableName = "grid_cells")
data class GridCell(
    @PrimaryKey val cellId: String,
    val highwayName: String,
    val kmMarker: Int,
    val vehicleCount: Int,
    val lastUpdated: Long = System.currentTimeMillis()
)

// ── Room entity: tracking session log ────────────────────────────────────

@Entity(tableName = "tracking_sessions")
data class TrackingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val totalKmTraveled: Double = 0.0,
    val emergenciesTriggered: Int = 0
)

// ── Emergency facility (hospital / police) ───────────────────────────────

data class EmergencyFacility(
    val name: String,
    val phone: String,
    val type: FacilityType,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double = 0.0
)

enum class FacilityType {
    HOSPITAL, POLICE, AMBULANCE, HIGHWAY_PATROL
}