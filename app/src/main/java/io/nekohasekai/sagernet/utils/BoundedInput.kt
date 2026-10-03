// @author 雾晚
package io.nekohasekai.sagernet.utils

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Limits apply while reading, including streams without a length and compressed entries. */
object BoundedInput {
    const val JSON_BYTES = 16 * 1024 * 1024
    const val SOURCE_BYTES = 64 * 1024 * 1024
    const val ZIP_ENTRY_BYTES = 32 * 1024 * 1024
    const val ZIP_TOTAL_BYTES = 64 * 1024 * 1024
    const val ZIP_ENTRIES = 128

    class LimitExceeded(val limit: Int) : IOException("文件超过安全大小限制（${limit / 1024} KiB）")

    fun counting(input: InputStream, limit: Int): InputStream = object : FilterInputStream(input) {
        private var count = 0L
        private fun add(size: Int): Int {
            if (size > 0) {
                count += size
                if (count > limit) throw LimitExceeded(limit)
            }
            return size
        }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            add(input.read(buffer, offset, length))
        override fun skip(n: Long): Long {
            val buffer = ByteArray(8192)
            var skipped = 0L
            while (skipped < n) {
                val size = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                if (size < 0) break
                skipped += size
            }
            return skipped
        }
    }

    fun read(input: InputStream, limit: Int = JSON_BYTES): ByteArray {
        val counted = counting(input, limit)
        val buffer = ByteArray(8192)
        return ByteArrayOutputStream().use { output ->
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("读取已取消")
                val size = counted.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
    }

    fun text(input: InputStream, limit: Int = JSON_BYTES): String = read(input, limit).toString(Charsets.UTF_8)

    /** Drain every entry under a shared budget; directory entries also count. */
    fun zip(input: InputStream, validateName: (String) -> Unit = {}, consume: (String, ByteArray) -> Unit) {
        ZipInputStream(counting(input, SOURCE_BYTES)).use { zip ->
            var entries = 0
            var total = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                validateName(entry.name)
                if (++entries > ZIP_ENTRIES) throw IOException("压缩包文件数量超过安全限制")
                val bytes = read(zip, minOf(ZIP_ENTRY_BYTES, ZIP_TOTAL_BYTES - total))
                total += bytes.size
                if (!entry.isDirectory) consume(entry.name, bytes)
                zip.closeEntry()
            }
        }
    }

    fun backupZip(input: InputStream): String {
        var result: String? = null
        zip(input, validateName = { name ->
            if (name.startsWith('/') || '\\' in name || ':' in name || name.split('/').any { it == ".." })
                throw IOException("备份压缩包含无效路径")
        }) { name, bytes ->
            // @author 雾晚: mobile backup archives contain one root JSON, not arbitrary files/paths.
            if ('/' in name || '\\' in name || name == ".." || ':' in name || !name.endsWith(".json", ignoreCase = true))
                throw IOException("备份压缩包含不支持的路径或文件")
            if (name.endsWith(".json", ignoreCase = true)) {
                if (result != null) throw IOException("备份包含多个 JSON 文件")
                if (bytes.size > JSON_BYTES) throw LimitExceeded(JSON_BYTES)
                result = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
        }
        return result ?: throw IOException("备份缺少 JSON 文件")
    }
}
