package com.example.util

import android.content.Context
import android.net.Uri
import com.example.data.model.ExtractionTask
import com.example.data.model.TaskRow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

object CsvExcelUtils {

    data class ParsedFileData(
        val fileName: String,
        val headers: List<String>,
        val rows: List<Map<String, String>>
    )

    /**
     * Parses CSV or raw text input with automatic encoding detection for Arabic (UTF-8, Windows-1256, ISO-8859-6, UTF-16).
     */
    fun parseCsvFromUri(context: Context, uri: Uri): ParsedFileData? {
        return try {
            val contentResolver = context.contentResolver
            val fileName = getFileName(context, uri) ?: "imported_file.csv"

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.isEmpty()) return null

            val text = decodeBytesAutoEncoding(bytes)
            parseTextToStructuredData(fileName, text)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Parses raw pasted text (e.g., copied directly from Excel table or text file)
     */
    fun parsePastedText(rawText: String, defaultFileName: String = "بيانات_ملصوقة.csv"): ParsedFileData? {
        if (rawText.isBlank()) return null
        return parseTextToStructuredData(defaultFileName, rawText)
    }

    private fun decodeBytesAutoEncoding(bytes: ByteArray): String {
        // Check for BOMs
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // Try standard UTF-8 first
        val utf8Text = String(bytes, Charsets.UTF_8)
        
        // If utf8 contains replacement characters (\uFFFD) or looks garbled, try Windows-1256 (standard Arabic Excel charset)
        if (utf8Text.contains("\uFFFD") || isGarbledArabic(utf8Text)) {
            try {
                val win1256Charset = Charset.forName("windows-1256")
                val winText = String(bytes, win1256Charset)
                if (containsArabicCharacters(winText)) {
                    return winText
                }
            } catch (ignored: Exception) {}

            try {
                val isoCharset = Charset.forName("ISO-8859-6")
                val isoText = String(bytes, isoCharset)
                if (containsArabicCharacters(isoText)) {
                    return isoText
                }
            } catch (ignored: Exception) {}
        }

        return utf8Text
    }

    private fun containsArabicCharacters(text: String): Boolean {
        return text.any { ch -> ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' }
    }

    private fun isGarbledArabic(text: String): Boolean {
        // Check if string has common mojibake characters like Ø,Ù,Ã,Â instead of Arabic letters
        val mojibakeCount = text.count { ch -> ch in listOf('Ø', 'Ù', 'Ã', 'Â', 'ï', '½', '¾') }
        return mojibakeCount > 5
    }

    private fun parseTextToStructuredData(fileName: String, rawText: String): ParsedFileData? {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val firstLine = lines.first()
        val delimiter = detectDelimiter(firstLine)
        val firstTokens = parseCsvLine(firstLine, delimiter).map { it.trim() }

        // Check if first line is a header or data row
        val isFirstLineHeader = firstTokens.any { token ->
            token.contains("هوية", ignoreCase = true) ||
                    token.contains("سنة", ignoreCase = true) ||
                    token.contains("اسم", ignoreCase = true) ||
                    token.contains("id", ignoreCase = true) ||
                    token.contains("year", ignoreCase = true) ||
                    token.contains("birth", ignoreCase = true) ||
                    token.contains("name", ignoreCase = true) ||
                    !token.all { ch -> ch.isDigit() || ch == '-' || ch == ' ' }
        }

        val headers: List<String>
        val startIndex: Int

        if (isFirstLineHeader) {
            headers = firstTokens
            startIndex = 1
        } else {
            // Generate auto headers
            headers = if (firstTokens.size >= 2) {
                listOf("رقم الهوية", "سنة الميلاد") + (3..firstTokens.size).map { "عمود $it" }
            } else {
                listOf("رقم الهوية")
            }
            startIndex = 0
        }

        val dataRows = mutableListOf<Map<String, String>>()
        for (i in startIndex until lines.size) {
            val tokens = parseCsvLine(lines[i], delimiter)
            val rowMap = mutableMapOf<String, String>()
            headers.forEachIndexed { index, header ->
                val value = if (index < tokens.size) tokens[index].trim() else ""
                rowMap[header] = value
            }
            if (rowMap.values.any { it.isNotEmpty() }) {
                dataRows.add(rowMap)
            }
        }

        if (dataRows.isEmpty()) return null
        return ParsedFileData(fileName, headers, dataRows)
    }

    fun generateSampleStudentCsv(context: Context): ParsedFileData {
        val fileName = "نموذج_بيانات_الطلاب.csv"
        val headers = listOf("رقم الهوية", "سنة الميلاد", "الاسم المبدئي", "ملاحظات")
        val sampleData = listOf(
            mapOf("رقم الهوية" to "900123456", "سنة الميلاد" to "2006", "الاسم المبدئي" to "طالب تجريبي 1", "ملاحظات" to "غزة"),
            mapOf("رقم الهوية" to "900123457", "سنة الميلاد" to "2006", "الاسم المبدئي" to "طالب تجريبي 2", "ملاحظات" to "رفح"),
            mapOf("رقم الهوية" to "900123458", "سنة الميلاد" to "2005", "الاسم المبدئي" to "طالب تجريبي 3", "ملاحظات" to "الوسطى"),
            mapOf("رقم الهوية" to "900123459", "سنة الميلاد" to "2007", "الاسم المبدئي" to "طالب تجريبي 4", "ملاحظات" to "خان يونس"),
            mapOf("رقم الهوية" to "900123460", "سنة الميلاد" to "2006", "الاسم المبدئي" to "طالب تجريبي 5", "ملاحظات" to "الشمال")
        )
        return ParsedFileData(fileName, headers, sampleData)
    }

    fun exportTaskResultsToCsv(
        context: Context,
        task: ExtractionTask,
        rows: List<TaskRow>
    ): File? {
        return try {
            val baseName = task.sourceFileName.substringBeforeLast(".")
            val exportFileName = "${baseName}_نتائج.csv"

            val exportsDir = File(context.getExternalFilesDir(null), "Exports")
            if (!exportsDir.exists()) exportsDir.mkdirs()

            val exportFile = File(exportsDir, exportFileName)

            // Extract unique dynamic response keys
            val allExtractedKeys = mutableSetOf<String>()
            rows.forEach { row ->
                try {
                    val json = JSONObject(row.extractedDataJson)
                    json.keys().forEach { key -> allExtractedKeys.add(key) }
                } catch (ignored: Exception) {}
            }

            // Fallback expected headers if empty
            if (allExtractedKeys.isEmpty()) {
                task.extractionFieldsJson.split(",").forEach { field ->
                    if (field.isNotBlank()) allExtractedKeys.add(field.trim())
                }
            }

            val headerRow = mutableListOf(
                "رقم الصف",
                task.idColumnName,
                task.yearColumnName,
                "الحالة",
                "رمز HTTP",
                "مدة الطلب (مللي ثانية)"
            )
            headerRow.addAll(allExtractedKeys)
            headerRow.add("تفاصيل الخطأ")

            FileOutputStream(exportFile).use { fos ->
                // Add UTF-8 BOM so Excel opens Arabic properly without garbled text
                fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

                // Write Header
                val headerLine = headerRow.joinToString(",") { escapeCsv(it) } + "\r\n"
                fos.write(headerLine.toByteArray(Charsets.UTF_8))

                // Write Data Rows
                rows.forEach { row ->
                    val lineTokens = mutableListOf<String>()
                    lineTokens.add(row.rowIndex.toString())
                    lineTokens.add(row.idValue)
                    lineTokens.add(row.yearValue)
                    lineTokens.add(row.status)
                    lineTokens.add(row.httpStatusCode.toString())
                    lineTokens.add(row.executionDurationMs.toString())

                    val extractedJson = try { JSONObject(row.extractedDataJson) } catch (e: Exception) { JSONObject() }
                    allExtractedKeys.forEach { key ->
                        val value = extractedJson.optString(key, "")
                        lineTokens.add(value)
                    }

                    lineTokens.add(row.errorMessage ?: "")

                    val rowLine = lineTokens.joinToString(",") { escapeCsv(it) } + "\r\n"
                    fos.write(rowLine.toByteArray(Charsets.UTF_8))
                }
            }

            exportFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun detectDelimiter(line: String): Char {
        return when {
            line.contains("\t") -> '\t'
            line.contains(";") -> ';'
            else -> ','
        }
    }

    private fun parseCsvLine(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (ch in line) {
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (ch == delimiter && !inQuotes) {
                result.add(sb.toString())
                sb.clear()
            } else {
                sb.append(ch)
            }
        }
        result.add(sb.toString())
        return result
    }

    private fun escapeCsv(value: String): String {
        val clean = value.replace("\n", " ").replace("\r", " ")
        return if (clean.contains(",") || clean.contains("\"") || clean.contains(";") || clean.contains("\t")) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else {
            clean
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex("_display_name")
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
