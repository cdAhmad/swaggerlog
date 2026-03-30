package com.cdahmad.swiggerlog

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.lang.reflect.Parameter
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

/**
 * 自定义 OkHttp 日志拦截器，支持格式化输出和反混淆 JSON。
 * @param netLogSwitch 是否开启日志记录
 * @param format 是否格式化输出
 * @param url 基础 URL，用于获取 Swagger 文档
 * @param cacheFile 文件工厂，用于获取缓存文件
 * @param log 日志记录函数，用于记录请求和响应
 * */

fun OkHttpClient.Builder.addSwiggerLoggingInterceptor(
    filter: Boolean,
    format: Boolean = false,
    url: String, cacheFile: () -> File?,
    log: (Int, String, String) -> Unit
): OkHttpClient.Builder {
    addInterceptor(
        SwiggerLoggingInterceptor(
            url,
            "${url}v2/api-docs",
            true,
            filter,
            format,
            cacheFile,
            log
        )
    )
    return this
}

 /**
  * @param baseUrl 基础 URL，用于获取 Swagger 文档
  * @param swaggerDocUrl Swagger 文档 URL
  * @param deobfus 是否反混淆 JSON
  * @param filter 是否过滤请求和响应
  * @param format 是否格式化输出
  * @param cacheFile 文件工厂，用于获取缓存文件
  * @param log 日志记录函数，用于记录请求和响应
  * */
