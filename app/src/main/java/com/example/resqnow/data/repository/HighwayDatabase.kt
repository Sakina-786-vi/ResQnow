package com.example.resqnow.data.repository

import com.example.resqnow.data.model.EmergencyFacility
import com.example.resqnow.data.model.FacilityType
import kotlin.math.*

object HighwayDatabase {

    const val GRID_SIZE_DEG = 0.0045
    const val RUMOR_DEBUNK_THRESHOLD = 3

    data class HighwayCorridor(
        val name: String, val shortName: String,
        val minLat: Double, val maxLat: Double,
        val minLon: Double, val maxLon: Double,
        val startLat: Double, val startLon: Double,
        val endLat: Double, val endLon: Double,
        val totalKm: Int
    )

    val HIGHWAYS = listOf(
        HighwayCorridor("National Highway 44","NH44",8.0,34.5,74.5,80.0,34.08,74.79,8.08,77.54,3745),
        HighwayCorridor("National Highway 48","NH48",12.5,28.7,76.5,80.2,28.61,77.20,13.08,80.27,2807),
        HighwayCorridor("National Highway 19","NH19",22.5,28.6,77.2,88.4,28.61,77.20,22.57,88.36,1453),
        HighwayCorridor("National Highway 8","NH8",18.9,28.7,72.8,77.2,28.61,77.20,18.96,72.82,1428),
        HighwayCorridor("Mumbai-Pune Expressway","MPEX",18.50,19.10,73.75,74.00,18.96,72.82,18.52,73.85,94),
        HighwayCorridor("Yamuna Expressway","YEX",27.10,28.70,77.30,78.10,28.61,77.20,27.18,78.01,165)
    )

    fun identifyHighway(lat: Double, lon: Double): Pair<String, Int> {
        val candidates = HIGHWAYS.filter {
            lat in it.minLat..it.maxLat && lon in it.minLon..it.maxLon
        }
        if (candidates.isEmpty()) return Pair("Local Road", 0)
        val best = candidates.minByOrNull {
            (it.maxLat - it.minLat) * (it.maxLon - it.minLon)
        } ?: candidates.first()
        val totalHighwayKm = haversineKm(best.startLat, best.startLon, best.endLat, best.endLon)
        val distFromStart  = haversineKm(lat, lon, best.startLat, best.startLon)
        val fraction       = (distFromStart / totalHighwayKm.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        return Pair(best.shortName, (fraction * best.totalKm).toInt())
    }

    fun getGridCellId(lat: Double, lon: Double): String {
        return "${(lat / GRID_SIZE_DEG).toInt()}_${(lon / GRID_SIZE_DEG).toInt()}"
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R    = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat/2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private val FACILITIES = listOf(
        EmergencyFacility("AIIMS Trauma Centre","01126588500",FacilityType.HOSPITAL,28.5672,77.2100),
        EmergencyFacility("Safdarjung Hospital","01126730000",FacilityType.HOSPITAL,28.5693,77.2072),
        EmergencyFacility("KEM Hospital","022-24107000",FacilityType.HOSPITAL,18.9936,72.8405),
        EmergencyFacility("Sassoon Hospital","020-26128000",FacilityType.HOSPITAL,18.5204,73.8567),
        EmergencyFacility("Victoria Hospital","080-22971231",FacilityType.HOSPITAL,12.9621,77.5738),
        EmergencyFacility("Govt General Hospital","044-25305000",FacilityType.HOSPITAL,13.0827,80.2707),
        EmergencyFacility("Osmania Hospital","040-24600000",FacilityType.HOSPITAL,17.3850,78.4867),
        EmergencyFacility("SSKM Hospital","033-22044101",FacilityType.HOSPITAL,22.5406,88.3408),
        EmergencyFacility("Delhi Traffic Police","011-25844444",FacilityType.POLICE,28.6139,77.2090),
        EmergencyFacility("Mumbai Highway Police","022-22694488",FacilityType.HIGHWAY_PATROL,19.0760,72.8777),
        EmergencyFacility("Bengaluru Traffic Police","080-22943344",FacilityType.POLICE,12.9716,77.5946)
    )

    fun getNearestFacilities(lat: Double, lon: Double): List<EmergencyFacility> =
        FACILITIES.map { it.copy(distanceKm = haversineKm(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.distanceKm }.take(5)

    fun getEmergencyPhones(lat: Double, lon: Double): List<String> {
        val phones = mutableListOf("112")
        getNearestFacilities(lat, lon)
            .filter { it.type == FacilityType.HOSPITAL }.take(2)
            .forEach { phones.add(it.phone) }
        phones.add("1033")
        return phones.distinct()
    }
}