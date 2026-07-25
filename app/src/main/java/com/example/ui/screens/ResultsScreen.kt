package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
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
                    row.extractedDataJson.contains(searchQuery)

            val matchesFilter = when (selectedFilter) {
                "SUCCESS" -> row.status == RowStatus.SUCCESS.name
                "FAILED" -> row.status == RowStatus.FAILED.name
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

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
                            text = "جدول النتائج المجمعة",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        val sourceBase = task.sourceFileName.substringBeforeLast(".")
                        Text(
                            text = "اسم الملف عند التصدير: ${sourceBase}_نتائج.csv",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }

                    Button(
                        onClick = { viewModel.exportResults() },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = "تصدير")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تصدير Excel / CSV", fontWeight = FontWeight.Bold)
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
                placeholder = { Text("بحث بالنتائج...") },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            FilterChip(
                selected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" },
                label = { Text("الكل") }
            )
            FilterChip(
                selected = selectedFilter == "SUCCESS",
                onClick = { selectedFilter = "SUCCESS" },
                label = { Text("ناجحة فقط") }
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
                        TableCell("#", width = 50.dp, isHeader = true)
                        TableCell(task.idColumnName, width = 110.dp, isHeader = true)
                        TableCell(task.yearColumnName, width = 80.dp, isHeader = true)
                        TableCell("الحالة", width = 80.dp, isHeader = true)
                        
                        extractedKeys.forEach { key ->
                            TableCell(key, width = 130.dp, isHeader = true)
                        }
                    }
                }

                Divider()

                // Table Rows
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredRows, key = { it.id }) { row ->
                        val rowJson = remember(row.extractedDataJson) {
                            try { JSONObject(row.extractedDataJson) } catch (e: Exception) { JSONObject() }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TableCell("#${row.rowIndex}", width = 50.dp)
                            TableCell(row.idValue, width = 110.dp, isBold = true)
                            TableCell(row.yearValue, width = 80.dp)
                            TableCell(
                                text = if (row.status == RowStatus.SUCCESS.name) "ناجح" else "فشل",
                                width = 80.dp,
                                color = if (row.status == RowStatus.SUCCESS.name) EmeraldSuccess else MaterialTheme.colorScheme.error
                            )

                            extractedKeys.forEach { key ->
                                val valStr = rowJson.optString(key, "-")
                                TableCell(valStr, width = 130.dp)
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
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
            fontSize = if (isHeader) 13.sp else 12.sp,
            fontWeight = if (isHeader || isBold) FontWeight.Bold else FontWeight.Normal,
            color = if (color != Color.Unspecified) color else if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}
