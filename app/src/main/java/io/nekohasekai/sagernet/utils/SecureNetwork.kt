// @author 雾晚
package io.nekohasekai.sagernet.utils

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.io.IOException

object SecureNetwork {
    fun isLoopback(host: String): Boolean =
        host == "127.0.0.1" || host == "localhost" || host == "::1"

    fun requireSecure(url: HttpUrl) {
        if (!url.isHttps && !isLoopback(url.host)) {
            throw IOException("外部服务器必须使用 HTTPS，以保护订阅和备份凭据")
        }
    }

    fun webDAVClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            requireSecure(chain.request().url)
            chain.proceed(chain.request())
        }.build()

    fun text(body: ResponseBody?, limit: Int = BoundedInput.JSON_BYTES): String {
        body ?: throw IOException("服务器返回空内容")
        if (body.contentLength() > limit) throw BoundedInput.LimitExceeded(limit)
        return body.byteStream().use { BoundedInput.text(it, limit) }
    }
}
