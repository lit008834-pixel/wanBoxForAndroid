// @author 雾晚
package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real loopback proxy fixtures, with fictional data only. @author 雾晚 */
class LandingIpHttpTest {
    @Test fun lookupUsesMixedProxyAndDoesNotResolveTargetDirectly() = runBlocking {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val line = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader()
                        val first = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                        first
                    }
                }
                LandingIpHttp(server.localPort, null, null).use { client ->
                    assertEquals("{}", client.text("http://fixture.invalid/exit", "fixture"))
                }
                assertEquals("GET http://fixture.invalid/exit HTTP/1.1", line.get(2, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun backgroundCancellationClosesStalledRequestAndDoesNotWaitSixtySeconds() = runBlocking {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val accepted = CompletableDeferred<Unit>()
            try {
                val closed = executor.submit<Int> {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        accepted.complete(Unit)
                        socket.getInputStream().read()
                    }
                }
                LandingIpHttp(server.localPort, null, null).use { client ->
                    val job = launch { client.text("http://fixture.invalid/exit", "fixture") }
                    withTimeout(2000) { accepted.await() }
                    withTimeout(1000) { job.cancelAndJoin() }
                    assertEquals(-1, closed.get(2, TimeUnit.SECONDS))
                }
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun stalledResponseHasTotalDeadlineAndErrorStatusIsNotSuccess() = runBlocking {
        for (status in listOf(503, 0)) {
            ServerSocket(0).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val reply = executor.submit {
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            if (status == 0) Thread.sleep(300)
                            else socket.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    }
                    LandingIpHttp(server.localPort, null, null, 100).use { client ->
                        try { client.text("http://fixture.invalid/exit", "fixture"); fail("false success") }
                        catch (_: java.io.IOException) { }
                    }
                    reply.get(2, TimeUnit.SECONDS)
                } finally { executor.shutdownNow() }
            }
        }
    }
}
