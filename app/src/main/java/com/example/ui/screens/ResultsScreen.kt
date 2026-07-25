package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RowStatus
import com.example.data.model.TaskRow
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.viewmodel.DataExtractorViewModel
import org.json.JSONObject

@Composable
fun ResultsScreen(
    viewModel: DataExtractorViewModel,
    modifier: Modifier = Modifier
) {
    val currentTask by viewModel.currentTask.collectAsState()
    val rows by viewModel.currentTaskRows.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, SUCCESS, FAILED
    var selectedRowForDetail by remember { mutableStateOf<TaskRow?>(null) }
    var showExportOptionsDialog by remember { mutableStateOf(false) }
    var exportOnlySuccessOption by remember { mutableStateOf(false) }

    val task = currentTask

    if (task == null || rows.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("لا توجد نتائج مستخرجة بعد. يرجى بدء الفحص أولاً.")
        }
        return
    }

    // Dynamic extra input field keys (e.g., student name in source file)
    val extraInputKeys = remember(rows) {
        val keysSet = mutableSetOf<String>()
        rows.forEach { row ->
            try {
                val json = JSONObject(row.extraInputJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != task.idColumnName && k != task.yearColumnName) {
                        keysSet.add(k)
                    }
                }
            } catch (ignored: Exception) {}
        }
        keysSet.toList()
    }

    // Dynamic extracted field keys
    val extractedKeys = remember(rows) {
        val keysSet = mutableSetOf<String>()
        rows.forEach { row ->
            try {
                val json = JSONObject(row.extractedDataJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    keysSet.add(keys.next())
                }
            } catch (ignored: Exception) {}
        }
        if (keysSet.isEmpty()) {
            task.extractionFieldsJson.split(",").forEach { keysSet.add(it.trim()) }
        }
        keysSet.toList()
    }

    val filteredRows = remember(rows, searchQuery, selectedFilter) {
        rows.filter { row ->
            val matchesSearch = searchQuery.isBlank() ||
                    row.idValue.contains(searchQuery) ||
                    row.extraInputJson.contains(searchQuery) ||
                    row.extractedDataJson.contains(searchQuery)

            val matchesFilter = when (selectedFilter) {
                "SUCCESS" -> row.status == RowStatus.SUCCESS.name
                "FAILED" -> row.status == RowStatus.FAILED.name
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

    val successCount = remember(rows) { rows.count { it.status == RowStatus.SUCCESS.name } }
    val failedCount = remember(rows) { rows.count { it.status == RowStatus.FAILED.name } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Export Action Header Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "جدول نتائج الطلاب والبيانات المستخرجة",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        val sourceBase = task.sourceFileName.substringBeforeLast(".")
                        Text(
                            text = "إجمالي: ${rows.size} طالب | ناجح: $successCount | فشل: $failedCount",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }

                    Button(
                        onClick = { showExportOptionsDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = "تصدير")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تصدير Excel (.xlsx)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Search & Filters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("بحث باسم الطالب أو الهوية...") },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            FilterChip(
                selected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" },
                label = { Text("الكل (${rows.size})") }
            )
            FilterChip(
                selected = selectedFilter == "SUCCESS",
                onClick = { selectedFilter = "SUCCESS" },
                label = { Text("ناجح ($successCount)") }
            )
            FilterChip(
                selected = selectedFilter == "FAILED",
                onClick = { selectedFilter = "FAILED" },
                label = { Text("فشل ($failedCount)") }
            )
        }

        // Horizontal Scrollable Data Table Grid
        val horizontalScrollState = rememberScrollState()

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            ) {
                // Table Header Row
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TableCell("#", width = 45.dp, isHeader = true)
                        TableCell(task.idColumnName, width = 110.dp, isHeader = true)
                        TableCell(task.yearColumnName, width = 75.dp, isHeader = true)

                        // Input Extra Columns
                        extraInputKeys.forEach { key ->
                            TableCell("$key (مدخل)", width = 120.dp, isHeader = true)
                        }

                        TableCell("حالة الفحص", width = 85.dp, isHeader = true)

                        // Extracted Web Output Columns
                        extractedKeys.forEach { key ->
                            TableCell("$key (مخرج)", width = 135.dp, isHeader = true)
                        }

                        TableCell("التفاصيل", width = 70.dp, isHeader = true)
                    }
                }

                Divider()

                // Table Rows
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredRows, key = { it.id }) { row ->
                        val inputJson = remember(row.extraInputJson) {
                            try { JSONObject(row.extraInputJson) } catch (e: Exception) { JSONObject() }
                        }
                        val extractedJson = remember(row.extractedDataJson) {
                            try { JSONObject(row.extractedDataJson) } catch (e: Exception) { JSONObject() }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRowForDetail = row }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TableCell("#${row.rowIndex}", width = 45.dp)
                            TableCell(row.idValue, width = 110.dp, isBold = true)
                            TableCell(row.yearValue, width = 75.dp)

                            // Input Extra Columns Values
                            extraInputKeys.forEach { key ->
                                val valStr = inputJson.optString(key, "-")
                                TableCell(valStr, width = 120.dp)
                            }

                            // Status
                            TableCell(
                                text = if (row.status == RowStatus.SUCCESS.name) "ناجح" else "فشل",
                                width = 85.dp,
                                color = if (row.status == RowStatus.SUCCESS.name) EmeraldSuccess else MaterialTheme.colorScheme.error,
                                isBold = true
                            )

                            // Extracted Web Columns Values
                            extractedKeys.forEach { key ->
                                val valStr = extractedJson.optString(key, "-")
                                TableCell(valStr, width = 135.dp, isBold = valStr != "-")
                            }

                            Box(
                                modifier = Modifier.width(70.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = { selectedRowForDetail = row },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "التفاصيل",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    // Student Detail Dialog Modal
    selectedRowForDetail?.let { detailRow ->
        val inputJson = remember(detailRow.extraInputJson) {
            try { JSONObject(detailRow.extraInputJson) } catch (e: Exception) { JSONObject() }
        }
        val extractedJson = remember(detailRow.extractedDataJson) {
            try { JSONObject(detailRow.extractedDataJson) } catch (e: Exception) { JSONObject() }
        }

        AlertDialog(
            onDismissRequest = { selectedRowForDetail = null },
            title = {
                Text(
                    text = "تفاصيل الطالب - صف #${detailRow.rowIndex}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ID & Year
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("رقم الهوية: ${detailRow.idValue}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("سنة الميلاد: ${detailRow.yearValue}", fontSize = 12.sp)
                            Text("حالة الاستعلام: ${if (detailRow.status == RowStatus.SUCCESS.name) "ناجح" else "فشل"}", fontSize = 12.sp, color = if (detailRow.status == RowStatus.SUCCESS.name) EmeraldSuccess else MaterialTheme.colorScheme.error)
                        }
                    }

                    // Input Data
                    if (inputJson.length() > 0) {
                        Text("البيانات في الملف الأصلي:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        val keys = inputJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            Text("• $k: ${inputJson.optString(k)}", fontSize = 12.sp)
                        }
                    }

                    Divider()

                    // Extracted Web Data
                    Text("البيانات المستخرجة من الموقع:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    if (extractedJson.length() == 0) {
                        Text("لم يتم استخراج أي بيانات إضافية", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val keys = extractedJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            Text("• $k: ${extractedJson.optString(k)}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (!detailRow.errorMessage.isNull_or_blank()) {
                        Text("ملاحظات الخطأ: ${detailRow.errorMessage}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { selectedRowForDetail = null }) {
                    Text("إغلاق")
                }
            }
        )
    }

    // Export Options Dialog Modal
    if (showExportOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showExportOptionsDialog = false },
            title = {
                Text(
                    text = "خيارات تصدير ملف Excel (.xlsx)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "اختر البيانات التي ترغب في تصديرها لملف الاكسل:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        onClick = { exportOnlySuccessOption = false },
                        shape = RoundedCornerShape(12.dp),
                        color = if (!exportOnlySuccessOption) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = !exportOnlySuccessOption,
                                onClick = { exportOnlySuccessOption = false }
                            )
                            Column {
                                Text(
                                    text = "تصدير كافة البيانات (${rows.size} طالب)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "يتضمن كافة السجلات (ناجحة + فاشلة) مع تفاصيل الأعمدة والأخطاء.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = { exportOnlySuccessOption = true },
                        shape = RoundedCornerShape(12.dp),
                        color = if (exportOnlySuccessOption) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = exportOnlySuccessOption,
                                onClick = { exportOnlySuccessOption = true }
                            )
                            Column {
                                Text(
                                    text = "تصدير الحالات الناجحة فقط ($successCount طالب)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = EmeraldSuccess
                                )
                                Text(
                                    text = "يتضمن فقط الطلاب الذين تم جلب وتفريغ بياناتهم بنجاح من الموقع.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportOptionsDialog = false
                        viewModel.exportResults(onlySuccess = exportOnlySuccessOption)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess)
                ) {
                    Text("بدء التصدير الآن", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExportOptionsDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

private fun String?.isNull_or_blank(): Boolean {
    return this == null || this.isBlank()
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    isHeader: Boolean = false,
    isBold: Boolean = false,
    color: Color = Color.Unspecified
) {
    Box(
        modifier = Modifier.width(width),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            fontSize = if (isHeader) 12.sp else 12.sp,
            fontWeight = if (isHeader || isBold) FontWeight.Bold else FontWeight.Normal,
            color = if (color != Color.Unspecified) color else if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}
