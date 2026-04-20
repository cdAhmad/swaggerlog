---
name: swaggerlog
description: >
  Android/JVM OkHttp 日志拦截器库，支持 Swagger 文档集成、JSON 反混淆与格式化日志输出。
  在以下场景触发：
  (1) 在 Android 或 JVM 项目中集成 SwaggerLog 网络日志拦截器，
  (2) 配置 Swagger 文档自动拉取/缓存、JSON 字段反混淆、日志格式化或过滤，
  (3) 实现 Debug/Release 环境隔离，避免 Release 包携带日志拦截器，
  (4) 修改 SwaggerLoggingInterceptor 源码、调整日志输出格式或缓存策略，
  (5) 处理 Swagger v2 api-docs 字段映射规则、大文件分段、多 build type 适配。
---

# SwaggerLog

纯 Kotlin/JVM 的 OkHttp 日志拦截器，通过 Swagger `v2/api-docs` 自动解析字段映射，实现 JSON 反混淆与格式化输出。

## 快速集成

### 1. 添加 JitPack 仓库与依赖

```kotlin
// settings.gradle.kts
repositories {
    maven { url = uri("https://jitpack.io") }
}

// build.gradle.kts
dependencies {
    debugImplementation("com.github.cdAhmad:swaggerlog:1.2.0")
}
```

### 2. 创建环境隔离的 LogHelper

**Debug** (`app/src/debug/kotlin/LogHelper.kt`)：

```kotlin
import com.cdahmad.swaggerlog.SwaggerLoggingInterceptor
import okhttp3.Interceptor
import java.io.File

object LogHelper {
    fun getInterceptor(
        apiUrl: String,
        format: Boolean = true,
        cacheFile: () -> File,
        log: (level: Int, tag: String, msg: String) -> Unit
    ): Interceptor = SwaggerLoggingInterceptor(
        baseUrl = apiUrl,
        swaggerDocUrl = "${apiUrl}v2/api-docs",
        deobfus = true,
        filter = true,
        format = format,
        cacheFile = cacheFile,
        log = log
    )
}
```

**Release / Staging / Beta** (`app/src/release/kotlin/LogHelper.kt`)：

```kotlin
import okhttp3.Interceptor
import java.io.File

object LogHelper {
    fun getInterceptor(
        apiUrl: String,
        format: Boolean,
        cacheFile: () -> File,
        log: (level: Int, tag: String, msg: String) -> Unit
    ): Interceptor? = null
}
```

### 3. 接入 OkHttpClient

```kotlin
val okHttpClient = OkHttpClient.Builder()
    .apply {
        LogHelper.getInterceptor(
            apiUrl = "https://api.example.com",
            format = true,
            cacheFile = { File(context.cacheDir, "swagger_cache") },
            log = { level, tag, msg -> Log.d(tag, msg) }
        )?.let { addInterceptor(it) }
    }
    .build()
```

## API 参数

### SwaggerLoggingInterceptor

```kotlin
class SwaggerLoggingInterceptor @JvmOverloads constructor(
    val baseUrl: String,                        // API 基础 URL，用于拼接在线文档链接
    val swaggerDocUrl: String,                  // 完整的 Swagger v2/api-docs 地址
    val deobfus: Boolean,                       // 是否根据 Swagger definitions 反混淆字段名
    val filter: Boolean,                        // 是否过滤未映射字段（始终保留 code/msg/data）
    val format: Boolean = false,                // 是否 pretty print 格式化 JSON
    val cacheFile: () -> File?,                 // 返回缓存目录的 lambda
    val log: (Int, String, String) -> Unit      // 日志回调：level(0=Debug,1=Error), tag, msg
) : Interceptor
```

### 扩展函数

```kotlin
fun OkHttpClient.Builder.addSwaggerLoggingInterceptor(
    filter: Boolean,
    format: Boolean = false,
    url: String,                                 // 对应 baseUrl，自动拼接 v2/api-docs
    cacheFile: () -> File?,
    log: (Int, String, String) -> Unit
): OkHttpClient.Builder
```

## 核心机制

### 字段反混淆规则

从 Swagger `definitions` 各属性的 `description` 字段提取映射：

```json
"definitions": {
  "UserVO": {
    "properties": {
      "a": { "type": "string", "description": "name: 用户名" },
      "b": { "type": "integer", "description": "age: 年龄" }
    }
  }
}
```

`description` 以 `:` 分割，第一段为原始字段名。反混淆后输出：

```json
{"name":"张三","age":25}
```

白名单字段 `code`、`msg`、`data` 始终保留，不受 filter 影响。

### 缓存策略

- 内存 + 文件双级缓存，默认 TTL 24 小时
- 首次构造时在后台 IO 协程中静默拉取 Swagger 文档
- 过期后触发后台刷新，读取逻辑永不阻塞主线程
- 缓存文件路径：`{cacheFile()}/swagger_v2_api_docs.json`

### 日志输出格式

```
OkHttp_000001  -> GET https://api.example.com/user/info
OkHttp_000001  <- GET https://api.example.com/user/info 200 (125 ms) OK
OkHttp_000001  <- Swagger: 获取用户信息 https://api.example.com/doc.html#/default/User/getUserInfo
OkHttp_000001  -> Request Header: {...}
OkHttp_000001  -> Request Body: null
OkHttp_000001  <- Response Body: {"a":"张三","b":25}
OkHttp_000001  <- Response Body (Deobfuscated): {"name":"张三","age":25}
```

### 大文件保护

响应体超过 1MB 时输出 `<Response body too large (X bytes)>`，避免 OOM。

## 源码修改要点

- `SwaggerLoggingInterceptor.kt` 是单文件实现，位于 `swaglog/src/main/java/com/cdahmad/swaggerlog/`
- `ObfuscateHelper.deobfuscateJson()` 使用 Gson `JsonReader/JsonWriter` 流式处理，支持嵌套对象与数组
- `SwaggerDocCache` 为私有内部类，通过 `AtomicBoolean` 防止并发刷新，使用 `SupervisorJob` 隔离协程异常
- 日志分段阈值 `LOG_MAX_LENGTH = 3000`，避免 logcat 截断
- 项目发布到 JitPack，由 `jitpack.yml` 指定 `openjdk21`

## 依赖

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.google.code.gson:gson:2.13.2")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
```
