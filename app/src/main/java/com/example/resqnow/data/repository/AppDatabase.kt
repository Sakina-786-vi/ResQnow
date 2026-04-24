package com.example.resqnow.data.repository

import android.content.Context
import androidx.room.*
import com.example.resqnow.data.model.GridCell
import com.example.resqnow.data.model.TrackingSession
import com.example.resqnow.data.model.UserProfile
import com.example.resqnow.data.model.EmergencyContact
import kotlinx.coroutines.flow.Flow

@Dao
interface GridCellDao {
    @Query("SELECT * FROM grid_cells WHERE cellId = :cellId LIMIT 1")
    suspend fun getCell(cellId: String): GridCell?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCell(cell: GridCell)

    @Query("UPDATE grid_cells SET vehicleCount = :count, lastUpdated = :ts WHERE cellId = :cellId")
    suspend fun updateCount(cellId: String, count: Int, ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM grid_cells WHERE lastUpdated < :cutoff")
    suspend fun deleteExpired(cutoff: Long)

    @Query("SELECT * FROM grid_cells ORDER BY vehicleCount DESC LIMIT 10")
    fun getTopBusiestCells(): Flow<List<GridCell>>
}

@Dao
interface TrackingSessionDao {
    @Insert
    suspend fun insertSession(session: TrackingSession): Long

    @Query("UPDATE tracking_sessions SET endTime = :endTime, totalKmTraveled = :km WHERE id = :id")
    suspend fun closeSession(id: Long, endTime: Long, km: Double)

    @Query("SELECT * FROM tracking_sessions ORDER BY startTime DESC LIMIT 20")
    fun getRecentSessions(): Flow<List<TrackingSession>>
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles ORDER BY id DESC LIMIT 1")
    suspend fun getLatestUser(): UserProfile?

    @Query("SELECT * FROM user_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UserProfile?

    @Query("SELECT * FROM user_profiles WHERE phoneE164 = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserProfile): Long
}

@Dao
interface EmergencyContactDao {
    @Query("SELECT * FROM emergency_contacts WHERE userId = :userId ORDER BY slotIndex ASC")
    suspend fun getForUser(userId: Long): List<EmergencyContact>

    @Query("DELETE FROM emergency_contacts WHERE userId = :userId")
    suspend fun deleteForUser(userId: Long)

    @Insert
    suspend fun insertAll(contacts: List<EmergencyContact>)
}

@Database(
    entities = [GridCell::class, TrackingSession::class, UserProfile::class, EmergencyContact::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gridCellDao(): GridCellDao
    abstract fun trackingSessionDao(): TrackingSessionDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun emergencyContactDao(): EmergencyContactDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "highway_sos_db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
