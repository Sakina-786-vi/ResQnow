package com.example.resqnow.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_profiles",
    indices = [Index(value = ["phoneE164"], unique = true)]
)
data class UserProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fullName: String,
    val phoneE164: String,
    val isVerified: Boolean = false,
    val verifiedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "emergency_contacts",
    foreignKeys = [
        ForeignKey(
            entity = UserProfile::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["userId"])]
)
data class EmergencyContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val slotIndex: Int,
    val contactName: String,
    val phone: String,
    val createdAt: Long = System.currentTimeMillis()
)
