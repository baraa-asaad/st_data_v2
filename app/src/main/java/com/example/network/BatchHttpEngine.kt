package com.example.network

import com.example.data.model.ExtractionTask
import com.example.data.model.TaskRow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object BatchHttpEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class HttpExecutionResult(
        val statusCode: Int,
        val isSuccess: Boolean,
        val durationMs: Long,
        val extractedDataMap: Map<String, String>,
        val rawBody: String,
        val errorMessage: String?
    )

    fun executeRequest(
        task: ExtractionTask,
        row: TaskRow
    ): HttpExecutionResult {
        val startTime = System.currentTimeMillis()
        var urlString = task.targetUrl.trim()
        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            urlString = "https://$urlString"
        }

        try {
            val requestBuilder = Request.Builder()
            requestBuilder.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")

            // Add Custom Headers if configured
            if (task.customHeadersJson.isNotBlank() && task.customHeadersJson != "{}") {
                try {
                    val headersObj = JSONObject(task.customHeadersJson)
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        requestBuilder.header(key, headersObj.getString(key))
                    }
                } catch (ignored: Exception) {}
            }

            if (task.requestMethod.uppercase() == "GET") {
                val httpUrlBuilder = urlString.toHttpUrlOrNull()?.newBuilder() ?: HttpUrl.Builder().scheme("https").host(urlString)
                httpUrlBuilder.addQueryParameter(task.idParamKey, row.idValue)
                httpUrlBuilder.addQueryParameter(task.yearParamKey, row.yearValue)
                
                requestBuilder.url(httpUrlBuilder.build())
                requestBuilder.get()
            } else {
                // Default POST (Form Data)
                val formBodyBuilder = FormBody.Builder()
                formBodyBuilder.add(task.idParamKey, row.idValue)
                formBodyBuilder.add(task.yearParamKey, row.yearValue)

                requestBuilder.url(urlString)
                requestBuilder.post(formBodyBuilder.build())
            }

            val request = requestBuilder.build()
            val response: Response = client.newCall(request).execute()
            val durationMs = System.currentTimeMillis() - startTime
            val statusCode = response.code

            val responseBytes = response.body?.bytes() ?: byteArrayOf()
            val rawDecoded = com.example.util.CsvExcelUtils.decodeBytesAutoEncoding(responseBytes)
            val bodyString = com.example.util.CsvExcelUtils.fixArabicMojibake(rawDecoded)
            val sampleBody = if (bodyString.length > 1200) bodyString.substring(0, 1200) + "..." else bodyString

            if (response.isSuccessful) {
                val extractedMap = parseResponseData(bodyString, task.extractionFieldsJson)
                return HttpExecutionResult(
                    statusCode = statusCode,
                    isSuccess = true,
                    durationMs = durationMs,
                    extractedDataMap = extractedMap,
                    rawBody = sampleBody,
                    errorMessage = null
                )
            } else {
                return HttpExecutionResult(
                    statusCode = statusCode,
                    isSuccess = false,
                    durationMs = durationMs,
                    extractedDataMap = emptyMap(),
                    rawBody = sampleBody,
                    errorMessage = "HTTP $statusCode - ${response.message}"
                )
            }

        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            return HttpExecutionResult(
                statusCode = 0,
                isSuccess = false,
                durationMs = durationMs,
                extractedDataMap = emptyMap(),
                rawBody = "",
                errorMessage = e.localizedMessage ?: "فشل الاتصال بالخادم"
            )
        }
    }

    private fun parseResponseData(bodyString: String, expectedFieldsCsv: String): Map<String, String> {
        val resultMap = mutableMapOf<String, String>()
        val fieldsList = expectedFieldsCsv.split(",").map { com.example.util.CsvExcelUtils.fixArabicMojibake(it.trim()) }.filter { it.isNotEmpty() }

        val cleanHtml = com.example.util.CsvExcelUtils.fixArabicMojibake(bodyString)
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")

        // 1. Try parsing JSON first
        try {
            val trimmed = cleanHtml.trim()
            if (trimmed.startsWith("{")) {
                val jsonObj = JSONObject(trimmed)
                fieldsList.forEach { field ->
                    val valNested = findInJsonObject(jsonObj, field)
                    if (valNested != null && valNested.isNotBlank()) {
                        resultMap[field] = com.example.util.CsvExcelUtils.fixArabicMojibake(valNested)
                    }
                }
                if (resultMap.isNotEmpty()) return resultMap
            } else if (trimmed.startsWith("[")) {
                val jsonArr = JSONArray(trimmed)
                if (jsonArr.length() > 0 && jsonArr.get(0) is JSONObject) {
                    val jsonObj = jsonArr.getJSONObject(0)
                    fieldsList.forEach { field ->
                        val valNested = findInJsonObject(jsonObj, field)
                        if (valNested != null && valNested.isNotBlank()) {
                            resultMap[field] = com.example.util.CsvExcelUtils.fixArabicMojibake(valNested)
                        }
                    }
                    if (resultMap.isNotEmpty()) return resultMap
                }
            }
        } catch (ignored: Exception) {}

        // 2. Extract all HTML table key-value pairs
        val tablePairs = extractAllTablePairsFromHtml(cleanHtml)

        // 3. Match configured fields against tablePairs or HTML regex
        fieldsList.forEach { field ->
            var foundVal: String? = null
            for ((k, v) in tablePairs) {
                if (k.equals(field, ignoreCase = true) || k.contains(field, ignoreCase = true) || field.contains(k, ignoreCase = true)) {
                    foundVal = v
                    break
                }
            }
            if (foundVal.isNull_or_blank()) {
                foundVal = extractFieldFromHtmlOrText(cleanHtml, field)
            }
            if (!foundVal.isNull_or_blank()) {
                resultMap[field] = com.example.util.CsvExcelUtils.fixArabicMojibake(foundVal!!)
            }
        }

        // 4. Also include any remaining extracted table pairs
        tablePairs.forEach { (k, v) ->
            if (!resultMap.containsKey(k) && v.isNotBlank()) {
                resultMap[k] = com.example.util.CsvExcelUtils.fixArabicMojibake(v)
            }
        }

        // 5. Auto-detect common Arabic student labels if missing
        val autoLabels = listOf("اسم الطالب", "الاسم", "النتيجة", "المعدل", "الصف", "المدرسة", "رقم الجلوس")
        autoLabels.forEach { label ->
            if (!resultMap.containsKey(label)) {
                val foundVal = extractFieldFromHtmlOrText(cleanHtml, label)
                if (foundVal != null && foundVal.isNotBlank()) {
                    resultMap[label] = com.example.util.CsvExcelUtils.fixArabicMojibake(foundVal)
                }
            }
        }

        // 6. Default fallback if no field matched: add clean text summary
        if (resultMap.isEmpty()) {
            val cleanText = stripHtmlTags(cleanHtml)
            val summary = if (cleanText.length > 200) cleanText.substring(0, 200) + "..." else cleanText
            resultMap["الملخص"] = if (summary.isNotBlank()) com.example.util.CsvExcelUtils.fixArabicMojibake(summary) else "تمت الاستجابة بنجاح"
        }

        return resultMap
    }

    private fun extractAllTablePairsFromHtml(html: String): Map<String, String> {
        val pairs = mutableMapOf<String, String>()
        try {
            val trPattern = Pattern.compile("(?i)<tr[^>]*>([\\s\\S]*?)</tr>")
            val cellPattern = Pattern.compile("(?i)<(?:td|th)[^>]*>([\\s\\S]*?)</(?:td|th)>")
            val trMatcher = trPattern.matcher(html)

            val rows = mutableListOf<List<String>>()
            while (trMatcher.find()) {
                val trInner = trMatcher.group(1) ?: continue
                val cellMatcher = cellPattern.matcher(trInner)
                val rowCells = mutableListOf<String>()
                while (cellMatcher.find()) {
                    val raw = cellMatcher.group(1) ?: ""
                    val clean = stripHtmlTags(raw)
                    rowCells.add(clean)
                }
                if (rowCells.any { it.isNotBlank() }) {
                    rows.add(rowCells)
                }
            }

            for (row in rows) {
                if (row.size == 2) {
                    val k = com.example.util.CsvExcelUtils.fixArabicMojibake(row[0].removeSuffix(":").removeSuffix("=").trim())
                    val v = com.example.util.CsvExcelUtils.fixArabicMojibake(row[1].trim())
                    if (k.isNotBlank() && v.isNotBlank() && k.length <= 60 && k != v) {
                        pairs[k] = v
                    }
                } else if (row.size == 3 && row[1].trim() in listOf(":", "=", "-")) {
                    val k = com.example.util.CsvExcelUtils.fixArabicMojibake(row[0].trim())
                    val v = com.example.util.CsvExcelUtils.fixArabicMojibake(row[2].trim())
                    if (k.isNotBlank() && v.isNotBlank() && k.length <= 60) {
                        pairs[k] = v
                    }
                }
            }

            if (rows.size >= 2) {
                val headers = rows[0]
                val firstDataRow = rows[1]
                if (headers.size == firstDataRow.size && headers.size > 1) {
                    headers.forEachIndexed { idx, rawH ->
                        val k = com.example.util.CsvExcelUtils.fixArabicMojibake(rawH.trim())
                        val v = com.example.util.CsvExcelUtils.fixArabicMojibake(firstDataRow.getOrNull(idx)?.trim() ?: "")
                        if (k.isNotBlank() && v.isNotBlank() && k.length <= 60) {
                            pairs[k] = v
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return pairs
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.isBlank()
    }

    private fun findInJsonObject(jsonObj: JSONObject, key: String): String? {
        val keys = jsonObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.equals(key, ignoreCase = true)) {
                return jsonObj.optString(k)
            }
            val child = jsonObj.optJSONObject(k)
            if (child != null) {
                val found = findInJsonObject(child, key)
                if (found != null) return found
            }
        }
        return null
    }

    private fun extractFieldFromHtmlOrText(html: String, fieldName: String): String? {
        val cleanFieldName = fieldName.trim()
        if (cleanFieldName.isEmpty()) return null

        val variants = listOf(
            cleanFieldName,
            cleanFieldName.replace("اسم ", ""),
            "اسم $cleanFieldName"
        ).distinct()

        for (variant in variants) {
            try {
                val quoted = Pattern.quote(variant)

                // Pattern 1: Table row <tr> <th>/<td> Label </td> <td> Value </td> </tr>
                val patternTr = Pattern.compile("(?i)<tr[^>]*>\\s*<(?:td|th)[^>]*>\\s*${quoted}\\s*[:\\?]?\\s*</(?:td|th)>\\s*<td[^>]*>([\\s\\S]{1,200}?)</td>", Pattern.CASE_INSENSITIVE)
                val matcherTr = patternTr.matcher(html)
                if (matcherTr.find()) {
                    val raw = matcherTr.group(1)
                    if (raw != null) {
                        val v = stripHtmlTags(raw)
                        if (v.isNotBlank()) return v
                    }
                }

                // Pattern 2: <td>/<th>/<span>/<div>/<label> Label </...> <td/span/div/p/b> Value </...>
                val patternTd = Pattern.compile("(?i)(?:<td[^>]*>|<th[^>]*>|<span[^>]*>|<div[^>]*>|<label[^>]*>)\\s*${quoted}\\s*[:\\?]?\\s*(?:</[^>]+>)*\\s*(?:<td[^>]*>|<span[^>]*>|<div[^>]*>|<p[^>]*>|<b[^>]*>|)(.*?)(?:</td>|</span>|</div>|</p>|</th>|<br>|<br/>|$)", Pattern.CASE_INSENSITIVE)
                val matcherTd = patternTd.matcher(html)
                if (matcherTd.find()) {
                    val raw = matcherTd.group(1)
                    if (raw != null) {
                        val v = stripHtmlTags(raw)
                        if (v.isNotBlank() && v != variant) return v
                    }
                }

                // Pattern 3: Label: Value in plain text or tags
                val patternKv = Pattern.compile("(?i)${quoted}\\s*[:=]\\s*[\"']?([^\"'<>\\n\\r]{1,100})[\"']?", Pattern.CASE_INSENSITIVE)
                val matcherKv = patternKv.matcher(html)
                if (matcherKv.find()) {
                    val raw = matcherKv.group(1)
                    if (raw != null) {
                        val v = stripHtmlTags(raw)
                        if (v.isNotBlank() && v != variant) return v
                    }
                }

                // Pattern 4: Input field with matching name/id/placeholder
                val patternInput = Pattern.compile("(?i)(?:name|id|placeholder)=[\"']?${quoted}[\"']?[^>]*value=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                val matcherInput = patternInput.matcher(html)
                if (matcherInput.find()) {
                    val raw = matcherInput.group(1)
                    if (raw != null) {
                        val v = stripHtmlTags(raw)
                        if (v.isNotBlank()) return v
                    }
                }
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun stripHtmlTags(str: String): String {
        val noScript = str.replace(Regex("(?i)<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), " ")
            .replace(Regex("(?i)<!--[\\s\\S]*?-->"), " ")
        val clean = noScript.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
        return com.example.util.CsvExcelUtils.fixArabicMojibake(clean)
    }
}
