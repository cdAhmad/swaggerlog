# SwaggerLog

一个功能强大的Android开发库，提供OkHttp日志拦截器，简化网络调试。

## 📦 模块介绍

### 1. swaglog - OkHttp日志拦截器

专为Android开发设计的OkHttp日志拦截器，支持Swagger文档集成和JSON反混淆，让网络调试更高效。

#### ✨ 核心功能
- 📝 **格式化日志**：请求和响应日志格式化输出，便于阅读
- 🔍 **JSON反混淆**：自动反混淆JSON数据，还原真实数据结构
- 📚 **Swagger集成**：自动获取并缓存Swagger文档，结合文档展示请求信息
- 💾 **文档缓存**：本地缓存Swagger文档，减少网络请求
- 📁 **大文件支持**：大文件日志自动分段处理，避免内存溢出
- 🎨 **自定义日志**：支持自定义日志记录方式，适配各种日志框架

#### 📥 安装

**推荐方式：仅在Debug环境使用**
```kotlin
debugImplementation("com.github.cdAhmad:swagger-interceptor:1.1.0")
```

**全局使用（不推荐）**
```kotlin
implementation("com.github.cdAhmad:swagger-interceptor:1.1.0")
```

#### 🚀 使用示例

##### 1. 区分Debug和Release环境（推荐）

**创建LogHelper**

在`app/src/debug/kotlin/`目录下创建Debug版本的LogHelper：
```kotlin
object LogHelper {
    fun getInterceptor(
        apiUrl: String,
        format: Boolean,
        cacheFile: () -> File,
        log: (level: Int, tag: String, msg: String) -> Unit
    ): Interceptor? {
        return SwiggerLoggingInterceptor(
            baseUrl = apiUrl,
            swaggerDocUrl = "${apiUrl}v2/api-docs",
            deobfus = true,
            filter = true,
            format = format,
            cacheFile = cacheFile,
            log = log
        )
    }
}
```

在`app/src/release/java/`目录下创建Release版本的LogHelper：
```kotlin
object LogHelper {
    fun getInterceptor(
        apiUrl: String,
        format: Boolean,
        cacheFile: () -> File,
        log: (level: Int, tag: String, msg: String) -> Unit
    ): Interceptor? {
       return null  // Release环境下不返回拦截器
    }
}
```

**使用LogHelper**
```
val okHttpClient = OkHttpClient.Builder()
    .apply {
        // 仅在Debug环境下添加拦截器
        LogHelper.getInterceptor(
            apiUrl = "https://api.example.com",
            format = true,
            cacheFile = { File(context.cacheDir, "swagger.json") },
            log = { level, tag, msg -> Log.d(tag, msg) }
        )?.let { addInterceptor(it) }
    }
    .build()
```

##### 2. 基本用法
```kotlin
val swaggerInterceptor = SwiggerLoggingInterceptor(
    baseUrl = "https://api.example.com",
    swaggerDocUrl = "/v2/api-docs",
    deobfus = true,  // 启用JSON反混淆
    filter = true,   // 启用日志过滤
    format = true    // 启用日志格式化
)
```

##### 3. 自定义配置
```kotlin
val customInterceptor = SwiggerLoggingInterceptor(
    baseUrl = "https://api.example.com",
    swaggerDocUrl = "/swagger.json",
    deobfus = true,
    filter = true,
    format = true,
    cacheFile = { File(context.cacheDir, "swagger.json") },  // 自定义缓存文件路径
    log = { level, tag, msg ->  // 自定义日志记录
        when (level) {
            0 -> Log.d(tag, msg)  // Debug级别
            1 -> Log.e(tag, msg)  // Error级别
        }
    }
)
```

##### 4. 使用扩展函数
```kotlin
val okHttpClient = OkHttpClient.Builder()
    .addSwiggerLoggingInterceptor(
        filter = true,
        format = true,
        url = "https://api.example.com",
        cacheFile = { File(context.cacheDir, "swagger.json") },
        log = { level, tag, msg -> Log.d(tag, msg) }
    )
    .build()
```

## 📁 项目结构

```
SwaggerLog/
├── swaglog/                  # OkHttp日志拦截器
│   ├── src/main/java/com/cdahmad/swiggerlog/
│   │   └── SwiggerLoggingInterceptor.kt
├── app/                      # 示例应用
│   ├── src/
│   │   ├── debug/kotlin/
│   │   │   └── LogHelper.kt  # Debug环境日志工具
│   │   ├── release/java/
│   │   │   └── LogHelper.kt  # Release环境日志工具
├── build.gradle.kts          # 项目构建配置
├── settings.gradle.kts       # 模块配置
└── jitpack.yml               # JitPack发布配置
```

## 🛠️ 技术栈

- **Kotlin** - 主要开发语言
- **OkHttp** - 强大的网络请求库
- **Gson** - 高效的JSON解析库
- **Coroutines** - 优雅的异步编程

## 📄 许可证

[MIT License](LICENSE)

## 🤝 贡献

欢迎提交Issue和Pull Request来帮助改进这个项目！

## 📧 联系方式与资源

**GitHub仓库**：[SwaggerLog](https://github.com/cdAhmad/SwaggerLog)（公开仓库，欢迎Star和Fork）

如有问题或建议，可以通过以下方式联系：
- GitHub Issues: [提交问题](https://github.com/cdAhmad/SwaggerLog/issues)
- GitHub: [cdAhmad](https://github.com/cdAhmad)

---

**SwaggerLog** - 让Android开发更高效！ 🚀