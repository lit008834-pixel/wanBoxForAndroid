// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class TileIconResourceTest {
    @Test
    fun defaultTileMatchesSuppliedTransparentCatImage() {
        val path = "src/main/res/drawable/ic_throne_tile.png"
        val file = listOf(File(path), File("app/$path")).first { it.isFile }
        val bytes = file.readBytes()
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            bytes.copyOfRange(0, 8)
        )
        assertEquals(512, ByteBuffer.wrap(bytes, 16, 4).int)
        assertEquals(512, ByteBuffer.wrap(bytes, 20, 4).int)
        assertEquals(8, bytes[24].toInt()) // Eight-bit channels.
        assertEquals(6, bytes[25].toInt()) // RGBA, including transparency.
        assertEquals(
            "bbb0fd65faad3c5a831c861ac4f391088e7e1776c441f353f726358a3e4049b2",
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        )
    }
}
