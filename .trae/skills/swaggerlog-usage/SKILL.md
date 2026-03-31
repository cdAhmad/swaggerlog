---
name: "swaggerlog-usage"
description: "提供SwaggerLog库的使用指南和示例代码，包括安装、基本用法、自定义配置和环境区分。当用户询问SwaggerLog的使用方法或需要示例代码时调用。"
---

# SwaggerLog 使用指南

SwaggerLog是一个功能强大的Android OkHttp日志拦截器库，支持Swagger文档集成和JSON反混淆，简化网络调试过程。

**GitHub仓库**：[SwaggerLog](https://github.com/cdAhmad/SwaggerLog)（公开仓库，欢迎Star和Fork）

## 安装方式

### 推荐：仅Debug环境使用
```kotlin
debugImplementation("com.github.cdAhmad:swaggerlog:1.1.1")
```

### 全局使用（不推荐）
```kotlin
implementation("com.github.cdAhmad:swaggerlog:1.1.1")
```

## 基本用法

### 1. 区分Debug和Release环境（推荐）

### 创建Debug版本LogHelper
在`app/src/debug/kotlin/`目录下：
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

### 创建Release版本LogHelper
在`app/src/release/java/`目录下：
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

**注意**：如果项目中有其他buildtype（如staging、beta等），可以复用Release版本的实现，只需在对应的目录结构下创建相同的LogHelper文件即可。例如，为staging环境创建`app/src/staging/java/LogHelper.kt`，内容与Release版本相同。

### 使用LogHelper
```kotlin
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

### 2. 创建基本拦截器
```kotlin
val swaggerInterceptor = SwiggerLoggingInterceptor(
    baseUrl = "https://api.example.com",
    swaggerDocUrl = "/v2/api-docs",
    deobfus = true,  // 启用JSON反混淆
    filter = true,   // 启用日志过滤
    format = true    // 启用日志格式化
)
```

### 3. 自定义配置
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

### 4. 使用扩展函数
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

## 核心功能

- 📝 **格式化日志**：请求和响应日志格式化输出，便于阅读
- 🔍 **JSON反混淆**：自动反混淆JSON数据，还原真实数据结构
- 📚 **Swagger集成**：自动获取并缓存Swagger文档，结合文档展示请求信息
- 💾 **文档缓存**：本地缓存Swagger文档，减少网络请求
- 📁 **大文件支持**：大文件日志自动分段处理，避免内存溢出
- 🎨 **自定义日志**：支持自定义日志记录方式，适配各种日志框架

## 注意事项

1. 建议仅在Debug环境下使用，避免在Release版本中暴露敏感信息
2. 确保正确配置Swagger文档URL，否则Swagger集成功能将无法正常工作
3. 对于大型项目，建议使用LogHelper方式管理拦截器，便于统一控制
4. 首次使用时，拦截器会自动下载Swagger文档，可能需要网络权限