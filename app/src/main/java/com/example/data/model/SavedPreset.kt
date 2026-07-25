package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_presets")
data class SavedPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val presetName: String,
    val targetUrl: String,
    val requestMethod: String = "POST",
    val idParamKey: String = "IdNumber",
    val yearParamKey: String = "BirthYear",
    val extractionFieldsCsv: String = "اسم الطالب,النتيجة,المعدل,الصف,المدرسة",
    val createdAt: Long = System.currentTimeMillis()
)
