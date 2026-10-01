// @author 雾晚
package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ConnectedUrlTestTest {
    @Test fun vpnUsesActiveCoreAndPassesExactParameters() {
        val box = Any()
        for (url in listOf("https://default.invalid/204", "http://custom.invalid/test")) {
            assertEquals(37, ConnectedUrlTest.measure(true, url, 731, null, { box }) { core, target, timeout ->
                assertSame(box, core); assertEquals(url, target); assertEquals(731, timeout); 37
            })
        }
    }

    @Test fun rootNeverReadsAbsentAppBoxForDefaultOrCustomUrl() {
        for (url in listOf("https://default.invalid/204", "http://custom.invalid/test")) {
            assertEquals(49, ConnectedUrlTest.measure<Any>(true, url, 953,
                { target, timeout -> assertEquals(url, target); assertEquals(953, timeout); 49 },
                { error("Root must not touch app box") }, { _, _, _ -> error("Wrong core") }))
        }
    }

    @Test fun disconnectedNeverInvokesEitherBackend() {
        assertEquals(0, ConnectedUrlTest.measure<Any>(false, "http://test/", 100,
            { _, _ -> error("root") }, { error("box") }, { _, _, _ -> error("core") }))
    }

    @Test fun backendFailureIsNotConvertedIntoSuccess() {
        try {
            ConnectedUrlTest.measure<Any>(true, "http://test/", 100,
                { _, _ -> throw java.io.IOException("timeout") }, { error("box") }, { _, _, _ -> 999 })
            fail("Failure must propagate to the service boundary")
        } catch (_: java.io.IOException) { }
    }

    @Test fun rootChecksReadinessBeforeAndAfterProbeIncludingFallback() {
        assertEquals(0, ConnectedUrlTest.guardedRoot({ false }) { error("not ready") })
        var ready = true
        assertEquals(0, ConnectedUrlTest.guardedRoot({ ready }) { ready = false; 123 })
        assertEquals(0, ConnectedUrlTest.guardedRoot({ true }) { 0 })
        assertEquals(53, ConnectedUrlTest.guardedRoot({ true }) { 53 })
    }

    private class FakeProcess(var running: Boolean) : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue(): Int { if (running) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { running = false }
    }

    @Test fun rootExitIsDetectedWithoutNewAndroidProcessApi() {
        assertFalse(ConnectedUrlTest.processAlive(null))
        val process = FakeProcess(true)
        assertTrue(ConnectedUrlTest.processAlive(process))
        process.destroy()
        assertFalse(ConnectedUrlTest.processAlive(process))
    }
}
