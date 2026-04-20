# SwaggerLog

一个功能强大的 OkHttp 日志拦截器库，支持 Swagger 文档集成与 JSON 反混淆，让网络调试更高效。

## ✨ 核心功能

- 📝 **格式化日志** — 请求与响应日志格式化输出，支持自动换行分段，避免 logcat 截断
- 🔍 **JSON 反混淆** — 基于 Swagger `definitions` 中的 `description` 映射规则，自动还原被混淆的字段名
- 📚 **Swagger 集成** — 自动拉取并缓存 Swagger 文档（`v2/api-docs`），在日志中展示接口摘要与在线文档链接
- 💾 **文档缓存** — 本地文件 + 内存双级缓存，默认 24h TTL，后台静默刷新
- 📁 **大文件保护** — 响应体超过 1MB 时自动降级，避免 OOM
- 🎨 **自定义日志** — 通过 lambda 注入任意日志框架（`Logcat`、`Timber`、`Logger` 等）

## 📥 安装

### 1. 添加 JitPack 仓库

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        // ... 其他仓库
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. 引入依赖

**推荐：仅在 Debug 环境使用**

```kotlin
debugImplementation("com.github.cdAhmad:swaggerlog:1.2.0")
```

**全环境使用（不推荐）**

```kotlin
implementation("com.github.cdAhmad:swaggerlog:1.2.0")
```

## 🚀 快速开始

### 方式一：扩展函数（推荐）

```kotlin
import com.cdahmad.swaggerlog.addSwaggerLoggingInterceptor

val okHttpClient = OkHttpClient.Builder()
    .addSwaggerLoggingInterceptor(
        filter = true,                               // 是否过滤未在 Swagger 中声明的字段
        format = true,                               // 是否格式化（pretty print）JSON
        url = "https://api.example.com",             // 基础 URL，Swagger 文档将从 ${url}v2/api-docs 拉取
        cacheFile = { File(context.cacheDir, "swagger_cache") }, // 缓存目录
        log = { level, tag, msg -> Log.d(tag, msg) } // 日志输出回调：0=Debug, 1=Error
    )
    .build()
```

### 方式二：直接使用拦截器

```kotlin
import com.cdahmad.swaggerlog.SwaggerLoggingInterceptor

val interceptor = SwaggerLoggingInterceptor(
    baseUrl = "https://api.example.com",
    swaggerDocUrl = "https://api.example.com/v2/api-docs",
    deobfus = true,        // 启用 JSON 反混淆
    filter = true,         // 启用字段过滤（仅保留 code/msg/data + Swagger 中声明的字段）
    format = true,         // 启用格式化输出
    cacheFile = { File(context.cacheDir, "swagger_cache") },
    log = { level, tag, msg ->
        when (level) {
            0 -> Log.d(tag, msg)
            1 -> Log.e(tag, msg)
        }
    }
)

val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(interceptor)
    .build()
```

### 方式三：Debug / Release 环境隔离（最佳实践）

利用 Gradle 的 `src/debug` 与 `src/release` 源码集，让拦截器仅在 Debug 环境生效：

**`app/src/debug/kotlin/LogHelper.kt`**

```kotlin
import com.cdahmad.swaggerlog.SwaggerLoggingInterceptor
import okhttp3.Interceptor
import java.io.File

object LogHelper {
    fun getInterceptor(
        apiUrl: String,
        format: Boolean,
        cacheFile: () -> File,
        log: (level: Int, tag: String, msg: String) -> Unit
    ): Interceptor? = SwaggerLoggingInterceptor(
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

**`app/src/release/kotlin/LogHelper.kt`**

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

> 若有其他 build type（如 `staging`、`beta`），在对应源码集下放置与 Release 相同内容的 `LogHelper.kt` 即可。

**统一使用**

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

## 📐 API 说明

### `SwaggerLoggingInterceptor`

| 参数 | 类型 | 说明 |
|---|---|---|
| `baseUrl` | `String` | 基础 URL，用于拼接 Swagger 文档路径与在线文档链接 |
| `swaggerDocUrl` | `String` | 完整的 Swagger `v2/api-docs` 地址 |
| `deobfus` | `Boolean` | 是否根据 Swagger `definitions` 中的 `description` 规则反混淆 JSON 字段名 |
| `filter` | `Boolean` | 是否过滤未在 Swagger 映射表中声明的字段（始终保留 `code` / `msg` / `data`） |
| `format` | `Boolean` | 是否对 JSON 进行 pretty print 格式化 |
| `cacheFile` | `() -> File?` | 返回缓存目录的 lambda，用于本地持久化 Swagger 文档 |
| `log` | `(Int, String, String) -> Unit` | 日志回调，`level`：0=Debug / 1=Error，`tag`：日志标签，`msg`：内容 |

### `OkHttpClient.Builder.addSwaggerLoggingInterceptor`

扩展函数，内部封装了 `SwaggerLoggingInterceptor` 的创建与添加，参数含义同上（`url` 对应 `baseUrl`，会自动拼接 `v2/api-docs`）。

## 📁 项目结构

```
swaggerlog/
├── swaglog/                           # 核心拦截器模块
│   └── src/main/java/com/cdahmad/swaggerlog/
│       └── SwaggerLoggingInterceptor.kt
├── build.gradle.kts                   # 根项目构建配置
├── settings.gradle.kts                # 项目设置
├── gradle/libs.versions.toml          # 版本目录
└── jitpack.yml                        # JitPack 发布配置
```

## 🛠 技术栈

- **Kotlin** — 主要开发语言
- **OkHttp** — 网络请求与拦截
- **Gson** — JSON 解析与格式化
- **Kotlin Coroutines** — 后台异步刷新 Swagger 文档

## 📄 许可证

[MIT License](LICENSE)

## 🤝 贡献

欢迎提交 Issue 和 Pull Request 来帮助改进这个项目！

## 📧 联系

- **GitHub 仓库**：[cdAhmad/SwaggerLog](https://github.com/cdAhmad/SwaggerLog)
- **问题反馈**：[GitHub Issues](https://github.com/cdAhmad/SwaggerLog/issues)

---

**SwaggerLog** — 让网络调试更高效！ 🚀
