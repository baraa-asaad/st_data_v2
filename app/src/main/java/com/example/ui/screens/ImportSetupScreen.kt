package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.WebInspectorDialog
import com.example.ui.viewmodel.DataExtractorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSetupScreen(
    viewModel: DataExtractorViewModel,
    onStartBatchClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val parsedData by viewModel.parsedFileData.collectAsState()

    val taskName by viewModel.taskNameInput.collectAsState()
    val targetUrl by viewModel.targetUrlInput.collectAsState()
    val requestMethod by viewModel.requestMethodInput.collectAsState()
    val idColumn by viewModel.idColumnInput.collectAsState()
    val yearColumn by viewModel.yearColumnInput.collectAsState()
    val idParam by viewModel.idParamInput.collectAsState()
    val yearParam by viewModel.yearParamInput.collectAsState()
    val extractionFields by viewModel.extractionFieldsInput.collectAsState()
    val delayMillis by viewModel.delayMillisInput.collectAsState()
    val concurrency by viewModel.concurrencyInput.collectAsState()

    val allPresets by viewModel.allPresets.collectAsState()
    var showInspectorDialog by remember { mutableStateOf(false) }
    var showPasteTextDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetNameInput by remember { mutableStateOf("") }
    var pastedRawText by remember { mutableStateOf("") }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadCsvFromUri(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: File Import Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = "استيراد",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "1. استيراد بيانات الطلاب (Excel / CSV / نص)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("اختر ملف Excel/CSV", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { showPasteTextDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("لصق نص مباشر", fontSize = 12.sp)
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.loadSampleData() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("استخدام قائمة تجريبية جاهزة (5 طلاب)")
                }

                parsedData?.let { data ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "تم تحميل المصدر: ${data.fileName}",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "عدد الطلاب: ${data.rows.size} طالب | الأعمدة: ${data.headers.size} أعمدة (${data.headers.joinToString(", ")})",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )

                            // Live Table Preview (First 3 rows)
                            if (data.rows.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // Header Row
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            data.headers.forEach { h ->
                                                Text(
                                                    text = h,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.widthIn(min = 90.dp)
                                                )
                                            }
                                        }
                                        Divider()
                                        // Sample Rows (Up to 3)
                                        data.rows.take(3).forEach { rowMap ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                data.headers.forEach { h ->
                                                    Text(
                                                        text = rowMap[h] ?: "-",
                                                        fontSize = 11.sp,
                                                        modifier = Modifier.widthIn(min = 90.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Endpoint & Target Settings Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "الإعدادات",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "2. إعدادات موقع الاستعلام والخانات",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    ElevatedButton(
                        onClick = {
                            newPresetNameInput = taskName.ifBlank { "قالب إعدادات جديد" }
                            showSavePresetDialog = true
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("حفظ كقالب جديد", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Saved Presets Row
                if (allPresets.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "القوالب المحفوظة (المدخلات والمخرجات):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                allPresets.forEach { preset ->
                                    InputChip(
                                        selected = targetUrl == preset.targetUrl && idParam == preset.idParamKey,
                                        onClick = { viewModel.applyPreset(preset) },
                                        label = { Text(preset.presetName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                                        trailingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "حذف القالب",
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clickable { viewModel.deletePreset(preset.id) },
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = taskName,
                    onValueChange = { viewModel.taskNameInput.value = it },
                    label = { Text("اسم عملية الفحص") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = targetUrl,
                    onValueChange = { viewModel.targetUrlInput.value = it },
                    label = { Text("رابط موقع الاستعلام (Target URL)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // Interactive Web Inspector Button
                Button(
                    onClick = { showInspectorDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Web, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("معاينة وتحديد الخانات بالنقر المباشر", fontWeight = FontWeight.Bold)
                }

                // Request Method Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "نوع الطلب:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    FilterChip(
                        selected = requestMethod == "POST",
                        onClick = { viewModel.requestMethodInput.value = "POST" },
                        label = { Text("POST (نموذج/Form)") }
                    )
                    FilterChip(
                        selected = requestMethod == "GET",
                        onClick = { viewModel.requestMethodInput.value = "GET" },
                        label = { Text("GET (رابط مباشر)") }
                    )
                }

                Divider()

                // Column & Parameter Mappings
                Text(text = "ربط أعمدة البيانات مع معاملات الموقع:", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                val headers = parsedData?.headers ?: listOf("رقم الهوية", "سنة الميلاد")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "عمود الهوية في الملف", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DropdownSelector(
                            options = headers,
                            selectedOption = idColumn,
                            onOptionSelected = { viewModel.idColumnInput.value = it }
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "اسم معامل الهوية بالموقع", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = idParam,
                            onValueChange = { viewModel.idParamInput.value = it },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "عمود سنة الميلاد بالملف", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DropdownSelector(
                            options = headers,
                            selectedOption = yearColumn,
                            onOptionSelected = { viewModel.yearColumnInput.value = it }
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "اسم معامل السنة بالموقع", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = yearParam,
                            onValueChange = { viewModel.yearParamInput.value = it },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = extractionFields,
                    onValueChange = { viewModel.extractionFieldsInput.value = it },
                    label = { Text("عناوين البيانات المطلوب استخراجها") },
                    supportingText = { Text("مثال: اسم الطالب,النتيجة,المعدل,الصف,المدرسة") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Performance Controls
                Divider()
                Text(text = "سرعة الاستعلام وضبط الأداء:", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Column {
                    Text(text = "التأخير بين كل طلب وآخر: $delayMillis ms", fontSize = 12.sp)
                    Slider(
                        value = delayMillis.toFloat(),
                        onValueChange = { viewModel.delayMillisInput.value = it.toLong() },
                        valueRange = 0f..2000f,
                        steps = 19
                    )
                }

                Column {
                    Text(text = "الطلبات المتوازية بنفس الوقت: $concurrency طلبات", fontSize = 12.sp)
                    Slider(
                        value = concurrency.toFloat(),
                        onValueChange = { viewModel.concurrencyInput.value = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                }
            }
        }

        // Action Button: Start Batch
        Button(
            onClick = {
                viewModel.createAndStartTask()
                onStartBatchClicked()
            },
            enabled = parsedData != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "بدء الفحص واستخراج البيانات الآن", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }

    // Direct Raw Text Paste Dialog
    if (showPasteTextDialog) {
        AlertDialog(
            onDismissRequest = { showPasteTextDialog = false },
            title = { Text("لصق بيانات الطلاب مباشرة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "انسخ الأعمدة مباشرة من Excel والصقها هنا (رقم الهوية وسنة الميلاد مفصولة بمسافة أو فاصلة):",
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = pastedRawText,
                        onValueChange = { pastedRawText = it },
                        placeholder = { Text("مثال:\n900123456, 2006\n900654321, 2007") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pastedRawText.isNotBlank()) {
                            viewModel.loadPastedText(pastedRawText)
                            showPasteTextDialog = false
                        }
                    }
                ) {
                    Text("اعتماد النص")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteTextDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Save Preset Modal Dialog
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = {
                Text("حفظ المدخلات والمخرجات كقالب جديد", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "قم بتسمية هذا القالب لتتمكن من إعادة استخدامه لاحقاً بنقرة واحدة (يتضمن الرابط ورابط الخانات والمخرجات):",
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = newPresetNameInput,
                        onValueChange = { newPresetNameInput = it },
                        label = { Text("اسم القالب") },
                        placeholder = { Text("مثال: نتائج مدرسة أونروا 2026") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPresetNameInput.isNotBlank()) {
                            viewModel.saveCurrentConfigAsPreset(newPresetNameInput)
                            showSavePresetDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("حفظ القالب الان", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSavePresetDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (showInspectorDialog) {
        WebInspectorDialog(
            initialUrl = targetUrl,
            initialIdParam = idParam,
            initialYearParam = yearParam,
            initialFieldsCsv = extractionFields,
            onApplySettings = { url, idKey, yearKey, fieldsCsv, method ->
                viewModel.updateConfigFromInspector(url, idKey, yearKey, fieldsCsv, method)
            },
            onDismiss = { showInspectorDialog = false }
        )
    }
}

@Composable
fun DropdownSelector(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(text = selectedOption.ifBlank { "اختر العمود" }, maxLines = 1)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
