// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BoundedInputTest {
    @Test fun exactLimitAndOneByteOverWithoutContentLength() {
        assertArrayEquals(ByteArray(10), BoundedInput.read(ByteArrayInputStream(ByteArray(10)), 10))
        val unbounded = object : InputStream() {
            var count = 0
            override fun read(): Int { count++; return 0 }
        }
        try { BoundedInput.read(unbounded, 10); fail() }
        catch (_: BoundedInput.LimitExceeded) { assertTrue(unbounded.count <= 8192) }
    }

    @Test fun zipBombStopsBeforeConsumer() {
        val zip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { out ->
                out.putNextEntry(ZipEntry("backup.json"))
                val zero = ByteArray(8192)
                repeat(BoundedInput.ZIP_ENTRY_BYTES / zero.size + 1) { out.write(zero) }
            }
        }.toByteArray()
        var consumed = false
        try { BoundedInput.zip(zip.inputStream()) { _, _ -> consumed = true }; fail() }
        catch (_: BoundedInput.LimitExceeded) { assertFalse(consumed) }
    }

    @Test fun zipEntryCountAndDuplicateBackupAreRejected() {
        fun zip(count: Int) = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { out ->
                repeat(count) {
                    out.putNextEntry(ZipEntry("$it.json"))
                    out.write("{}".toByteArray())
                    out.closeEntry()
                }
            }
        }.toByteArray()
        try { BoundedInput.zip(zip(129).inputStream()) { _, _ -> }; fail() }
        catch (_: java.io.IOException) {}
        try { BoundedInput.backupZip(zip(2).inputStream()); fail() }
        catch (_: java.io.IOException) {}
        assertEquals("{}", BoundedInput.backupZip(zip(1).inputStream()))
    }

    @Test fun imageBudgetBoundsCommonHighResolutionImages() {
        for ((width, height) in listOf(4000 to 3000, 8000 to 6000, 11548 to 8660, 100000 to 1)) {
            val (w, h) = ImageBudget.target(width, height)
            assertTrue(w in 1..2048 && h in 1..2048)
            assertTrue(w.toLong() * h <= 4194304)
            val sample = ImageBudget.sample(width, height)
            assertTrue((width.toLong() + sample - 1) / sample <= 2048)
            assertTrue((height.toLong() + sample - 1) / sample <= 2048)
        }
    }
}
