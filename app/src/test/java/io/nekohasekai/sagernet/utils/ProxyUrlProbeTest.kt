// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyUrlProbeTest {
    @Test fun usesExplicitProxyWithoutResolvingTargetLocally() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write(
                            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray()
                        )
                        line
                    }
                }
                assertTrue(ProxyUrlProbe.measure("http://unresolvable.invalid/generate_204",
                    server.localPort, 3000, null, null) > 0)
                assertEquals("GET http://unresolvable.invalid/generate_204 HTTP/1.1",
                    received.get(3, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun stalledProxyIsBoundedByTotalDeadline() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val stalled = executor.submit {
                    server.accept().use { Thread.sleep(300) }
                }
                val started = System.nanoTime()
                try {
                    ProxyUrlProbe.measure("https://unresolvable.invalid/",
                        server.localPort, 100, null, null)
                    fail("Expected timeout")
                } catch (_: java.io.IOException) {
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
                }
                stalled.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun httpProxyAuthenticationStopsAfterOneChallenge() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val requests = executor.submit<List<List<String>>> {
                    (1..2).map {
                        server.accept().use { socket ->
                            socket.soTimeout = 2000
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = mutableListOf<String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                headers += line
                            }
                            socket.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                                "Proxy-Authenticate: Basic realm=wanbox\r\nContent-Length: 0\r\n" +
                                "Connection: close\r\n\r\n").toByteArray())
                            headers
                        }
                    }
                }
                try {
                    ProxyUrlProbe.measure("http://unresolvable.invalid/test", server.localPort,
                        2000, "alice", "secret")
                    fail("Repeated authentication failure must fail")
                } catch (_: java.io.IOException) { }
                val headers = requests.get(3, TimeUnit.SECONDS)
                assertEquals("GET http://unresolvable.invalid/test HTTP/1.1", headers[0][0])
                assertFalse(headers[0].any { it.startsWith("Proxy-Authorization:") })
                assertTrue(headers[1].contains("Proxy-Authorization: Basic YWxpY2U6c2VjcmV0"))
                server.soTimeout = 150
                try { server.accept().close(); fail("Authentication loop") }
                catch (_: SocketTimeoutException) { }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun redirectsAndNon2xxFailAndReleaseKeepAliveSocket() {
        for (code in listOf(302, 503)) {
            ServerSocket(0).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val closed = executor.submit<Boolean> {
                        server.accept().use { socket ->
                            socket.soTimeout = 2000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            socket.getOutputStream().write(("HTTP/1.1 $code Error\r\n" +
                                "Location: http://should-not-follow.invalid/\r\n" +
                                "Content-Length: 0\r\nConnection: keep-alive\r\n\r\n").toByteArray())
                            reader.read() == -1
                        }
                    }
                    try {
                        ProxyUrlProbe.measure("http://unresolvable.invalid/", server.localPort,
                            1000, null, null)
                        fail("HTTP $code must fail")
                    } catch (error: java.io.IOException) { assertEquals("HTTP $code", error.message) }
                    assertTrue("Pool cleanup must close the socket", closed.get(3, TimeUnit.SECONDS))
                    server.soTimeout = 150
                    try { server.accept().close(); fail("Redirect followed") }
                    catch (_: SocketTimeoutException) { }
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun authenticationAndResponseShareOneTotalDeadline() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val stalled = executor.submit {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        Thread.sleep(400)
                        socket.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                            "Proxy-Authenticate: Basic realm=wanbox\r\nContent-Length: 0\r\n" +
                            "Connection: close\r\n\r\n").toByteArray())
                    }
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        // A fresh per-retry deadline would allow this 204; one total deadline cannot.
                        Thread.sleep(350)
                        try {
                            socket.getOutputStream().write(
                                "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                        } catch (_: java.io.IOException) { /* the correctly timed-out client closed */ }
                    }
                }
                val started = System.nanoTime()
                try {
                    ProxyUrlProbe.measure("http://unresolvable.invalid/", server.localPort,
                        600, "alice", "secret")
                    fail("Expected total timeout")
                } catch (_: java.io.IOException) {
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    assertTrue("One 600ms deadline, elapsed=$elapsed", elapsed < 1000)
                }
                stalled.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun httpsUsesConnectAuthorityWithoutLocalTargetResolution() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write(
                            "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        line
                    }
                }
                try {
                    ProxyUrlProbe.measure("https://unresolvable.invalid/path", server.localPort,
                        1000, null, null)
                    fail("CONNECT failure must fail")
                } catch (_: java.io.IOException) { }
                assertEquals("CONNECT unresolvable.invalid:443 HTTP/1.1", request.get(3, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

}
