// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.ServicePowerLocks
import org.junit.Assert.*
import org.junit.Test

/** Real ownership helper with fake platform locks and injected faults. @author 雾晚 */
class ServicePowerLocksTest {
    @Test fun repeatedAcquireStopAndRestartAreIdempotent() {
        val locks = ServicePowerLocks(); var acquired = 0; var released = 0
        val create = { ServicePowerLocks.CallbackLease({ acquired++ }, { released++ }) }
        repeat(4) { locks.acquire("cpu", create) }
        assertEquals(1, acquired); assertNull(locks.releaseAll()); assertNull(locks.releaseAll())
        assertEquals(1, released); locks.acquire("cpu", create); locks.releaseAll()
        assertEquals(2, acquired); assertEquals(2, released)
    }
    @Test fun partialAcquisitionFailureRollsBackBeforePropagation() {
        val locks = ServicePowerLocks(); var held = false
        val failure = IllegalStateException("fixture")
        try { locks.acquire("cpu") { ServicePowerLocks.CallbackLease({ held = true; throw failure }, { held = false }) }; fail() }
        catch (error: IllegalStateException) { assertSame(failure, error) }
        assertFalse(held); assertNull(locks.releaseAll())
    }
    @Test fun failedReleaseRetainsHandleAndDoesNotBlockOtherCleanup() {
        val locks = ServicePowerLocks(); var attempts = 0; var cpuReleased = false
        locks.acquire("cpu") { ServicePowerLocks.CallbackLease({}, { cpuReleased = true }) }
        locks.acquire("wifi") { ServicePowerLocks.CallbackLease({}, { if (attempts++ == 0) error("fixture") }) }
        assertNotNull(locks.releaseAll()); assertTrue(cpuReleased)
        assertNull(locks.releaseAll()); assertEquals(2, attempts)
    }
    @Test fun acquisitionAndCleanupErrorsKeepOriginalCause() {
        val locks = ServicePowerLocks(); val original = IllegalStateException("acquire")
        var failure = true
        try { locks.acquire("cpu") { ServicePowerLocks.CallbackLease({ throw original }, { if (failure) error("release") }) }; fail() }
        catch (error: IllegalStateException) { assertSame(original, error); assertEquals(1, error.suppressed.size) }
        failure = false; assertNull(locks.releaseAll())
    }
    @Test fun factoryFailureNeverCreatesAnOwnedLease() {
        val locks = ServicePowerLocks()
        try { locks.acquire("cpu") { error("factory") }; fail() } catch (_: IllegalStateException) {}
        assertNull(locks.releaseAll())
    }
    @Test fun optionalWifiFailureDoesNotReleaseCpuUntilStop() {
        val locks = ServicePowerLocks(); var cpu = false; var wifi = false
        locks.acquire("cpu") { ServicePowerLocks.CallbackLease({ cpu = true }, { cpu = false }) }
        try { locks.acquire("wifi") { ServicePowerLocks.CallbackLease({ wifi = true; error("fixture") }, { wifi = false }) }; fail() }
        catch (_: IllegalStateException) {}
        assertTrue(cpu); assertFalse(wifi); locks.releaseAll(); assertFalse(cpu)
    }
    @Test fun allModesWireTheSameOwnerAndSleepDoesNotPauseCore() {
        val folder = java.io.File("src/main/java/io/nekohasekai/sagernet/bg")
        listOf("VpnService", "RootTunService", "ProxyService").forEach {
            val source = java.io.File(folder, "$it.kt").readText()
            assertTrue(source.contains("override val powerLocks = ServicePowerLocks()"))
            assertTrue(source.contains("powerLocks.acquire(\"cpu\")"))
        }
        val base = java.io.File(folder, "BaseService.kt").readText()
        assertTrue(base.contains("powerLocks.releaseAll()")); assertTrue(base.contains("DataStore.acquireWakeLock"))
        assertFalse(base.lineSequence().any { !it.trim().startsWith("//") && it.contains("box?.sleep()") })
    }
}
