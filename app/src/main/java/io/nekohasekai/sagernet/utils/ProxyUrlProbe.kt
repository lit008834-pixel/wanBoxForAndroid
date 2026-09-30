// @author 雾晚
package io.nekohasekai.sagernet.utils

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** 一次完整 HTTP 响应头探测，连接、认证、TLS 共用一个超时预算。 */
object ProxyUrlProbe {
    fun measure(url: String, port: Int, timeoutMs: Int, username: String?, password: String?): Int {
        val builder = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(timeoutMs.coerceIn(1, 30_000).toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
        if (!username.isNullOrBlank()) {
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(username, password.orEmpty()))
                    .build()
            }
        }
        val client = builder.build()
        try {
            val request = Request.Builder().url(url).get().build()
            val start = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) throw IOException("HTTP ${response.code}")
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    .coerceAtLeast(1).toInt()
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
