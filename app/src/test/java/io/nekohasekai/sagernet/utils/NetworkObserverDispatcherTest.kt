// @author 雾晚
package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Exercises faulty observers and continued network event delivery. @author 雾晚 */
class NetworkObserverDispatcherTest {
    @Test fun changesToObserverMembershipApplyToTheNextEvent() {
        val received = mutableListOf<String>()
        val listeners = mutableListOf<(String) -> Unit>()
        listeners += { listeners.clear() }
        listeners += { received += it }
        NetworkObserverDispatcher.dispatch(listeners, "fixture-network") { throw AssertionError(it) }
        assertEquals(listOf("fixture-network"), received)
        NetworkObserverDispatcher.dispatch(listeners, "fixture-next") { throw AssertionError(it) }
        assertEquals(1, received.size)
    }

    @Test fun failingObserverDoesNotDropOtherObserversOrLaterEvents() {
        val received = mutableListOf<String?>()
        val failure = IllegalStateException("fixture observer failure")
        val errors = mutableListOf<Exception>()
        val listeners = listOf<(String?) -> Unit>(
            { throw failure }, { received += it }, { received += it }
        )
        listOf("fixture-wifi", null, "fixture-cellular").forEach { network ->
            NetworkObserverDispatcher.dispatch(listeners, network) { errors += it }
        }
        assertEquals(listOf("fixture-wifi", "fixture-wifi", null, null, "fixture-cellular", "fixture-cellular"), received)
        assertEquals(3, errors.size)
        errors.forEach { assertSame(failure, it) }
    }

    @Test fun retiredObserversCancellationDoesNotCancelTheSharedEventDispatcher() {
        var delivered = false
        var error: Exception? = null
        NetworkObserverDispatcher.dispatch(listOf<(Unit) -> Unit>(
            { throw CancellationException("fixture retired observer") }, { delivered = true }
        ), Unit) { error = it }
        assertTrue(delivered)
        assertTrue(error is CancellationException)
    }
}
