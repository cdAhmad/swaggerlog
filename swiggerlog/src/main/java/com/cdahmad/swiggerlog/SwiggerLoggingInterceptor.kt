package com.cdahmad.swiggerlog


import android.app.Application
import android.util.Log
import com.google.gson.Gson
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours


// 自定义线程安全的日志拦截器
class SwiggerLoggingInterceptor(
    application: Application,
    val baseUrl: String,
    swaggerDocUrl: String,
    val deobfus: Boolean
) : Interceptor {
    companion object {
        private val UTF8 = Charset.forName("UTF-8")
        val tag = "OkHttp"

        private var id = AtomicInteger(0)

        fun OkHttpClient.Builder.addSwiggerLoggingInterceptor(
            netLogSwitch: Boolean,
            application: Application,
            url: String
        ): OkHttpClient.Builder {
            if (netLogSwitch.not()) {
                return this
            }
            addInterceptor(
                SwiggerLoggingInterceptor(
                    application,
                    url,
                    "${url}v2/api-docs",
                    true
                )
            )
            return this
        }

    }

    private val swaggerDocCache = SwaggerDocCache(application, swaggerDocUrl)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val currentId = id.getAndIncrement()
        // 生成唯一自增id

        val requestId = String.format("%06d", currentId)
        val currentTag = "${tag}_$requestId"
        // ✅ 触发缓存检查 & 后台刷新（幂等、线程安全）
        swaggerDocCache.ensureFresh()
        // === 尝试获取 Swagger 摘要（仅内存读取，绝不阻塞）===
        var swaggerSummary: String? = null
        val path = request.url.encodedPath
        val method = request.method.lowercase()

        // 只读访问，无网络、无锁、无 suspend
        val swaggerDoc = swaggerDocCache.getCachedDoc()
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
            Log.w(currentTag, "-> Failed to inspect request body", e)
            requestBodySummary = "<Request body inspection failed>"
        }

        // 只记录请求URL
        Log.d(currentTag, "-> ${request.method} ${request.url}")

        val response = chain.proceed(request)
        val duration = System.currentTimeMillis() - startTime

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
            // 分段打印长日志以避免logcat截断
            printLongLog(
                "<- ${request.method} ${request.url} ${response.code} ($duration ms) ${response.message}",
                currentTag
            )
            printLongLog("<- Swagger: $swaggerSummary", currentTag)
            //debug 打印请求头
            val jsonObject = JsonObject()
            request.headers.forEach {
                if (it.second.isEmpty()) {
                    null
                } else {
                    jsonObject.add(it.first, JsonPrimitive(it.second))
                }
            }.let {
                printLongLog("->Request Header:", currentTag)
                printLongLog(jsonObject.toString(), currentTag)
            }
            // 打印请求体
            if (requestBodySummary.startsWith("<")) {
                printLongLog("-> Request Body: $requestBodySummary", currentTag)
            } else if (requestBodySummary.isEmpty()) {
                printLongLog("-> Request Body: null", currentTag)
            } else {
                printLongLog("-> Request Body:", currentTag)
                printLongLog(requestBodySummary, currentTag)
                if (deobfus) {
                    swaggerDoc?.tripeDesc()?.takeIf {
                        it.isNotEmpty()
                    }?.let {
                        val deobfuscatedJson =
                            ObfuscateHelper.deobfuscateJson(requestBodySummary, it)
                        printLongLog("-> Request Body: (Deobfuscated):", currentTag)
                        printLongLog(deobfuscatedJson, currentTag)
                    }
                }
            }


            // 打印返回
            if (bodyString.isNotEmpty()) {
                printLongLog("<- Response Body", currentTag)
                printLongLog(bodyString, currentTag)
                if (deobfus) {
                    swaggerDoc?.tripeDesc()?.takeIf {
                        it.isNotEmpty()
                    }?.let {
                        val deobfuscatedJson = ObfuscateHelper.deobfuscateJson(bodyString, it)
                        printLongLog("<- Response Body (Deobfuscated):", currentTag)
                        printLongLog(deobfuscatedJson, currentTag)
                    }

                }

            }
        } catch (e: Exception) {
            Log.w(currentTag, "<- Error reading response body", e)
        }

        return response
    }

    private fun printLongLog(message: String, tag: String) {
        // 处理logcat截断问题，分段打印长消息
        val maxLength = 3000
        var index = 0
        while (index < message.length) {
            val endIndex = (index + maxLength).coerceAtMost(message.length)
            val subMessage = message.substring(index, endIndex)
            Log.d(tag, subMessage)
            index += maxLength
        }
    }


}

