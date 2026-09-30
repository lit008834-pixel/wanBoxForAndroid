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
}
