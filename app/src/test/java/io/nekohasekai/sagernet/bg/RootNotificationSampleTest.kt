// @author 雾晚
package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

/** @author 雾晚 */
class RootNotificationSampleTest {
    private fun sample(tag: String = "p-3", tx: String = "5") =
        """WANBOX_STATS:{"tag":"$tag","tx":$tx,"rx":7,"directTx":1,"directRx":2}"""
    @Test fun readsActualMemberAndIndependentRates() {
        assertEquals(RootNotificationSample("p-3", 5, 7, 1, 2), RootNotificationSample.parse(sample()))
        assertEquals("", RootNotificationSample.parse(sample(""))!!.tag)
    }
    @Test fun invalidSamplesCannotProduceSpeedsOrRememberOldMembers() {
        for (line in listOf("Root TUN: " + sample(), "WANBOX_STATS:{}", "WANBOX_STATS:broken",
            sample(tx = "-1"), sample(tx = "1.5"), sample(tx = "\"5\""),
            sample(tx = "9223372036854775808"), sample("a".repeat(2000)), sample("bad\\nname"))) {
            assertNull(line.take(35), RootNotificationSample.parse(line))
        }
    }
}
