

#  swagger log



#  1.引入项目
```kotlin
implementation("com.github.cdAhmad:swagger-interceptor:1.0.7.6")

```
# 2.添加拦截器
```kotlin
val swaggerInterceptor = SwiggerLoggingInterceptor(
    baseUrl = "https://api.github.com",
      swaggerDocUrl="/v2/api",
          deobfus=true,
    filter=true,
    format=true,
    cacheFile = { File(context.cacheDir, "swagger.json") },
    log = { level, tag, msg, error ->{
        when (level) {
            0 -> Log.d(tag, msg)
            1 -> Log.e(tag, msg)
        }
    }}
)

```
