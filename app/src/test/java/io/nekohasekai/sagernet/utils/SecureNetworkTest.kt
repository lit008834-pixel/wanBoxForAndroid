// @author 雾晚
package io.nekohasekai.sagernet.utils

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SecureNetworkTest {
    @Test fun externalHttpFailsBeforeNetworkAndCredentialsAreSent() {
        val client = SecureNetwork.webDAVClient()
        val request = Request.Builder().url("http://example.com/backup")
            .header("Authorization", "Basic secret").build()
        try { client.newCall(request).execute(); fail() }
        catch (error: IOException) { assertTrue(error.message!!.contains("HTTPS")) }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun httpsAndExactLoopbackOnly() {
        for (url in listOf("https://example.com/", "http://127.0.0.1/", "http://localhost/", "http://[::1]/")) {
            SecureNetwork.requireSecure(url.toHttpUrl())
        }
        for (url in listOf("http://127.0.0.1.example.com/", "http://192.168.1.1/", "http://example.com/")) {
            try { SecureNetwork.requireSecure(url.toHttpUrl()); fail() } catch (_: IOException) {}
        }
        assertFalse(SecureNetwork.webDAVClient().followRedirects)
    }
}
