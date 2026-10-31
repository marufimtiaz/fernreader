package app.epubreader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user setting. Synced row (F-115): stable id, change timestamp, tombstone. Value is JSON. */
@Entity(tableName = "setting", indices = [Index(value = ["key"], unique = true)])
data class SettingEntity(
    @PrimaryKey val id: String,
    val key: String,
    val json: String,
    val updatedAt: Long,
    val deleted: Boolean,
)
