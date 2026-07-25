package com.example.data.local

import androidx.room.*
import com.example.data.model.SavedPreset
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: SavedPreset): Long

    @Query("SELECT * FROM saved_presets ORDER BY createdAt DESC")
    fun getAllPresets(): Flow<List<SavedPreset>>

    @Query("SELECT COUNT(*) FROM saved_presets")
    suspend fun getPresetCount(): Int

    @Query("DELETE FROM saved_presets WHERE id = :id")
    suspend fun deletePresetById(id: Long)
}
