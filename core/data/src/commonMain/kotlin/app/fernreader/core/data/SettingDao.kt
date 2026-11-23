package app.fernreader.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SettingDao {
    @Upsert suspend fun upsert(row: SettingEntity)
    @Query("SELECT * FROM setting WHERE `key` = :key AND deleted = 0") suspend fun get(key: String): SettingEntity?
}
