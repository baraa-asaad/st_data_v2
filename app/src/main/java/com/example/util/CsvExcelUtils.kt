package com.example.util

import android.content.Context
import android.net.Uri
import com.example.data.model.ExtractionTask
import com.example.data.model.TaskRow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object CsvExcelUtils {

    data class ParsedFileData(
        val fileName: String,
        val headers: List<String>,
        val rows: List<Map<String, String>>
    )

    /**
     * Parses CSV, TXT, or binary XLSX input with automatic encoding detection for Arabic.
     */
    fun parseCsvFromUri(context: Context, uri: Uri): ParsedFileData? {
        return try {
            val contentResolver = context.contentResolver
            val fileName = getFileName(context, uri) ?: "imported_file.xlsx"

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.isEmpty()) return null

            // Check if binary XLSX zip file (PK magic bytes 0x50 0x4B)
            if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                val parsedXlsx = parseBinaryXlsx(fileName, bytes)
                if (parsedXlsx != null && parsedXlsx.rows.isNotEmpty()) {
                    return parsedXlsx
                }
            }

            val text = decodeBytesAutoEncoding(bytes)
            parseTextToStructuredData(fileName, text)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Native lightweight XLSX reader using ZipInputStream + DocumentBuilderFactory
     */
    private fun parseBinaryXlsx(fileName: String, bytes: ByteArray): ParsedFileData? {
        return try {
            val sharedStrings = mutableListOf<String>()
            val sheetRows = mutableListOf<List<String>>()

            // 1. First pass: read xl/sharedStrings.xml
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/sharedStrings.xml") {
                        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(zis)
                        val tList = doc.getElementsByTagName("t")
                        for (i in 0 until tList.length) {
                            sharedStrings.add(tList.item(i).textContent ?: "")
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            // 2. Second pass: read sheet1.xml
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/worksheets/sheet1.xml" || entry.name.startsWith("xl/worksheets/sheet")) {
                        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(zis)
                        val rowNodes = doc.getElementsByTagName("row")
                        for (r in 0 until rowNodes.length) {
                            val rowEl = rowNodes.item(r)
                            val cellNodes = rowEl.childNodes
                            val rowValues = mutableListOf<String>()
                            for (c in 0 until cellNodes.length) {
                                val cellNode = cellNodes.item(c)
                                if (cellNode.nodeName == "c") {
                                    val type = cellNode.attributes?.getNamedItem("t")?.nodeValue
                                    var valStr = ""
                                    val vList = cellNode.childNodes
                                    for (v in 0 until vList.length) {
                                        if (vList.item(v).nodeName == "v") {
                                            val rawV = vList.item(v).textContent ?: ""
                                            if (type == "s") {
                                                val idx = rawV.toIntOrNull() ?: -1
                                                if (idx in sharedStrings.indices) {
                                                    valStr = sharedStrings[idx]
                                                }
                                            } else {
                                                valStr = rawV
                                            }
                                        }
                                    }
                                    rowValues.add(valStr)
                                }
                            }
                            if (rowValues.any { it.isNotBlank() }) {
                                sheetRows.add(rowValues)
                            }
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }

            if (sheetRows.isEmpty()) return null

            val headers = sheetRows.first()
            val dataRows = mutableListOf<Map<String, String>>()
            for (i in 1 until sheetRows.size) {
                val rowList = sheetRows[i]
                val map = mutableMapOf<String, String>()
                headers.forEachIndexed { idx, h ->
                    map[h] = rowList.getOrNull(idx) ?: ""
                }
                if (map.values.any { it.isNotBlank() }) {
                    dataRows.add(map)
                }
            }

            ParsedFileData(fileName, headers, dataRows)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Parses raw pasted text (e.g., copied directly from Excel table or text file)
     */
    fun parsePastedText(rawText: String, defaultFileName: String = "بيانات_ملصوقة.xlsx"): ParsedFileData? {
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
        val mojibakeCount = text.count { ch -> ch in listOf('Ø', 'Ù', 'Ã', 'Â', 'ï', '½', '¾') }
        return mojibakeCount > 5
    }

    private fun parseTextToStructuredData(fileName: String, rawText: String): ParsedFileData? {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val firstLine = lines.first()
        val delimiter = detectDelimiter(firstLine)
        val firstTokens = parseCsvLine(firstLine, delimiter).map { it.trim() }

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
        val fileName = "نموذج_بيانات_الطلاب.xlsx"
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

    /**
     * Exports task extraction results as an XML Spreadsheet Excel file (.xlsx)
     * Encoded strictly in UTF-8 with XML headers, fully compatible with MS Excel, Google Sheets, LibreOffice and mobile apps without garbled characters.
     */
    fun exportTaskResultsToExcel(
        context: Context,
        task: ExtractionTask,
        rows: List<TaskRow>
    ): File? {
        return try {
            val baseName = task.sourceFileName.substringBeforeLast(".")
            val exportFileName = "${baseName}_نتائج.xlsx"

            val exportsDir = File(context.getExternalFilesDir(null), "Exports")
            if (!exportsDir.exists()) exportsDir.mkdirs()

            val exportFile = File(exportsDir, exportFileName)

            val allExtractedKeys = mutableSetOf<String>()
            rows.forEach { row ->
                try {
                    val json = JSONObject(row.extractedDataJson)
                    json.keys().forEach { key -> allExtractedKeys.add(key) }
                } catch (ignored: Exception) {}
            }

            if (allExtractedKeys.isEmpty()) {
                task.extractionFieldsJson.split(",").forEach { field ->
                    if (field.isNotBlank()) allExtractedKeys.add(field.trim())
                }
            }

            val headers = mutableListOf(
                "رقم الصف",
                task.idColumnName,
                task.yearColumnName,
                "الحالة",
                "رمز HTTP",
                "مدة الطلب (مللي ثانية)"
            )
            headers.addAll(allExtractedKeys)
            headers.add("تفاصيل الخطأ")

            val xmlBuilder = StringBuilder()
            xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            xmlBuilder.append("<?mso-application progid=\"Excel.Sheet\"?>\n")
            xmlBuilder.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"\n")
            xmlBuilder.append(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"\n")
            xmlBuilder.append(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"\n")
            xmlBuilder.append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"\n")
            xmlBuilder.append(" xmlns:html=\"http://www.w3.org/TR/REC-html40\">\n")

            xmlBuilder.append(" <Styles>\n")
            xmlBuilder.append("  <Style ss:ID=\"HeaderStyle\">\n")
            xmlBuilder.append("   <Font ss:FontName=\"Arial\" ss:Size=\"11\" ss:Color=\"#FFFFFF\" ss:Bold=\"1\"/>\n")
            xmlBuilder.append("   <Interior ss:Color=\"#10B981\" ss:Pattern=\"Solid\"/>\n")
            xmlBuilder.append("   <Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\"/>\n")
            xmlBuilder.append("  </Style>\n")
            xmlBuilder.append("  <Style ss:ID=\"DataStyle\">\n")
            xmlBuilder.append("   <Font ss:FontName=\"Arial\" ss:Size=\"10\" ss:Color=\"#000000\"/>\n")
            xmlBuilder.append("   <Alignment ss:Horizontal=\"Right\" ss:Vertical=\"Center\"/>\n")
            xmlBuilder.append("  </Style>\n")
            xmlBuilder.append(" </Styles>\n")

            xmlBuilder.append(" <Worksheet ss:Name=\"نتائج الاستعلام\">\n")
            xmlBuilder.append("  <Table>\n")

            // Header Row
            xmlBuilder.append("   <Row ss:Height=\"24\">\n")
            headers.forEach { h ->
                xmlBuilder.append("    <Cell ss:StyleID=\"HeaderStyle\"><Data ss:Type=\"String\">${escapeXml(h)}</Data></Cell>\n")
            }
            xmlBuilder.append("   </Row>\n")

            // Data Rows
            rows.forEach { row ->
                val lineTokens = mutableListOf<String>()
                lineTokens.add(row.rowIndex.toString())
                lineTokens.add(row.idValue)
                lineTokens.add(row.yearValue)
                lineTokens.add(if (row.status == "SUCCESS") "ناجح" else "فشل")
                lineTokens.add(row.httpStatusCode.toString())
                lineTokens.add(row.executionDurationMs.toString())

                val extractedJson = try { JSONObject(row.extractedDataJson) } catch (e: Exception) { JSONObject() }
                allExtractedKeys.forEach { key ->
                    val value = extractedJson.optString(key, "")
                    lineTokens.add(value)
                }

                lineTokens.add(row.errorMessage ?: "")

                xmlBuilder.append("   <Row ss:Height=\"20\">\n")
                lineTokens.forEach { token ->
                    xmlBuilder.append("    <Cell ss:StyleID=\"DataStyle\"><Data ss:Type=\"String\">${escapeXml(token)}</Data></Cell>\n")
                }
                xmlBuilder.append("   </Row>\n")
            }

            xmlBuilder.append("  </Table>\n")
            xmlBuilder.append(" </Worksheet>\n")
            xmlBuilder.append("</Workbook>")

            FileOutputStream(exportFile).use { fos ->
                fos.write(xmlBuilder.toString().toByteArray(Charsets.UTF_8))
            }

            exportFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
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

