package com.example.data.repository

import com.example.data.local.PresetDao
import com.example.data.local.TaskDao
import com.example.data.model.ExtractionTask
import com.example.data.model.RowStatus
import com.example.data.model.SavedPreset
import com.example.data.model.TaskRow
import com.example.network.BatchHttpEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class TaskRepository(
    private val taskDao: TaskDao,
    private val presetDao: PresetDao? = null
) {

    val allTasks: Flow<List<ExtractionTask>> = taskDao.getAllTasks()
    val allPresets: Flow<List<SavedPreset>> = presetDao?.getAllPresets() ?: flowOf(emptyList())

    suspend fun savePreset(preset: SavedPreset): Long {
        return presetDao?.insertPreset(preset) ?: -1L
    }

    suspend fun deletePreset(id: Long) {
        presetDao?.deletePresetById(id)
    }

    suspend fun seedDefaultPresetsIfEmpty() {
        if (presetDao == null) return
        if (presetDao.getPresetCount() == 0) {
            presetDao.insertPreset(
                SavedPreset(
                    presetName = "🎓 قالب أونروا (EMIS)",
                    targetUrl = "https://emis.unrwa.org/Result/StudentsResult",
                    requestMethod = "POST",
                    idParamKey = "IdNumber",
                    yearParamKey = "BirthYear",
                    extractionFieldsCsv = "اسم الطالب,النتيجة,المعدل,الصف,اسم المدرسة"
                )
            )
            presetDao.insertPreset(
                SavedPreset(
                    presetName = "🏫 قالب استعلام نتائج الشهادات العامة",
                    targetUrl = "https://example.com/api/results",
                    requestMethod = "POST",
                    idParamKey = "id_number",
                    yearParamKey = "birth_year",
                    extractionFieldsCsv = "اسم الطالب,المعدل,النتيجة,المدرسة,رقم الجلوس"
                )
            )
        }
    }

    private val _isBatchRunning = MutableStateFlow(false)
    val isBatchRunning: StateFlow<Boolean> = _isBatchRunning.asStateFlow()

    private val _currentActiveTaskId = MutableStateFlow<Long?>(null)
    val currentActiveTaskId: StateFlow<Long?> = _currentActiveTaskId.asStateFlow()

    private var batchJob: Job? = null
    private val isPaused = AtomicBoolean(false)

    suspend fun createNewTaskWithRows(
        task: ExtractionTask,
        rowsData: List<Map<String, String>>
    ): Long {
        val taskId = taskDao.insertTask(task)
        val taskRows = rowsData.mapIndexed { index, rowMap ->
            val idVal = extractColumnValue(rowMap, task.idColumnName, 0)
            val yearVal = extractColumnValue(rowMap, task.yearColumnName, 1)
            
            val cleanRowMap = rowMap.mapKeys { com.example.util.CsvExcelUtils.fixArabicMojibake(it.key).trim() }
                .mapValues { com.example.util.CsvExcelUtils.fixArabicMojibake(it.value).trim() }
            val extraJson = JSONObject(cleanRowMap).toString()

            TaskRow(
                taskId = taskId,
                rowIndex = index + 1,
                idValue = idVal,
                yearValue = yearVal,
                extraInputJson = extraJson,
                status = RowStatus.PENDING.name
            )
        }
        taskDao.insertTaskRows(taskRows)
        val updatedTask = task.copy(id = taskId, totalRows = taskRows.size)
        taskDao.updateTask(updatedTask)
        return taskId
    }

    private fun extractColumnValue(rowMap: Map<String, String>, targetColName: String, fallbackIdx: Int): String {
        val cleanTarget = com.example.util.CsvExcelUtils.fixArabicMojibake(targetColName).trim()
        
        // 1. Exact / normalized match
        for ((k, v) in rowMap) {
            val cleanK = com.example.util.CsvExcelUtils.fixArabicMojibake(k).trim()
            if (cleanK.equals(cleanTarget, ignoreCase = true)) {
                return com.example.util.CsvExcelUtils.fixArabicMojibake(v).trim()
            }
        }

        // 2. Contains match
        for ((k, v) in rowMap) {
            val cleanK = com.example.util.CsvExcelUtils.fixArabicMojibake(k).trim()
            if (cleanK.contains(cleanTarget, ignoreCase = true) || cleanTarget.contains(cleanK, ignoreCase = true)) {
                return com.example.util.CsvExcelUtils.fixArabicMojibake(v).trim()
            }
        }

        // 3. Fallback by index
        val values = rowMap.values.toList()
        val rawFallback = values.getOrNull(fallbackIdx) ?: values.firstOrNull() ?: ""
        return com.example.util.CsvExcelUtils.fixArabicMojibake(rawFallback).trim()
    }

    fun getTaskRowsFlow(taskId: Long): Flow<List<TaskRow>> {
        return taskDao.getRowsForTask(taskId)
    }

    suspend fun getTaskById(taskId: Long): ExtractionTask? {
        return taskDao.getTaskById(taskId)
    }

    suspend fun resetAndRetryFailedRows(taskId: Long) {
        taskDao.resetFailedRowsToPending(taskId)
        updateTaskCounts(taskId)
    }

    fun startOrResumeBatch(taskId: Long, scope: CoroutineScope) {
        if (_isBatchRunning.value && _currentActiveTaskId.value == taskId && !isPaused.get()) {
            return
        }

        isPaused.set(false)
        _isBatchRunning.value = true
        _currentActiveTaskId.value = taskId

        batchJob?.cancel()
        batchJob = scope.launch(Dispatchers.IO) {
            runBatchExecutionLoop(taskId)
        }
    }

    fun pauseBatch() {
        isPaused.set(true)
        _isBatchRunning.value = false
    }

    fun stopBatch() {
        isPaused.set(true)
        batchJob?.cancel()
        batchJob = null
        _isBatchRunning.value = false
    }

    private suspend fun runBatchExecutionLoop(taskId: Long) {
        val task = taskDao.getTaskById(taskId) ?: run {
            _isBatchRunning.value = false
            return
        }

        val pendingRows = taskDao.getPendingRows(taskId)
        if (pendingRows.isEmpty()) {
            _isBatchRunning.value = false
            return
        }

        val semaphore = Semaphore(task.concurrency.coerceIn(1, 10))

        coroutineScope {
            for (row in pendingRows) {
                if (!coroutineContext.isActive || isPaused.get()) break

                semaphore.acquire()
                launch {
                    try {
                        if (!isPaused.get() && coroutineContext.isActive) {
                            // Update status to processing
                            taskDao.updateTaskRow(row.copy(status = RowStatus.PROCESSING.name))

                            // Execute HTTP Request
                            val result = BatchHttpEngine.executeRequest(task, row)

                            val extractedJsonStr = JSONObject(result.extractedDataMap).toString()
                            val updatedRow = row.copy(
                                status = if (result.isSuccess) RowStatus.SUCCESS.name else RowStatus.FAILED.name,
                                httpStatusCode = result.statusCode,
                                extractedDataJson = extractedJsonStr,
                                rawResponseBody = result.rawBody,
                                errorMessage = result.errorMessage,
                                executionDurationMs = result.durationMs,
                                updatedAt = System.currentTimeMillis()
                            )
                            taskDao.updateTaskRow(updatedRow)
                            updateTaskCounts(taskId)

                            // Apply delay between requests if specified
                            if (task.delayMillis > 0) {
                                delay(task.delayMillis)
                            }
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }

        _isBatchRunning.value = false
    }

    private suspend fun updateTaskCounts(taskId: Long) {
        val task = taskDao.getTaskById(taskId) ?: return
        val pending = taskDao.getPendingRows(taskId).size
        val failed = taskDao.getFailedRows(taskId).size
        val success = task.totalRows - pending - failed
        
        taskDao.updateTask(
            task.copy(
                successCount = success.coerceAtLeast(0),
                failedCount = failed.coerceAtLeast(0)
            )
        )
    }

    suspend fun deleteTask(taskId: Long) {
        if (_currentActiveTaskId.value == taskId) {
            stopBatch()
        }
        taskDao.deleteRowsForTask(taskId)
        taskDao.deleteTaskById(taskId)
    }
}