// =============================================
// 🔒 私有缓存管理器：内存 + 文件 + TTL + 后台刷新
// =============================================
class SwaggerDocCache(
    val context: Application,
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
    private fun getCacheFile(): File {
        return File(context.cacheDir, CACHE_FILE_NAME)
    }


    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)

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
            val cacheFile = getCacheFile() // ← 修改这里
            if (!cacheFile.exists()) return null
            val json = cacheFile.readText(Charsets.UTF_8)
            if (json.isBlank()) {
                cacheFile.delete()
                return null
            }

            val wrapper = gson.fromJson(json, SwaggerCacheWrapper::class.java)
            if (wrapper.timestamp <= 0 || wrapper.swaggerDoc == null) {
                Log.w(tag, "Invalid cache content: timestamp=${wrapper.timestamp}, doc=null")
                cacheFile.delete()
                return null
            }

            wrapper.swaggerDoc to wrapper.timestamp
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse cache file, deleting it", e)
            getCacheFile().delete() // ← 修改这里
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
                Log.d(tag, "Refreshing Swagger doc from network")
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(swaggerDocUrl)
                    .build()

                Log.d(tag, "Request URL: ${request.url}")
                response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (bodyString.isNullOrBlank()) {
                        Log.w(tag, "Received empty or blank response body")
                    } else {
                        newDoc = gson.fromJson(bodyString, SwaggerDoc::class.java)
                        if (newDoc != null) {
                            val now = System.currentTimeMillis()
                            val wrapper = SwaggerCacheWrapper(timestamp = now, swaggerDoc = newDoc)

                            // 使用临时文件保证原子写入
                            val tempFile = File(context.cacheDir, "${CACHE_FILE_NAME}.tmp")
                            tempFile.writeText(gson.toJson(wrapper), Charsets.UTF_8)
                            // 注意：renameTo 在部分 Android 设备上可能失败（跨分区），但 cacheDir 内通常安全
                            if (tempFile.renameTo(getCacheFile())) {
                                memoryCache = newDoc to now
                                success = true
                                Log.d(tag, "Successfully refreshed and cached Swagger doc")
                            } else {
                                Log.e(tag, "Failed to rename temp cache file")
                                tempFile.delete() // 清理残留
                            }
                        } else {
                            Log.w(tag, "Parsed SwaggerDoc is null")
                        }
                    }
                } else {
                    Log.w(tag, "HTTP request failed: ${response.code} ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception during Swagger doc refresh", e)
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

    fun deobfuscateJson(
        obfuscatedJson: String,
        keyMapping: Map<String, Pair<String, String>>
    ): String {
        val reader = JsonReader(StringReader(obfuscatedJson))
        val writer = StringWriter()
        val jsonWriter = JsonWriter(writer).apply { setSerializeNulls(true) }
        process(reader, jsonWriter, keyMapping)
        return writer.toString()
    }

    private fun process(
        reader: JsonReader,
        writer: JsonWriter,
        mapping: Map<String, Pair<String, String>>
    ) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                writer.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val originalName = mapping[name]?.first ?: name
                    writer.name(originalName)
                    process(reader, writer, mapping)
                }
                reader.endObject()
                writer.endObject()
            }

            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                writer.beginArray()
                while (reader.hasNext()) {
                    process(reader, writer, mapping)
                }
                reader.endArray()
                writer.endArray()
            }

            JsonToken.STRING -> writer.value(reader.nextString())
            JsonToken.NUMBER -> writer.value(reader.nextString().toBigDecimalOrNull()) // 保持精度
            JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                writer.nullValue()
            }

            else -> reader.skipValue()
        }
    }
}