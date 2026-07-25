package com.example.data.local

import androidx.room.*
import com.example.data.model.ExtractionTask
import com.example.data.model.TaskRow
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ExtractionTask): Long

    @Update
    suspend fun updateTask(task: ExtractionTask)

    @Query("SELECT * FROM extraction_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<ExtractionTask>>

    @Query("SELECT * FROM extraction_tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: Long): ExtractionTask?

    @Query("DELETE FROM extraction_tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskRows(rows: List<TaskRow>)

    @Update
    suspend fun updateTaskRow(row: TaskRow)

    @Query("SELECT * FROM task_rows WHERE taskId = :taskId ORDER BY rowIndex ASC")
    fun getRowsForTask(taskId: Long): Flow<List<TaskRow>>

    @Query("SELECT * FROM task_rows WHERE taskId = :taskId AND status = :status ORDER BY rowIndex ASC")
    fun getRowsForTaskByStatus(taskId: Long, status: String): Flow<List<TaskRow>>

    @Query("SELECT * FROM task_rows WHERE taskId = :taskId AND status = 'PENDING' ORDER BY rowIndex ASC")
    suspend fun getPendingRows(taskId: Long): List<TaskRow>

    @Query("SELECT * FROM task_rows WHERE taskId = :taskId AND status = 'FAILED' ORDER BY rowIndex ASC")
    suspend fun getFailedRows(taskId: Long): List<TaskRow>

    @Query("UPDATE task_rows SET status = 'PENDING', errorMessage = NULL, httpStatusCode = 0 WHERE taskId = :taskId AND status = 'FAILED'")
    suspend fun resetFailedRowsToPending(taskId: Long)

    @Query("DELETE FROM task_rows WHERE taskId = :taskId")
    suspend fun deleteRowsForTask(taskId: Long)
}
