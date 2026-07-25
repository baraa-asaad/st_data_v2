package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "extraction_tasks")
data class ExtractionTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskName: String,
    val sourceFileName: String,
    val targetUrl: String,
    val requestMethod: String = "POST", // POST or GET
    val idColumnName: String = "رقم الهوية",
    val yearColumnName: String = "سنة الميلاد",
    val idParamKey: String = "IdNumber",
    val yearParamKey: String = "BirthYear",
    val customHeadersJson: String = "{}",
    val extractionFieldsJson: String = "اسم الطالب,النتيجة,المعدل,الصف,المدرسة",
    val delayMillis: Long = 200,
    val concurrency: Int = 3,
    val totalRows: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

enum class RowStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED
}

@Entity(tableName = "task_rows")
data class TaskRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val rowIndex: Int,
    val idValue: String,
    val yearValue: String,
    val extraInputJson: String = "{}",
    val status: String = RowStatus.PENDING.name,
    val httpStatusCode: Int = 0,
    val extractedDataJson: String = "{}",
    val rawResponseBody: String = "",
    val errorMessage: String? = null,
    val executionDurationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
