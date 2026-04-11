import com.cdahmad.swaggerlog.SwaggerLoggingInterceptor
import okhttp3.Interceptor
import java.io.File

object LogHelper {


    fun getInterceptor(
        apiUrl: String,
        format: Boolean,
        cacheFile: () -> File,
        log: (level: Int, tag: String, msg: String) -> Unit
    ): Interceptor? {
        return SwaggerLoggingInterceptor(
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