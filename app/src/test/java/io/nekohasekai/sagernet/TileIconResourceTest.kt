// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class TileIconResourceTest {
    @Test
    fun defaultTileHasVisibleShapeAndTransparentBackground() {
        val path = "src/main/res/drawable/ic_throne_tile.png"
        val file = listOf(File(path), File("app/$path")).first { it.isFile }
        val image = ImageIO.read(file)
        assertEquals(512, image.width)
        assertEquals(512, image.height)

        var transparent = 0
        var visible = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (pixel ushr 24 == 0) transparent++
                else {
                    visible++
                    assertEquals(0xFFFFFF, pixel and 0xFFFFFF)
                }
            }
        }
        assertTrue(transparent > 0)
        assertTrue(visible > 0)
    }
}
