package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.TaskRow
import org.json.JSONObject

@Composable
fun RowDetailDialog(
    row: TaskRow,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "تفاصيل السجل #${row.rowIndex}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("إغلاق")
                    }
                }

                Divider()

                // Primary Params
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "البيانات الأساسية", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(text = "رقم الهوية: ${row.idValue}", fontSize = 13.sp)
                    Text(text = "سنة الميلاد: ${row.yearValue}", fontSize = 13.sp)
                    Text(text = "رمز الاستجابة: HTTP ${row.httpStatusCode}", fontSize = 13.sp)
                    Text(text = "مدة التنفيذ: ${row.executionDurationMs} ms", fontSize = 13.sp)
                    if (row.errorMessage != null) {
                        Text(text = "الخطأ: ${row.errorMessage}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                Divider()

                // Extracted Response Fields
                Text(text = "البيانات المستخرجة", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                val extractedMap = try {
                    val json = JSONObject(row.extractedDataJson)
                    val map = mutableMapOf<String, String>()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = json.optString(k)
                    }
                    map
                } catch (e: Exception) {
                    emptyMap()
                }

                if (extractedMap.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        extractedMap.forEach { (key, valStr) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "$key:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(text = valStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    Text(text = "لا توجد بيانات مستخرجة لهذا السجل", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Divider()

                // Raw Response Body Preview
                Text(text = "معاينة استجابة الخادم (Raw Body)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (row.rawResponseBody.isNotBlank()) row.rawResponseBody else "لا توجد استجابة نصية",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}
