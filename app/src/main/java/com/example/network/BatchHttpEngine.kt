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
            val bodyString = com.example.util.CsvExcelUtils.decodeBytesAutoEncoding(responseBytes)
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
        val fieldsList = expectedFieldsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val cleanHtml = bodyString
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
                        resultMap[field] = valNested
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
                            resultMap[field] = valNested
                        }
                    }
                    if (resultMap.isNotEmpty()) return resultMap
                }
            }
        } catch (ignored: Exception) {}

        // 2. Parse HTML Table / Key-Value text
        fieldsList.forEach { field ->
            val value = extractFieldFromHtmlOrText(cleanHtml, field)
            if (value != null && value.isNotBlank()) {
                resultMap[field] = value
            }
        }

        // 3. Auto-detect common Arabic student labels if missing
        val autoLabels = listOf("اسم الطالب", "الاسم", "النتيجة", "المعدل", "الصف", "المدرسة", "رقم الجلوس")
        autoLabels.forEach { label ->
            if (!resultMap.containsKey(label)) {
                val foundVal = extractFieldFromHtmlOrText(cleanHtml, label)
                if (foundVal != null && foundVal.isNotBlank()) {
                    resultMap[label] = foundVal
                }
            }
        }

        // 4. Default fallback if no field matched: add clean text summary
        if (resultMap.isEmpty()) {
            val cleanText = cleanHtml.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
            val summary = if (cleanText.length > 150) cleanText.substring(0, 150) + "..." else cleanText
            resultMap["الملخص"] = if (summary.isNotBlank()) summary else "تمت الاستجابة بنجاح"
        }

        return resultMap
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
        try {
            val quoted = Pattern.quote(fieldName)

            // Pattern 1: <td>Label</td><td>Value</td> or <th>Label</th><td>Value</td>
            val patternTd = Pattern.compile("(?i)(?:<td>|<th>|<span>|<div>|<label>)\\s*${quoted}\\s*[:\\?]?\\s*(?:</[^>]+>)*\\s*(?:<td[^>]*>|<span[^>]*>|<div[^>]*>|<p[^>]*>|<b[^>]*>)\\s*([^<]+)\\s*</", Pattern.CASE_INSENSITIVE)
            val matcherTd = patternTd.matcher(html)
            if (matcherTd.find()) {
                val v = matcherTd.group(1)?.trim()
                if (!v.isNull_or_blank()) return stripHtmlTags(v!!)
            }

            // Pattern 2: Label: Value in plain text or tags
            val patternKv = Pattern.compile("(?i)${quoted}\\s*[:=]\\s*[\"']?([^\"'<>\\n\\r]{1,100})[\"']?", Pattern.CASE_INSENSITIVE)
            val matcherKv = patternKv.matcher(html)
            if (matcherKv.find()) {
                val v = matcherKv.group(1)?.trim()
                if (!v.isNull_or_blank()) return stripHtmlTags(v!!)
            }

            // Pattern 3: Input field with matching name/id/placeholder
            val patternInput = Pattern.compile("(?i)(?:name|id|placeholder)=[\"']?${quoted}[\"']?[^>]*value=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
            val matcherInput = patternInput.matcher(html)
            if (matcherInput.find()) {
                val v = matcherInput.group(1)?.trim()
                if (!v.isNull_or_blank()) return stripHtmlTags(v!!)
            }
        } catch (ignored: Exception) {}
        return null
    }

    private fun stripHtmlTags(str: String): String {
        return str.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
    }
}
