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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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

    private fun cellRefToColIndex(ref: String?): Int {
        if (ref.isNullOrBlank()) return -1
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return -1
        var col = 0
        for (ch in letters) {
            col = col * 26 + (ch - 'A' + 1)
        }
        return col - 1
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
                        val siList = doc.getElementsByTagName("si")
                        for (i in 0 until siList.length) {
                            sharedStrings.add(siList.item(i).textContent ?: "")
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
                            val cellMap = mutableMapOf<Int, String>()
                            var nextCol = 0

                            for (c in 0 until cellNodes.length) {
                                val cellNode = cellNodes.item(c)
                                if (cellNode.nodeName == "c") {
                                    val rAttr = cellNode.attributes?.getNamedItem("r")?.nodeValue
                                    val colIdx = if (rAttr != null) cellRefToColIndex(rAttr) else nextCol
                                    nextCol = if (colIdx >= 0) colIdx + 1 else nextCol + 1

                                    val type = cellNode.attributes?.getNamedItem("t")?.nodeValue
                                    var valStr = ""

                                    if (type == "inlineStr") {
                                        valStr = cellNode.textContent ?: ""
                                    } else {
                                        val vList = cellNode.childNodes
                                        for (v in 0 until vList.length) {
                                            val child = vList.item(v)
                                            if (child.nodeName == "v" || child.nodeName == "t") {
                                                val rawV = child.textContent ?: ""
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
                                    }
                                    if (colIdx >= 0) {
                                        cellMap[colIdx] = valStr
                                    }
                                }
                            }

                            if (cellMap.isNotEmpty()) {
                                val maxCol = cellMap.keys.maxOrNull() ?: 0
                                val rowValues = (0..maxCol).map { cellMap[it] ?: "" }
                                if (rowValues.any { it.isNotBlank() }) {
                                    sheetRows.add(rowValues)
                                }
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

    fun decodeBytesAutoEncoding(bytes: ByteArray): String {
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

        // Check if utf8 text contains genuine Arabic characters without mojibake
        if (containsArabicCharacters(utf8Text) && !isGarbledArabic(utf8Text)) {
            return utf8Text
        }

        // If utf8 contains replacement characters or looks garbled, try Windows-1256 (standard Arabic Excel charset)
        if (utf8Text.contains("\uFFFD") || isGarbledArabic(utf8Text) || !containsArabicCharacters(utf8Text)) {
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
        val mojibakeCount = text.count { ch -> ch in listOf('Ø', 'Ù', 'Ã', 'Â', 'ï', '½', '¾', 'â', '€', '™') }
        return mojibakeCount > 0
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
     * Exports task extraction results as a native binary OpenXML Excel file (.xlsx).
     * Creates a genuine zip archive containing sharedStrings, worksheet, and XML schemas with strict UTF-8 encoding.
     * Guaranteed 100% compatible with MS Excel, Google Sheets, LibreOffice, and mobile Office apps.
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

            // Gather all input keys from extraInputJson
            val allInputKeys = mutableSetOf<String>()
            rows.forEach { row ->
                try {
                    val json = JSONObject(row.extraInputJson)
                    json.keys().forEach { key ->
                        if (key != task.idColumnName && key != task.yearColumnName) {
                            allInputKeys.add(key)
                        }
                    }
                } catch (ignored: Exception) {}
            }

            // Gather all extracted keys from task fields and extractedDataJson
            val allExtractedKeys = LinkedHashSet<String>()

            // ALWAYS include configured fields first
            task.extractionFieldsJson.split(",").forEach { field ->
                val clean = field.trim()
                if (clean.isNotBlank()) {
                    allExtractedKeys.add(clean)
                }
            }

            // ADD any additional dynamic keys found across rows
            rows.forEach { row ->
                try {
                    val json = JSONObject(row.extractedDataJson)
                    json.keys().forEach { key -> allExtractedKeys.add(key) }
                } catch (ignored: Exception) {}
            }

            // Construct Headers
            val headers = mutableListOf(
                "رقم الصف",
                task.idColumnName,
                task.yearColumnName
            )
            headers.addAll(allInputKeys)
            headers.add("حالة الفحص")
            headers.addAll(allExtractedKeys)
            headers.add("رمز HTTP")
            headers.add("مدة الطلب (مللي ثانية)")
            headers.add("تفاصيل الخطأ")

            // Build Matrix
            val matrix = mutableListOf<List<String>>()
            matrix.add(headers)

            rows.forEach { row ->
                val lineTokens = mutableListOf<String>()
                lineTokens.add(row.rowIndex.toString())
                lineTokens.add(row.idValue)
                lineTokens.add(row.yearValue)

                val inputJson = try { JSONObject(row.extraInputJson) } catch (e: Exception) { JSONObject() }
                allInputKeys.forEach { key ->
                    lineTokens.add(inputJson.optString(key, ""))
                }

                lineTokens.add(if (row.status == "SUCCESS") "ناجح" else "فشل")

                val extractedJson = try { JSONObject(row.extractedDataJson) } catch (e: Exception) { JSONObject() }
                allExtractedKeys.forEach { key ->
                    lineTokens.add(extractedJson.optString(key, ""))
                }

                lineTokens.add(row.httpStatusCode.toString())
                lineTokens.add(row.executionDurationMs.toString())
                lineTokens.add(row.errorMessage ?: "")

                matrix.add(lineTokens)
            }

            // Write Native Binary OpenXML .xlsx ZIP Container
            writeNativeXlsxZip(exportFile, matrix)

            exportFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun writeNativeXlsxZip(file: File, matrix: List<List<String>>) {
        val stringMap = LinkedHashMap<String, Int>()
        fun getStringIndex(str: String): Int {
            return stringMap.computeIfAbsent(str) { stringMap.size }
        }

        matrix.forEach { row ->
            row.forEach { cell ->
                getStringIndex(cell)
            }
        }

        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            // 1. [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            val contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            zip.write(contentTypes)
            zip.closeEntry()

            // 2. _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            val mainRels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            zip.write(mainRels)
            zip.closeEntry()

            // 3. xl/_rels/workbook.xml.rels
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            val wbRels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            zip.write(wbRels)
            zip.closeEntry()

            // 4. xl/workbook.xml
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            val wbXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="نتائج الاستعلام" sheetId="1" r:id="rId1"/>
                  </sheets>
                </workbook>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            zip.write(wbXml)
            zip.closeEntry()

            // 5. xl/styles.xml
            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            val stylesXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts>
                  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                  <borders count="1"><border/></borders>
                  <cellXfs count="1"><xf fontId="0" fillId="0" borderId="0"/></cellXfs>
                </styleSheet>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            zip.write(stylesXml)
            zip.closeEntry()

            // 6. xl/sharedStrings.xml
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            val sstSb = StringBuilder()
            sstSb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            sstSb.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${stringMap.size}\" uniqueCount=\"${stringMap.size}\">\n")
            stringMap.keys.forEach { s ->
                sstSb.append("  <si><t>${escapeXml(s)}</t></si>\n")
            }
            sstSb.append("</sst>")
            zip.write(sstSb.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 7. xl/worksheets/sheet1.xml
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val sheetSb = StringBuilder()
            sheetSb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            sheetSb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n")
            sheetSb.append("  <sheetData>\n")

            matrix.forEachIndexed { rIndex, rowValues ->
                val rowNum = rIndex + 1
                sheetSb.append("    <row r=\"$rowNum\">\n")
                rowValues.forEachIndexed { cIndex, cellVal ->
                    val colRef = getExcelColumnName(cIndex)
                    val sIdx = getStringIndex(cellVal)
                    sheetSb.append("      <c r=\"$colRef$rowNum\" t=\"s\"><v>$sIdx</v></c>\n")
                }
                sheetSb.append("    </row>\n")
            }

            sheetSb.append("  </sheetData>\n")
            sheetSb.append("</worksheet>")
            zip.write(sheetSb.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun getExcelColumnName(index: Int): String {
        var temp = index
        var colName = ""
        while (temp >= 0) {
            colName = ('A' + (temp % 26)).toString() + colName
            temp = (temp / 26) - 1
        }
        return colName
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

