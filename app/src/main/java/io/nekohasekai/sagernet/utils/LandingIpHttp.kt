// @author 雾晚
package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded exit lookup through mixed inbound; never silently falls back to direct. @author 雾晚 */
internal class LandingIpHttp(port: Int, username: String?, password: String?, timeoutMs: Long = 2800) : AutoCloseable {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .proxyAuthenticator { _, response ->
            if (username.isNullOrBlank() || response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(username, password.orEmpty())).build()
        }.build()

    suspend fun text(url: String, userAgent: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("landing_ip_http_${it.code}")
                        SecureNetwork.text(it.body, 64 * 1024)
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
