// @author 雾晚
package io.nekohasekai.sagernet

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.king.zxing.util.CodeUtils
import io.nekohasekai.sagernet.utils.BoundedImageDecoder
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Arrays
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class ScannerBudgetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val message = "https://example.org/wanbox-qr-budget"

    // Stream grayscale rows so even the 100 MP fixture needs no full-size Bitmap.
    private fun image(file: File, width: Int, height: Int) {
        val qr = QRCodeWriter().encode(message, BarcodeFormat.QR_CODE, 0, 0)
        val scale = minOf(width, height) * 3 / 4 / qr.width
        val left = (width - qr.width * scale) / 2
        val top = (height - qr.height * scale) / 2
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { output ->
            val row = ByteArray(width + 1)
            var previous = -2
            for (y in 0 until height) {
                val qy = if (y in top until top + qr.height * scale) (y - top) / scale else -1
                if (qy != previous) {
                    Arrays.fill(row, 1, row.size, 255.toByte())
                    if (qy >= 0) for (x in 0 until qr.width) {
                        if (qr[x, qy]) Arrays.fill(row, 1 + left + x * scale, 1 + left + (x + 1) * scale, 0.toByte())
                    }
                    previous = qy
                }
                output.write(row)
            }
        }
        DataOutputStream(file.outputStream()).use { output ->
            output.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            fun chunk(type: String, data: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                val crc = CRC32().apply { update(name); update(data) }
                output.writeInt(data.size); output.write(name); output.write(data); output.writeInt(crc.value.toInt())
            }
            val header = ByteArrayOutputStream().apply {
                DataOutputStream(this).apply { writeInt(width); writeInt(height); write(byteArrayOf(8,0,0,0,0)); flush() }
            }.toByteArray()
            chunk("IHDR", header); chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        }
    }

    @Test fun highResolutionImagesDecodeWithinBudgetAndRemainReadable() {
        val file = File(context.cacheDir, "audit-large-qr.png")
        try {
            for ((width, height) in listOf(4000 to 3000, 8000 to 6000, 10000 to 10000)) {
                image(file, width, height)
                for (legacy in listOf(false, true)) {
                    val bitmap = BoundedImageDecoder.decode(context.contentResolver, Uri.fromFile(file), legacy)
                    try {
                        assertTrue(bitmap.width <= 2048 && bitmap.height <= 2048)
                        assertTrue(bitmap.allocationByteCount <= 16 * 1024 * 1024)
                        assertEquals(message, CodeUtils.parseCodeResult(bitmap)?.text)
                    } finally { bitmap.recycle() }
                }
            }
        } finally { file.delete() }
    }
}
