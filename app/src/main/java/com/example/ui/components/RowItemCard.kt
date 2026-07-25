package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RowStatus
import com.example.data.model.TaskRow
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.EmeraldSuccess
import org.json.JSONObject

@Composable
fun RowItemCard(
    row: TaskRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusEnum = try { RowStatus.valueOf(row.status) } catch (e: Exception) { RowStatus.PENDING }

    val (statusColor, statusText, statusIcon) = when (statusEnum) {
        RowStatus.SUCCESS -> Triple(EmeraldSuccess, "ناجح", Icons.Default.CheckCircle)
        RowStatus.FAILED -> Triple(CrimsonError, "فشل", Icons.Default.Error)
        RowStatus.PROCESSING -> Triple(MaterialTheme.colorScheme.primary, "جاري...", Icons.Default.Sync)
        RowStatus.PENDING -> Triple(AmberWarning, "انتظار", Icons.Default.HourglassTop)
    }

    // Extract snippet summary
    val extractedSummary = try {
        val jsonObj = JSONObject(row.extractedDataJson)
        val sb = StringBuilder()
        val keys = jsonObj.keys()
        var count = 0
        while (keys.hasNext() && count < 3) {
            val k = keys.next()
            val v = jsonObj.optString(k)
            if (v.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(" • ")
                sb.append("$k: $v")
                count++
            }
        }
        sb.toString()
    } catch (e: Exception) {
        ""
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Row Index Badge
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(
                    modifier = Modifier.size(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#${row.rowIndex}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Main Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "الهوية: ${row.idValue}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "(${row.yearValue})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (extractedSummary.isNotBlank()) {
                    Text(
                        text = extractedSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                } else if (row.errorMessage != null) {
                    Text(
                        text = row.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = CrimsonError,
                        maxLines = 1
                    )
                }

                if (row.executionDurationMs > 0) {
                    Text(
                        text = "مدة الاستجابة: ${row.executionDurationMs} مللي ثانية | HTTP ${row.httpStatusCode}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status Badge
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = statusColor.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = statusText,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }
        }
    }
}
