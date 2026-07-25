package com.example.data.repository

import com.example.data.local.TaskDao
import com.example.data.model.ExtractionTask
import com.example.data.model.RowStatus
import com.example.data.model.TaskRow
import com.example.network.BatchHttpEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class TaskRepository(private val taskDao: TaskDao) {

    val allTasks: Flow<List<ExtractionTask>> = taskDao.getAllTasks()

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
            val idVal = rowMap[task.idColumnName] ?: rowMap.values.firstOrNull() ?: ""
            val yearVal = rowMap[task.yearColumnName] ?: rowMap.values.elementAtOrNull(1) ?: ""
            
            val extraJson = JSONObject(rowMap).toString()
            TaskRow(
                taskId = taskId,
                rowIndex = index + 1,
                idValue = idVal.trim(),
                yearValue = yearVal.trim(),
                extraInputJson = extraJson,
                status = RowStatus.PENDING.name
            )
        }
        taskDao.insertTaskRows(taskRows)
        val updatedTask = task.copy(id = taskId, totalRows = taskRows.size)
        taskDao.updateTask(updatedTask)
        return taskId
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
