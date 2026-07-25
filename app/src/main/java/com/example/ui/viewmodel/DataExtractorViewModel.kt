package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.ExtractionTask
import com.example.data.model.RowStatus
import com.example.data.model.TaskRow
import com.example.data.repository.TaskRepository
import com.example.util.CsvExcelUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class DataExtractorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TaskRepository
    val allTasks: StateFlow<List<ExtractionTask>>

    val isBatchRunning: StateFlow<Boolean>
    val currentActiveTaskId: StateFlow<Long?>

    private val _selectedTaskId = MutableStateFlow<Long?>(null)
    val selectedTaskId: StateFlow<Long?> = _selectedTaskId.asStateFlow()

    private val _parsedFileData = MutableStateFlow<CsvExcelUtils.ParsedFileData?>(null)
    val parsedFileData: StateFlow<CsvExcelUtils.ParsedFileData?> = _parsedFileData.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _exportedFile = MutableStateFlow<File?>(null)
    val exportedFile: StateFlow<File?> = _exportedFile.asStateFlow()

    // Task Form Input States
    val taskNameInput = MutableStateFlow("فحص وتفقد نتائج الطلاب")
    val targetUrlInput = MutableStateFlow("https://emis.unrwa.org/Result/StudentsResult")
    val requestMethodInput = MutableStateFlow("POST")
    val idColumnInput = MutableStateFlow("رقم الهوية")
    val yearColumnInput = MutableStateFlow("سنة الميلاد")
    val idParamInput = MutableStateFlow("IdNumber")
    val yearParamInput = MutableStateFlow("BirthYear")
    val extractionFieldsInput = MutableStateFlow("اسم الطالب,النتيجة,المعدل,الصف,المدرسة")
    val delayMillisInput = MutableStateFlow(200L)
    val concurrencyInput = MutableStateFlow(3)

    init {
        val dao = AppDatabase.getDatabase(application).taskDao()
        repository = TaskRepository(dao)
        allTasks = repository.allTasks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        isBatchRunning = repository.isBatchRunning
        currentActiveTaskId = repository.currentActiveTaskId

        // Auto select first task if available
        viewModelScope.launch {
            allTasks.collect { tasks ->
                if (_selectedTaskId.value == null && tasks.isNotEmpty()) {
                    _selectedTaskId.value = tasks.first().id
                }
            }
        }
    }

    // Active Task details flow
    val currentTask: StateFlow<ExtractionTask?> = _selectedTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else allTasks.map { list -> list.find { it.id == id } }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Current Task Rows flow
    val currentTaskRows: StateFlow<List<TaskRow>> = _selectedTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.getTaskRowsFlow(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun updateConfigFromInspector(
        url: String,
        idParamKey: String,
        yearParamKey: String,
        extractionFieldsCsv: String,
        method: String = "POST"
    ) {
        targetUrlInput.value = url
        idParamInput.value = idParamKey
        yearParamInput.value = yearParamKey
        if (extractionFieldsCsv.isNotBlank()) {
            extractionFieldsInput.value = extractionFieldsCsv
        }
        requestMethodInput.value = method
        _userMessage.value = "تم اعتماد إعدادات الرابط والخانات المحددة بنجاح"
    }

    fun selectTask(taskId: Long) {
        _selectedTaskId.value = taskId
    }

    fun loadCsvFromUri(uri: Uri) {
        viewModelScope.launch {
            val parsed = CsvExcelUtils.parseCsvFromUri(getApplication(), uri)
            if (parsed != null && parsed.rows.isNotEmpty()) {
                _parsedFileData.value = parsed
                // Auto detect columns
                val headers = parsed.headers
                val idHeader = headers.find { it.contains("هوية") || it.contains("id", ignoreCase = true) } ?: headers.firstOrNull() ?: "رقم الهوية"
                val yearHeader = headers.find { it.contains("سنة") || it.contains("تاريخ") || it.contains("year", ignoreCase = true) } ?: headers.getOrNull(1) ?: "سنة الميلاد"
                
                idColumnInput.value = idHeader
                yearColumnInput.value = yearHeader
                taskNameInput.value = "فحص - ${parsed.fileName}"
                _userMessage.value = "تم استيراد ${parsed.rows.size} صف من الملف: ${parsed.fileName}"
            } else {
                _userMessage.value = "تعذر قراءة ملف CSV أو الملف فارغ"
            }
        }
    }

    fun loadSampleData() {
        val sample = CsvExcelUtils.generateSampleStudentCsv(getApplication())
        _parsedFileData.value = sample
        idColumnInput.value = "رقم الهوية"
        yearColumnInput.value = "سنة الميلاد"
        taskNameInput.value = "نموذج فحص تجريبي"
        _userMessage.value = "تم تحميل ${sample.rows.size} صفوف تجريبية بنجاح"
    }

    fun loadPastedRows(rows: List<Map<String, String>>) {
        val headers = listOf("رقم الهوية", "سنة الميلاد")
        _parsedFileData.value = CsvExcelUtils.ParsedFileData(
            headers = headers,
            rows = rows,
            fileName = "نص ملصق (${rows.size} طالب)"
        )
        idColumnInput.value = "رقم الهوية"
        yearColumnInput.value = "سنة الميلاد"
        taskNameInput.value = "فحص - نص ملصق (${rows.size} طالب)"
        _userMessage.value = "تم استيراد ${rows.size} طالب بنجاح من النص الملصق"
    }

    fun createAndStartTask() {
        val data = _parsedFileData.value
        if (data == null || data.rows.isEmpty()) {
            _userMessage.value = "يرجى اختيار أو استيراد ملف يحتوي على بيانات أولاً"
            return
        }

        viewModelScope.launch {
            val newTask = ExtractionTask(
                taskName = taskNameInput.value.ifBlank { "مهمة استخراج جديدة" },
                sourceFileName = data.fileName,
                targetUrl = targetUrlInput.value.ifBlank { "https://emis.unrwa.org/Result/StudentsResult" },
                requestMethod = requestMethodInput.value,
                idColumnName = idColumnInput.value,
                yearColumnName = yearColumnInput.value,
                idParamKey = idParamInput.value.ifBlank { "IdNumber" },
                yearParamKey = yearParamInput.value.ifBlank { "BirthYear" },
                extractionFieldsJson = extractionFieldsInput.value.ifBlank { "اسم الطالب,النتيجة,المعدل,الصف,المدرسة" },
                delayMillis = delayMillisInput.value,
                concurrency = concurrencyInput.value,
                totalRows = data.rows.size
            )

            val taskId = repository.createNewTaskWithRows(newTask, data.rows)
            _selectedTaskId.value = taskId
            _userMessage.value = "تم إنشاء المهمة بنجاح، جاري بدء الفحص والاستعلام..."
            
            repository.startOrResumeBatch(taskId, viewModelScope)
        }
    }

    fun startOrResumeBatch() {
        val id = _selectedTaskId.value ?: return
        repository.startOrResumeBatch(id, viewModelScope)
        _userMessage.value = "تم تشغيل عمليات الاستعلام"
    }

    fun pauseBatch() {
        repository.pauseBatch()
        _userMessage.value = "تم إيقاف الاستعلام مؤقتاً"
    }

    fun retryFailedRows() {
        val id = _selectedTaskId.value ?: return
        viewModelScope.launch {
            repository.resetAndRetryFailedRows(id)
            repository.startOrResumeBatch(id, viewModelScope)
            _userMessage.value = "تمت إعادة تعيين الحالات الفاشلة وجاري إعادة المحاولة..."
        }
    }

    fun exportResults() {
        val task = currentTask.value ?: return
        val rows = currentTaskRows.value
        if (rows.isEmpty()) {
            _userMessage.value = "لا توجد نتائج للتصدير"
            return
        }

        viewModelScope.launch {
            val exported = CsvExcelUtils.exportTaskResultsToCsv(getApplication(), task, rows)
            if (exported != null) {
                _exportedFile.value = exported
                _userMessage.value = "تم تصدير النتائج بنجاح إلى: ${exported.name}"
            } else {
                _userMessage.value = "فشل تصدير ملف النتائج"
            }
        }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
            if (_selectedTaskId.value == taskId) {
                _selectedTaskId.value = allTasks.value.find { it.id != taskId }?.id
            }
            _userMessage.value = "تم حذف المهمة"
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }
}
