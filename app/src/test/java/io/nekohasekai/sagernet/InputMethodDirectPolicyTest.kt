// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.route.InputMethodDirectPolicy
import org.junit.Assert.*
import org.junit.Test

/** Tests ordinary rule identity and migration equivalence, using fictional packages. @author 雾晚 */
class InputMethodDirectPolicyTest {
    @Test fun componentAndSecondaryUserUidArePreserved() {
        val identity = InputMethodDirectPolicy.identity("fixture.keyboard/.ImeService", 210123, 10101)!!
        assertEquals("fixture.keyboard", identity.packageName)
        assertEquals(210123, identity.uid)
        assertEquals("fixture.keyboard", InputMethodDirectPolicy.packageName("fixture.keyboard/fixture.keyboard.Ime\$Service"))
    }

    @Test fun unavailableMalformedPlatformIsolatedAndOwnIdentitiesFailClosed() {
        for (component in listOf(null, "", "fixture.keyboard", "fixture.keyboard/", "bad package/.Ime", "fixture.keyboard/.Ime/extra")) {
            assertNull(InputMethodDirectPolicy.identity(component, 10123, 10101))
        }
        for (uid in listOf(null, -1, 0, 1000, 201000, 99000, 299000, 10101)) {
            assertNull(InputMethodDirectPolicy.identity("fixture.keyboard/.Ime", uid, 10101))
        }
    }

    @Test fun ordinaryRuleRetainsPackageAndUserEnabledState() {
        val identity = InputMethodDirectPolicy.identity("fixture.keyboard/.Ime", 210123, 10101)!!
        for (enabled in listOf(false, true)) {
            val rule = InputMethodDirectPolicy.rule(identity, "Fixture keyboard", enabled)
            assertEquals(setOf(identity.packageName), rule.packages)
            assertEquals(-1L, rule.outbound)
            assertEquals(enabled, rule.enabled)
            assertTrue(InputMethodDirectPolicy.equivalent(rule, identity))
            assertFalse(InputMethodDirectPolicy.equivalent(rule.copy(outbound = 0), identity))
            assertFalse(InputMethodDirectPolicy.equivalent(rule.copy(domains = "full:fixture.invalid"), identity))
            assertFalse(InputMethodDirectPolicy.equivalent(rule.copy(packages = emptySet()), identity))
        }
    }
}