class SwiggerLoggingInterceptor @JvmOverloads constructor(
    val baseUrl: String,
    val swaggerDocUrl: String,
    val deobfus: Boolean,
    val filter: Boolean,
    val format: Boolean = false,
    val cacheFile: () -> File?,
    val log: (Int, String, String) -> Unit
) : Interceptor {
    companion object {
        private val UTF8 = Charset.forName("UTF-8")
        val tag = "OkHttp"

        private var id = AtomicInteger(0)


    }

    private var swaggerDocCache: SwaggerDocCache? = null

    private fun getSwaggerDocCache(): SwaggerDocCache? {
        if (swaggerDocCache != null) return swaggerDocCache

        swaggerDocCache = SwaggerDocCache(cacheFile, log, swaggerDocUrl).also {
            // 首次访问时触发后台加载（如果有网络权限）
            it.ensureFresh()
        }
        return swaggerDocCache
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val currentId = id.getAndIncrement()
        // 生成唯一自增id

        val requestId = String.format("%06d", currentId)
        val currentTag = "${tag}_$requestId"
        // ✅ 触发缓存检查 & 后台刷新（幂等、线程安全）
        getSwaggerDocCache()?.ensureFresh()
        // === 尝试获取 Swagger 摘要（仅内存读取，绝不阻塞）===
        var swaggerSummary: String? = null
        val path = request.url.encodedPath
        val method = request.method.lowercase()

        // 只读访问，无网络、无锁、无 suspend
        val swaggerDoc = getSwaggerDocCache()?.getCachedDoc()
        swaggerSummary =
            swaggerDoc?.paths?.get(path)?.get(method)?.richSummary(baseUrl)
        // === 安全获取请求体摘要 ===
        var requestBodySummary = ""
        try {
            request.body?.let { body ->
                val contentType = body.contentType()
                val mediaType = contentType?.toString()?.lowercase() ?: ""

                if (mediaType.startsWith("application/json")) {
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    val charset = contentType?.charset(UTF8) ?: UTF8
                    requestBodySummary = buffer.readString(charset)
                } else {
                    when {
                        body is FormBody -> requestBodySummary =
                            "<Form Body: ${body.size} fields>"

                        body is MultipartBody -> requestBodySummary =
                            "<Multipart Body: ${body.parts.size} parts>"

                        mediaType.startsWith("text/") -> requestBodySummary =
                            "<Text Body: $mediaType>"

                        else -> requestBodySummary = "<Binary/Stream Body: $mediaType>"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            log(1, currentTag, "-> Failed to inspect request body")
            requestBodySummary = "<Request body inspection failed>"
        }

        // 只记录请求URL
        log(0, currentTag, "-> ${request.method} ${request.url}")

        val response = chain.proceed(request)
        val duration = System.currentTimeMillis() - startTime
        val msgList = mutableListOf<String>()

        // 记录响应信息
        try {
            val responseBody = response.body
            var bodyString = ""

            if (responseBody != null) {
                val source = responseBody.source()
                source.request(Long.MAX_VALUE) // 请求完整内容
                val buffer = source.buffer()

                val charset = responseBody.contentType()?.charset(UTF8) ?: UTF8
                // 可选：限制 body 大小
                if (buffer.size > 1_000_000) { // 1MB
                    bodyString = "<Response body too large (${buffer.size} bytes)>"
                } else {
                    bodyString = buffer.clone().readString(charset)
                }
            }
            //返回信息描述
            msgList.add("<- ${request.method} ${request.url} ${response.code} ($duration ms) ${response.message}")

            //接口描述
            msgList.add("<- Swagger: $swaggerSummary")
            //debug 打印请求头
            val jsonObject = JsonObject()
            request.headers.forEach {
                if (it.second.isEmpty()) {
                    null
                } else {
                    jsonObject.add(it.first, JsonPrimitive(it.second))
                }
            }.let {
                msgList.add("->Request Header:")
                msgList.add(jsonObject.toString())
            }
            // 打印请求体
            if (requestBodySummary.startsWith("<")) {
                msgList.add("-> Request Body: $requestBodySummary")
            } else if (requestBodySummary.isEmpty()) {

                msgList.add("-> Request Body: null")
            } else {
                msgList.add("-> Request Body:")
                msgList.add(requestBodySummary)
                if (deobfus) {
                    swaggerDoc?.tripeDesc()?.takeIf {
                        it.isNotEmpty()
                    }?.let {
                        val deobfuscatedJson =
                            ObfuscateHelper.deobfuscateJson(requestBodySummary, it, format, filter)
                        msgList.add("-> Request Body: (Deobfuscated):")
                        if (format) {
                            deobfuscatedJson.jsonFormatString().forEach {
                                msgList.add(it)
                            }
                        } else {
                            msgList.add(deobfuscatedJson)
                        }


                    }
                }
            }


            // 打印返回
            if (bodyString.isNotEmpty()) {
                msgList.add("<- Response Body:")
                msgList.add(bodyString)
                if (deobfus) {
                    swaggerDoc?.tripeDesc()?.takeIf {
                        it.isNotEmpty()
                    }?.let {
                        val deobfuscatedJson =
                            ObfuscateHelper.deobfuscateJson(bodyString, it, format, filter)
                        msgList.add("<- Response Body (Deobfuscated):")
                        if (format) {
                            deobfuscatedJson.jsonFormatString().forEach {
                                msgList.add(it)
                            }
                        } else {
                            msgList.add(deobfuscatedJson)
                        }
                    }
                }
            }
            msgList.forEach {
                printLongLog(it, currentTag)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            log(1, currentTag, "<- Error reading response body")
        }

        return response
    }

    val LOG_MAX_LENGTH = 3000
    private fun String.jsonFormatString(): List<String> {
        val msgList = mutableListOf<String>()
        val lines = split("\n")

        var currentChunk = StringBuilder()

        for (line in lines) {
            // 如果加上新行会超限，则先打印当前 chunk
            if (currentChunk.length + line.length + 1 > LOG_MAX_LENGTH) {
                if (currentChunk.isNotEmpty()) {
                    msgList.add(currentChunk.toString())
                    currentChunk.clear()
                }
                // 如果单行就超长，强制分段（按字符）
                if (line.length > LOG_MAX_LENGTH) {
                    line.chunked(LOG_MAX_LENGTH).forEach { part ->
                        msgList.add(part)
                    }
                    continue
                }
            }
            currentChunk.append(line).append("\n")
        }

        // 打印最后一块
        if (currentChunk.isNotEmpty()) {
            msgList.add(currentChunk.toString())
        }

        return msgList
    }

    private fun printLongLog(msg: String, tag: String) {

        if (msg.length <= LOG_MAX_LENGTH) {
            log(0, tag, msg)
        } else {
            msg.chunked(LOG_MAX_LENGTH).forEachIndexed { index, chunk ->
                log(0, tag, chunk)
            }
        }
    }


}

// =============================================
// 🔒 私有缓存管理器：内存 + 文件 + TTL + 后台刷新
// =============================================
private class SwaggerDocCache(
    val cacheFile: () -> File?,
    val log: (Int, String, String) -> Unit,
    val swaggerDocUrl: String,
    val ttlHours: Int = 24
) {

    // 安全 fallback：避免 tag 为 null 导致崩溃
    private val baseTag = SwiggerLoggingInterceptor.tag ?: "DefaultTag"
    val tag = "${baseTag}_SwaggerDocCache"

    private val CACHE_FILE_NAME = "swagger_v2_api_docs.json"

    // ❗ 修正注释：实际是 24 小时，不是 1 小时
    private val TTL_MILLIS = ttlHours.hours.inWholeMilliseconds // 24 小时过期

    @Volatile
    private var memoryCache: Pair<SwaggerDoc, Long>? = null // (doc, saveTime)

    private val gson = Gson()

    // ✅ 安全：每次调用时才访问 context.cacheDir
    private fun getCacheFile(): File? {
        return cacheFile()?.let { File(it, CACHE_FILE_NAME) }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRefreshing = AtomicBoolean(false)

    // ✅ 外部调用：确保缓存是“新鲜”的（可能触发后台刷新）
    fun ensureFresh() {
        val now = System.currentTimeMillis()
        val cached = getFromMemoryOrDisk()

        if (cached == null) {
            // 无缓存 → 首次加载
            refreshInBackground(force = false)
            return
        }

        val (doc, savedAt) = cached
        memoryCache = doc to savedAt // 即使过期也更新内存（避免后续重复读磁盘）

        if (now - savedAt > TTL_MILLIS) {
            refreshInBackground(force = true)
        }
    }

    // ✅ 安全读取当前可用的缓存（优先内存，其次文件）
    fun getCachedDoc(): SwaggerDoc? = memoryCache?.first ?: readFromFile()?.first

    // -------------------------------
    // 内部方法
    // -------------------------------

    private fun getFromMemoryOrDisk(): Pair<SwaggerDoc, Long>? {
        return memoryCache ?: readFromFile()
    }

    private fun readFromFile(): Pair<SwaggerDoc, Long>? {
        return try {
            val cacheFile = getCacheFile() ?: return null // ← 无 Context 则无文件缓存
            if (!cacheFile.exists()) return null
            val json = cacheFile.readText(Charsets.UTF_8)
            if (json.isBlank()) {
                cacheFile.delete()
                return null
            }

            val wrapper = gson.fromJson(json, SwaggerCacheWrapper::class.java)
            if (wrapper.timestamp <= 0 || wrapper.swaggerDoc == null) {
                log(1, tag, "Invalid cache content: timestamp=${wrapper.timestamp}, doc=null")
                cacheFile.delete()
                return null
            }

            wrapper.swaggerDoc to wrapper.timestamp
        } catch (e: Exception) {
            e.printStackTrace()
            log(1, tag, "Failed to parse cache file, deleting it")
            getCacheFile()?.delete() // ← 修改这里
            null
        }
    }

    /**
     * 后台刷新 Swagger 文档
     * @param force 是否强制刷新（即使正在刷新中，也可考虑取消旧任务——此处简化为忽略）
     */
    private fun refreshInBackground(force: Boolean = false) {
        // 防止并发刷新（除非强制，但这里暂不支持取消旧任务）
        if (!force && isRefreshing.getAndSet(true)) return

        scope.launch {
            var response: Response? = null
            var newDoc: SwaggerDoc? = null
            var success = false
            try {
                log(0, tag, "Refreshing Swagger doc from network")
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(swaggerDocUrl)
                    .build()

                log(0, tag, "Request URL: ${request.url}")
                response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (bodyString.isNullOrBlank()) {
                        log(1, tag, "Received empty or blank response body")
                    } else {
                        newDoc = gson.fromJson(bodyString, SwaggerDoc::class.java)
                        if (newDoc != null) {
                            val now = System.currentTimeMillis()
                            // 更新内存缓存（始终执行）
                            memoryCache = newDoc to now

                            // 仅当有 Context 时才尝试写入磁盘
                            cacheFile()?.let { ctx ->
                                val wrapper =
                                    SwaggerCacheWrapper(timestamp = now, swaggerDoc = newDoc)
                                val tempFile = File(ctx, "${CACHE_FILE_NAME}.tmp")
                                try {
                                    tempFile.writeText(gson.toJson(wrapper), Charsets.UTF_8)
                                    if (tempFile.renameTo(getCacheFile()!!)) {
                                        log(
                                            0,
                                            tag,
                                            "Successfully refreshed and cached Swagger doc to disk"
                                        )
                                    } else {
                                        log(1, tag, "Failed to rename temp cache file")
                                        tempFile.delete()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    log(1, tag, "Failed to write cache file")
                                    tempFile.delete()
                                }
                            }
                        } else {
                            log(1, tag, "Parsed SwaggerDoc is null")
                        }
                    }
                } else {
                    log(1, tag, "HTTP request failed: ${response.code} ${response.message}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                log(1, tag, "Exception during Swagger doc refresh")
            } finally {
                isRefreshing.set(false)
                response?.close()

                // 如果刷新失败，且内存中没有有效缓存，可考虑保留旧磁盘缓存（已由 getCachedDoc 自动处理）
                // 此处无需额外操作
            }
        }
    }

    // 👇 缓存包装类（必须 public 或 internal 才能被 Gson 反序列化？建议加 @Keep 或使用 TypeAdapter）
    // 但若在 object 内部，Gson 默认可通过反射访问，通常没问题
    private data class SwaggerCacheWrapper(
        val timestamp: Long,
        val swaggerDoc: SwaggerDoc
    )
}

// 数据类（保持不变）
data class SwaggerDoc(
    val swagger: String,
    val paths: Map<String, Map<String, Operation>>,
    val definitions: Map<String, Definition>? = null,
) {
    private var descTripeMap: MutableMap<String, Pair<String, String>>? = null

    fun tripeDesc(): Map<String, Pair<String, String>> {
        if (descTripeMap == null) {
            val map = mutableMapOf<String, Pair<String, String>>()
            definitions?.forEach { (_, modelSchema) ->
                modelSchema.properties?.forEach { (propName, propSchema) ->

                    val originKey = propSchema.desc()
                    if (originKey != null) {
                        if (propName !in listOf("code", "msg", "data")) {
                            map[propName] = originKey to (propSchema.description ?: "")
                        }
                    }
                }
            }
            descTripeMap = map
        }
        return descTripeMap!! // 安全：上面已初始化
    }

    data class Definition(val type: String, val properties: Map<String, Property>? = null) {
        data class Property(
            val type: String?,
            val description: String?,
        ) {
            fun desc(): String? {
                return description?.split(":")?.get(0)?.trim()
            }
        }
    }

    data class Operation(
        val tags: List<String>,
        val summary: String,
        val description: String,
        val operationId: String,
        val consumes: List<String>,
        val produces: List<String>,
        val parameters: List<Parameter>,
        val deprecated: Boolean
    ) {

        fun richSummary(baseUrl: String): String {
            val paths = tags.joinToString("/")
            val url = "${baseUrl}doc.html#/default/$paths/$operationId"
            val uri = URI(url)
            return "$summary ${uri.toASCIIString()} "
        }

    }
}


private object ObfuscateHelper {

    // 白名单：始终保留的字段（反混淆后的名字）
    private val WHITELISTED_KEYS = setOf("data", "code", "msg")

    /**
     * 反混淆 JSON 并可选过滤字段
     *
     * @param obfuscatedJson 混淆后的 JSON 字符串
     * @param keyMapping 映射表：obfuscatedKey -> (originalKey, description)
     * @param format 是否格式化输出（pretty print）
     * @param filter 是否启用过滤：
     *               - true: 仅保留 data/code/msg + mapping 中存在的字段
     *               - false: 输出所有字段（仅反混淆）
     */
    fun deobfuscateJson(
        obfuscatedJson: String,
        keyMapping: Map<String, Pair<String, String>>,
        format: Boolean,
        filter: Boolean
    ): String {
        val reader = JsonReader(StringReader(obfuscatedJson))
        val writer = StringWriter()
        val jsonWriter = JsonWriter(writer).apply {
            setSerializeNulls(true)
        }

        try {
            process(reader, jsonWriter, keyMapping, filter)
        } finally {
            reader.close()
            jsonWriter.close()
        }

        val rawResult = writer.toString()

        return if (format) {
            try {
                val gson = Gson()
                val prettyGson = GsonBuilder().setPrettyPrinting().create()
                val element: JsonElement = gson.fromJson(rawResult, JsonElement::class.java)
                prettyGson.toJson(element)
            } catch (e: Exception) {
                // 格式化失败则返回原始结果
                rawResult
            }
        } else {
            rawResult
        }
    }

    private fun process(
        reader: JsonReader,
        writer: JsonWriter,
        mapping: Map<String, Pair<String, String>>,
        filter: Boolean
    ) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                writer.beginObject()
                while (reader.hasNext()) {
                    val obfuscatedName = reader.nextName()
                    val originalName = mapping[obfuscatedName]?.first ?: obfuscatedName

                    // ✅ 关键过滤逻辑
                    if (filter) {
                        val isInWhitelist = originalName in WHITELISTED_KEYS
                        val isInMapping = mapping.containsKey(obfuscatedName)

                        if (!isInWhitelist && !isInMapping) {
                            reader.skipValue()
                            continue
                        }
                    }

                    writer.name(originalName)
                    process(reader, writer, mapping, filter)
                }
                reader.endObject()
                writer.endObject()
            }

            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                writer.beginArray()
                while (reader.hasNext()) {
                    process(reader, writer, mapping, filter)
                }
                reader.endArray()
                writer.endArray()
            }

            JsonToken.STRING -> {
                writer.value(reader.nextString())
            }

            JsonToken.NUMBER -> {
                val numStr = reader.nextString()
                writer.value(numStr)
            }

            JsonToken.BOOLEAN -> {
                writer.value(reader.nextBoolean())
            }

            JsonToken.NULL -> {
                reader.nextNull()
                writer.nullValue()
            }

            else -> {
                reader.skipValue()
            }
        }
    }
}