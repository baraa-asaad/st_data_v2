package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import com.example.ui.components.RowDetailDialog
import com.example.ui.components.RowItemCard
import com.example.ui.components.StatsOverviewCard
import com.example.ui.theme.CrimsonError
import com.example.ui.viewmodel.DataExtractorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DataExtractorViewModel,
    modifier: Modifier = Modifier
) {
    val currentTask by viewModel.currentTask.collectAsState()
    val rows by viewModel.currentTaskRows.collectAsState()
    val isRunning by viewModel.isBatchRunning.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, SUCCESS, FAILED, PENDING
    var selectedRowForDetail by remember { mutableStateOf<TaskRow?>(null) }

    // Filtered Rows
    val filteredRows = remember(rows, searchQuery, selectedFilter) {
        rows.filter { row ->
            val matchesSearch = searchQuery.isBlank() ||
                    row.idValue.contains(searchQuery) ||
                    row.yearValue.contains(searchQuery) ||
                    row.extractedDataJson.contains(searchQuery)

            val matchesFilter = when (selectedFilter) {
                "SUCCESS" -> row.status == RowStatus.SUCCESS.name
                "FAILED" -> row.status == RowStatus.FAILED.name
                "PENDING" -> row.status == RowStatus.PENDING.name || row.status == RowStatus.PROCESSING.name
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
        val task = currentTask
        if (task == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("لا توجد مهمة فحص قائمة. يرجى استيراد ملف وإنشاء مهمة جديدة من تبويب الاستيراد.")
            }
        } else {
            // Stats Overview Header Card
            StatsOverviewCard(task = task, isRunning = isRunning)

            // Execution Controls Bar
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRunning) {
                        Button(
                            onClick = { viewModel.pauseBatch() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("إيقاف مؤقت")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startOrResumeBatch() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("بدء / استئناف")
                        }
                    }

                    // Retry Failed Rows Button
                    if (task.failedCount > 0) {
                        Button(
                            onClick = { viewModel.retryFailedRows() },
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonError),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("إعادة المحاولة للفاصلة (${task.failedCount})")
                        }
                    }
                }
            }

            // Search & Filter Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("بحث برقم الهوية أو النتائج...") },
                    leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // Status Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("الكل (${rows.size})") }
                )
                FilterChip(
                    selected = selectedFilter == "SUCCESS",
                    onClick = { selectedFilter = "SUCCESS" },
                    label = { Text("الناجحة (${task.successCount})") }
                )
                FilterChip(
                    selected = selectedFilter == "FAILED",
                    onClick = { selectedFilter = "FAILED" },
                    label = { Text("الفاشلة (${task.failedCount})") }
                )
                FilterChip(
                    selected = selectedFilter == "PENDING",
                    onClick = { selectedFilter = "PENDING" },
                    label = { Text("الانتظار (${(task.totalRows - task.successCount - task.failedCount).coerceAtLeast(0)})") }
                )
            }

            // Rows Live Feed List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredRows, key = { it.id }) { row ->
                    RowItemCard(
                        row = row,
                        onClick = { selectedRowForDetail = row }
                    )
                }
            }
        }
    }

    // Modal Dialog for details
    selectedRowForDetail?.let { row ->
        RowDetailDialog(
            row = row,
            onDismiss = { selectedRowForDetail = null }
        )
    }
}
