package com.example.ui.components

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.EmeraldSuccess
import org.json.JSONArray

class WebInspectorBridge(
    private val onPicked: (mode: String, elementName: String) -> Unit
) {
    @JavascriptInterface
    fun onElementPicked(mode: String, elementName: String) {
        onPicked(mode, elementName)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WebInspectorDialog(
    initialUrl: String,
    initialIdParam: String,
    initialYearParam: String,
    initialFieldsCsv: String,
    onApplySettings: (targetUrl: String, idParamKey: String, yearParamKey: String, extractionFieldsCsv: String, method: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var urlText by remember { mutableStateOf(initialUrl.ifBlank { "https://emis.unrwa.org/Result/StudentsResult" }) }
    var currentLoadedUrl by remember { mutableStateOf(urlText) }
    var isLoadingPage by remember { mutableStateOf(false) }

    var idParamKey by remember { mutableStateOf(initialIdParam.ifBlank { "IdNumber" }) }
    var yearParamKey by remember { mutableStateOf(initialYearParam.ifBlank { "BirthYear" }) }
    var testIdValue by remember { mutableStateOf("900123456") }
    var testYearValue by remember { mutableStateOf("2006") }

    // Interactive picker mode: null, "ID", "YEAR", "SUBMIT", "OUTPUT"
    var pickingMode by remember { mutableStateOf<String?>(null) }
    var pickingBannerMessage by remember { mutableStateOf("") }

    var showOutputFieldConfirmDialog by remember { mutableStateOf(false) }
    var pickedOutputText by remember { mutableStateOf("") }
    var newOutputFieldName by remember { mutableStateOf("") }

    var extractedFieldsList by remember {
        mutableStateOf(
            if (initialFieldsCsv.isNotBlank()) initialFieldsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            else mutableListOf("اسم الطالب", "النتيجة", "المعدل", "الصف", "المدرسة")
        )
    }

    var newFieldInput by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var activeTab by remember { mutableIntStateOf(0) } // 0: WebView, 1: Field Mapping & Results

    // Common Arabic field presets for 1-tap toggling
    val commonFieldPresets = remember {
        listOf("اسم الطالب", "النتيجة", "المعدل", "الصف", "اسم المدرسة", "رقم الجلوس", "اسم العائلة", "الحالة")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top Header Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "معاين ومفتش المواقع التفاعلي",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "حدّد خيارات الاستعلام والبيانات بالنقر المباشر على الشاشة",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق")
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                onApplySettings(
                                    currentLoadedUrl,
                                    idParamKey,
                                    yearParamKey,
                                    extractedFieldsList.joinToString(","),
                                    "POST"
                                )
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("حفظ واعتماد", fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )

                // Quick Preset Templates Banner
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "قوالب جاهزة:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        AssistChip(
                            onClick = {
                                urlText = "https://emis.unrwa.org/Result/StudentsResult"
                                currentLoadedUrl = urlText
                                idParamKey = "IdNumber"
                                yearParamKey = "BirthYear"
                                extractedFieldsList = mutableListOf("اسم الطالب", "النتيجة", "المعدل", "الصف", "المدرسة")
                                webViewInstance?.loadUrl(urlText)
                                Toast.makeText(context, "تم تطبيق قالب نتائج الأونروا بنجاح", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("🎓 بوابة أونروا EMIS", fontSize = 11.sp) }
                        )

                        AssistChip(
                            onClick = {
                                idParamKey = "id_number"
                                yearParamKey = "birth_year"
                                Toast.makeText(context, "تم تطبيق القالب العام (id_number / birth_year)", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("🏫 نموذج عام", fontSize = 11.sp) }
                        )
                    }
                }

                // URL Address Bar
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            placeholder = { Text("رابط موقع الاستعلام...") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            trailingIcon = {
                                if (isLoadingPage) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                        )

                        Button(
                            onClick = {
                                var formatted = urlText.trim()
                                if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
                                    formatted = "https://$formatted"
                                    urlText = formatted
                                }
                                webViewInstance?.loadUrl(formatted)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "انتقال")
                        }
                    }
                }

                // Tabs: Interactive WebView vs Manual Form Controls
                TabRow(selectedTabIndex = activeTab) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("المعاينة والتحديد بالنقر") },
                        icon = { Icon(imageVector = Icons.Default.TouchApp, contentDescription = null) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("إعدادات الخانات والبيانات") },
                        icon = { Icon(imageVector = Icons.Default.Tune, contentDescription = null) }
                    )
                }

                // Tab Contents
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (activeTab == 0) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Interactive Click Picker Action Buttons Bar
                            Card(
                                shape = RoundedCornerShape(0.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "اختر نوع النقر لتحديد العناصر من الصفحة:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                pickingMode = "ID"
                                                pickingBannerMessage = "👉 انقر الآن على خانة (رقم الهوية) في الصفحة أدناه..."
                                                enableJsPickerInWebView(webViewInstance, "ID")
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (pickingMode == "ID") EmeraldSuccess else MaterialTheme.colorScheme.primary
                                            ),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                                        ) {
                                            Text("الهوية", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                pickingMode = "YEAR"
                                                pickingBannerMessage = "👉 انقر الآن على خانة (سنة الميلاد) في الصفحة أدناه..."
                                                enableJsPickerInWebView(webViewInstance, "YEAR")
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (pickingMode == "YEAR") EmeraldSuccess else MaterialTheme.colorScheme.primary
                                            ),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                                        ) {
                                            Text("السنة", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                pickingMode = "SUBMIT"
                                                pickingBannerMessage = "👉 انقر الآن على (زر البحث/الاستعلام) في الصفحة..."
                                                enableJsPickerInWebView(webViewInstance, "SUBMIT")
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (pickingMode == "SUBMIT") EmeraldSuccess else MaterialTheme.colorScheme.secondary
                                            ),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                                        ) {
                                            Text("زر الاستعلام", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                pickingMode = "OUTPUT"
                                                pickingBannerMessage = "👉 انقر الآن على أي نص/بيان في صفحة النتيجة لاستخراجه..."
                                                enableJsPickerInWebView(webViewInstance, "OUTPUT")
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (pickingMode == "OUTPUT") Color(0xFFD97706) else MaterialTheme.colorScheme.tertiary
                                            ),
                                            modifier = Modifier.weight(1.2f),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("تحديد مخرج", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    // Picking Instruction Banner
                                    AnimatedVisibility(visible = pickingMode != null) {
                                        Surface(
                                            color = EmeraldSuccess.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = pickingBannerMessage,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                IconButton(
                                                    onClick = { pickingMode = null },
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Icon(imageVector = Icons.Default.Close, contentDescription = "إلغاء")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Embedded WebView Frame
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true

                                        addJavascriptInterface(
                                            WebInspectorBridge { mode, elementName ->
                                                when (mode) {
                                                    "ID" -> {
                                                        idParamKey = elementName
                                                        Toast.makeText(context, "تم تحديد خانة رقم الهوية: $elementName", Toast.LENGTH_SHORT).show()
                                                    }
                                                    "YEAR" -> {
                                                        yearParamKey = elementName
                                                        Toast.makeText(context, "تم تحديد خانة سنة الميلاد: $elementName", Toast.LENGTH_SHORT).show()
                                                    }
                                                    "SUBMIT" -> {
                                                        Toast.makeText(context, "تم التقاط زر الاستعلام ($elementName)", Toast.LENGTH_SHORT).show()
                                                    }
                                                    "OUTPUT" -> {
                                                        pickedOutputText = elementName
                                                        newOutputFieldName = suggestFieldNameFromText(elementName)
                                                        showOutputFieldConfirmDialog = true
                                                    }
                                                }
                                                pickingMode = null
                                            },
                                            "AndroidInspector"
                                        )

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                isLoadingPage = true
                                                url?.let { currentLoadedUrl = it }
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                isLoadingPage = false
                                                url?.let { currentLoadedUrl = it }
                                            }
                                        }

                                        loadUrl(urlText)
                                        webViewInstance = this
                                    }
                                },
                                update = { webView ->
                                    webViewInstance = webView
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        // Form Controls & Custom Extraction Fields Tab
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Card 1: Input Parameters
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "1. أسماء معاملات الإدخال المستهدفة",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = idParamKey,
                                            onValueChange = { idParamKey = it },
                                            label = { Text("اسم معامل رقم الهوية") },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        )

                                        OutlinedTextField(
                                            value = yearParamKey,
                                            onValueChange = { yearParamKey = it },
                                            label = { Text("اسم معامل سنة الميلاد") },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            val fillJs = """
                                                (function() {
                                                    var idEl = document.querySelector('input[name="$idParamKey"], input[id="$idParamKey"]');
                                                    if (idEl) idEl.value = "$testIdValue";
                                                    var yrEl = document.querySelector('input[name="$yearParamKey"], input[id="$yearParamKey"]');
                                                    if (yrEl) yrEl.value = "$testYearValue";
                                                    var btn = document.querySelector('button[type="submit"], input[type="submit"], button.btn, input.btn');
                                                    if (btn) btn.click();
                                                    return "Submitted";
                                                })();
                                            """.trimIndent()

                                            webViewInstance?.evaluateJavascript(fillJs) {
                                                activeTab = 0
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("تعبئة وقيم تجريبية واختبار الاستعلام في المتصفح")
                                    }
                                }
                            }

                            // Card 2: Extracted Data Fields
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "2. البيانات المطلوب استخراجها من النتيجة",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )

                                    Text(
                                        text = "انقر على العناوين الشائعة لإضافتها أو إزالتها بضغطة واحدة:",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    // Common Presets Toggle Chips
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        commonFieldPresets.forEach { preset ->
                                            val isSelected = extractedFieldsList.contains(preset)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    extractedFieldsList = if (isSelected) {
                                                        extractedFieldsList.toMutableList().apply { remove(preset) }
                                                    } else {
                                                        extractedFieldsList.toMutableList().apply { add(preset) }
                                                    }
                                                },
                                                label = { Text(preset) },
                                                leadingIcon = {
                                                    if (isSelected) {
                                                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                                    Text(text = "القائمة المعتمدة للاستخراج حالياً (${extractedFieldsList.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        extractedFieldsList.forEach { field ->
                                            InputChip(
                                                selected = true,
                                                onClick = {
                                                    extractedFieldsList = extractedFieldsList.toMutableList().apply { remove(field) }
                                                },
                                                label = { Text(field) },
                                                trailingIcon = {
                                                    Icon(imageVector = Icons.Default.Close, contentDescription = "حذف", modifier = Modifier.size(14.dp))
                                                }
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = newFieldInput,
                                            onValueChange = { newFieldInput = it },
                                            placeholder = { Text("إضافة عنوان مخصص آخر...") },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            singleLine = true
                                        )

                                        Button(
                                            onClick = {
                                                if (newFieldInput.isNotBlank() && !extractedFieldsList.contains(newFieldInput.trim())) {
                                                    extractedFieldsList = extractedFieldsList.toMutableList().apply { add(newFieldInput.trim()) }
                                                    newFieldInput = ""
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Add, contentDescription = "إضافة")
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

    // Dialog to confirm adding picked output data field from WebView result
    if (showOutputFieldConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showOutputFieldConfirmDialog = false },
            title = { Text("إضافة حقل مخرج من نتيجة الصفحة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "النص المنقور من صفحة الموقع:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = pickedOutputText.ifBlank { "عنصر محدد" },
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    OutlinedTextField(
                        value = newOutputFieldName,
                        onValueChange = { newOutputFieldName = it },
                        label = { Text("عنوان حقل البيانات لجدول Excel") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newOutputFieldName.trim()
                        if (name.isNotBlank()) {
                            if (!extractedFieldsList.contains(name)) {
                                extractedFieldsList = extractedFieldsList.toMutableList().apply { add(name) }
                            }
                            Toast.makeText(context, "تمت إضافة حقل: $name", Toast.LENGTH_SHORT).show()
                        }
                        showOutputFieldConfirmDialog = false
                    }
                ) {
                    Text("إضافة إلى أعمدة Excel")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOutputFieldConfirmDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

private fun suggestFieldNameFromText(text: String): String {
    val clean = text.trim()
    return when {
        clean.contains("اسم") || clean.contains("طالب") -> "اسم الطالب"
        clean.contains("نتيجة") || clean.contains("ناجح") || clean.contains("راسب") -> "النتيجة"
        clean.contains("معدل") || clean.contains("%") -> "المعدل"
        clean.contains("صف") || clean.contains("مرحلة") -> "الصف"
        clean.contains("مدرسة") -> "اسم المدرسة"
        clean.contains("جلس") || clean.contains("رقم") -> "رقم الجلوس"
        clean.length in 1..25 -> clean
        else -> "حقل مخرج جديد"
    }
}

private fun enableJsPickerInWebView(webView: WebView?, mode: String) {
    val js = """
        (function() {
            var mode = '$mode';
            var handler = function(e) {
                e.preventDefault();
                e.stopPropagation();
                var el = e.target;
                
                var pickedVal = '';
                if (mode === 'OUTPUT') {
                    pickedVal = (el.innerText || el.textContent || el.value || '').trim();
                } else {
                    pickedVal = el.name || el.id || el.placeholder || el.getAttribute('aria-label') || el.tagName;
                }
                
                // Highlight visual effect
                el.style.border = '4px solid #10B981';
                el.style.boxShadow = '0 0 12px #10B981';
                
                if (window.AndroidInspector) {
                    window.AndroidInspector.onElementPicked(mode, pickedVal);
                }
                document.removeEventListener('click', handler, true);
            };
            document.addEventListener('click', handler, true);
        })();
    """.trimIndent()
    webView?.evaluateJavascript(js, null)
}

